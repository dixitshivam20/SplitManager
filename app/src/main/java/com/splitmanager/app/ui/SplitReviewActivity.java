package com.splitmanager.app.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.splitmanager.app.api.SplitwiseApiClient;
import com.splitmanager.app.databinding.ActivitySplitReviewBinding;
import com.splitmanager.app.model.SplitwiseGroup;
import com.splitmanager.app.service.PaymentService;
import com.splitmanager.app.util.SecurePrefsHelper;
import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SplitReviewActivity extends AppCompatActivity {

    private static final String TAG = "SplitManager.SplitReview";

    private static final int SPLIT_EQUAL  = 0;
    private static final int SPLIT_CUSTOM = 1;

    private ActivitySplitReviewBinding binding;
    private SplitwiseApiClient apiClient;
    private List<SplitwiseGroup> groups = new ArrayList<>();
    private SplitwiseGroup selectedGroup = null;
    private double amount;
    private String merchant;
    private int splitType = SPLIT_EQUAL;
    private ExecutorService executor;

    // Per-member UI state
    private final Map<Long, CheckBox> memberCheckboxes   = new HashMap<>();
    private final Map<Long, EditText> memberAmountInputs = new HashMap<>();

    // Tracks which members have been manually edited in Custom mode (locked from auto-redistribution)
    private final Set<Long> manuallyEditedMembers = new HashSet<>();

    // Guards against listener re-entrancy while we update fields programmatically
    private boolean updatingProgrammatically = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySplitReviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                             WindowManager.LayoutParams.FLAG_SECURE);

        // Init executor BEFORE any early return so onDestroy() can always call shutdown()
        executor = Executors.newSingleThreadExecutor();

        amount = getIntent().getDoubleExtra(PaymentService.EXTRA_AMOUNT, -1);
        if (amount <= 0 || amount >= 1_000_000 || Double.isNaN(amount) || Double.isInfinite(amount)) {
            showError("Invalid payment amount. Please try again.");
            binding.btnSplitConfirm.setEnabled(false);
            return;
        }

        merchant = getIntent().getStringExtra(PaymentService.EXTRA_MERCHANT);
        String method = getIntent().getStringExtra(PaymentService.EXTRA_METHOD);
        String source = getIntent().getStringExtra(PaymentService.EXTRA_SOURCE);

        binding.tvAmount.setText(String.format("\u20B9%.0f", amount));
        binding.tvMerchant.setText(merchant != null ? merchant : "Unknown");
        binding.tvMethod.setText(method != null ? method.replace("_", " ") : "Payment");
        binding.tvSource.setText(source != null ? source : "SMS");

        String apiKey = "";
        try { apiKey = SecurePrefsHelper.getApiKey(this); } catch (Exception ignored) {}
        if (apiKey.isEmpty()) {
            showError("Splitwise not configured. Open Settings to add your API key.");
            binding.btnSplitConfirm.setEnabled(false);
            return;
        }

        apiClient = new SplitwiseApiClient(apiKey);
        loadGroups();

        binding.btnSplitConfirm.setOnClickListener(v -> confirmSplit());
        binding.btnIgnore.setOnClickListener(v -> finish());

        // Two chips: Equal Split and Custom Amounts
        binding.chipEqualSplit.setChecked(true);
        binding.chipEqualSplit.setOnCheckedChangeListener((b, checked) -> {
            if (checked && splitType != SPLIT_EQUAL) {
                splitType = SPLIT_EQUAL;
                manuallyEditedMembers.clear();
                rebuildMemberRows();
            }
        });
        binding.chipCustomSplit.setOnCheckedChangeListener((b, checked) -> {
            if (checked && splitType != SPLIT_CUSTOM) {
                splitType = SPLIT_CUSTOM;
                manuallyEditedMembers.clear();
                rebuildMemberRows();
            }
        });
    }

    // ── Group loading ────────────────────────────────────────────────────────

    private void loadGroups() {
        binding.loadingGroups.setVisibility(View.VISIBLE);
        binding.chipGroupContainer.removeAllViews();

        executor.submit(() -> {
            try {
                boolean authed = apiClient.authenticate();
                if (!authed) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        showError("Invalid Splitwise API key. Go to Settings to update.");
                    });
                    return;
                }
                List<SplitwiseGroup> fetched = apiClient.getGroups();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    binding.loadingGroups.setVisibility(View.GONE);
                    groups = fetched;
                    displayGroups(fetched);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load groups", e);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    binding.loadingGroups.setVisibility(View.GONE);
                    showError("Could not load Splitwise groups. Check connection.");
                });
            }
        });
    }

    private void displayGroups(List<SplitwiseGroup> groups) {
        binding.chipGroupContainer.removeAllViews();
        if (groups.isEmpty()) { binding.tvNoGroups.setVisibility(View.VISIBLE); return; }
        binding.tvNoGroups.setVisibility(View.GONE);

        for (SplitwiseGroup group : groups) {
            Chip chip = new Chip(this);
            chip.setText(group.getName() + " (" + group.getMemberCount() + ")");
            chip.setCheckable(true);
            chip.setOnCheckedChangeListener((b, checked) -> {
                if (checked) {
                    selectedGroup = group;
                    manuallyEditedMembers.clear();
                    rebuildMemberRows();
                    binding.btnSplitConfirm.setEnabled(true);
                }
            });
            binding.chipGroupContainer.addView(chip);
        }
        binding.btnSplitConfirm.setEnabled(false);
    }

    // ── Member rows ──────────────────────────────────────────────────────────

    /**
     * Rebuilds per-member rows from scratch.
     *
     * Equal Split mode:
     *   - All members ticked by default.
     *   - Amount fields are READ-ONLY — computed automatically.
     *   - Unticking a member redistributes the full amount equally among remaining ticked members.
     *   - Re-ticking redistributes equally again.
     *   - Amount fields always sum exactly to total (last person absorbs paise rounding).
     *
     * Custom Amount mode:
     *   - All members ticked by default with equal pre-fill.
     *   - Amount fields are EDITABLE.
     *   - Editing any field does NOT auto-redistribute (user controls all values).
     *   - A warning shows if amounts don't sum to total.
     *   - Unticking zeros out that member; re-ticking restores their equal share.
     */
    private void rebuildMemberRows() {
        if (selectedGroup == null || selectedGroup.getMembers().isEmpty()) return;

        binding.layoutMemberRows.removeAllViews();
        memberCheckboxes.clear();
        memberAmountInputs.clear();

        List<SplitwiseGroup.Member> members = selectedGroup.getMembers();

        for (SplitwiseGroup.Member member : members) {
            boolean isYou = member.getId() == apiClient.getCurrentUserId();
            String displayName = isYou ? "You" : member.getFirstName();

            // ── Row container ────────────────────────────────────────────────
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, 12);
            row.setLayoutParams(rowParams);

            // ── Name label ───────────────────────────────────────────────────
            TextView tvName = new TextView(this);
            tvName.setText(displayName);
            tvName.setTextSize(14f);
            tvName.setTextColor(ContextCompat.getColor(this, com.splitmanager.app.R.color.text_primary));
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvName.setLayoutParams(nameParams);
            row.addView(tvName);

            // ── Checkbox ─────────────────────────────────────────────────────
            CheckBox cb = new CheckBox(this);
            cb.setChecked(true);   // everyone included by default
            LinearLayout.LayoutParams cbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cbParams.setMarginEnd(8);
            cb.setLayoutParams(cbParams);
            memberCheckboxes.put(member.getId(), cb);
            row.addView(cb);

            // ── Amount field ─────────────────────────────────────────────────
            EditText etAmount = new EditText(this);
            etAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            etAmount.setTextSize(14f);
            etAmount.setTextColor(ContextCompat.getColor(this, com.splitmanager.app.R.color.text_primary));
            etAmount.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_END);
            etAmount.setBackground(null);
            LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(
                200, LinearLayout.LayoutParams.WRAP_CONTENT);
            etAmount.setLayoutParams(etParams);

            // Equal split: read-only. Custom: editable.
            etAmount.setEnabled(splitType == SPLIT_CUSTOM);
            etAmount.setAlpha(splitType == SPLIT_CUSTOM ? 1f : 0.6f);

            memberAmountInputs.put(member.getId(), etAmount);
            row.addView(etAmount);
            binding.layoutMemberRows.addView(row);

            final long memberId = member.getId();

            // ── Checkbox listener ────────────────────────────────────────────
            cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (updatingProgrammatically) return;
                if (!isChecked) {
                    // Exclude: zero out, remove lock, redistribute remaining among others
                    manuallyEditedMembers.remove(memberId);
                    updatingProgrammatically = true;
                    etAmount.setText("0");
                    etAmount.setEnabled(false);
                    etAmount.setAlpha(0.3f);
                    updatingProgrammatically = false;
                    redistributeRemainingAmongFree(memberId);
                } else {
                    // Re-include: remove lock so this member is "free" again
                    manuallyEditedMembers.remove(memberId);
                    etAmount.setEnabled(splitType == SPLIT_CUSTOM);
                    etAmount.setAlpha(splitType == SPLIT_EQUAL ? 0.6f : 1f);
                    if (splitType == SPLIT_EQUAL) {
                        // Equal mode: reset everyone equally (no locks)
                        redistributeEquallyAmongChecked();
                    } else {
                        // Custom mode: keep other members' locked amounts,
                        // spread remaining among free members (including this newly re-ticked one)
                        redistributeRemainingAmongFree(-1);
                    }
                }
            });

            // ── Amount edit listener (Custom mode only) ───────────────────────
            etAmount.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    if (updatingProgrammatically) return;
                    if (splitType == SPLIT_CUSTOM) {
                        // Mark this member as manually edited (locked)
                        manuallyEditedMembers.add(memberId);
                        // Auto-distribute remaining amount among non-edited checked members
                        redistributeRemainingAmongFree(memberId);
                    }
                }
            });
        }

        binding.cardSplitPreview.setVisibility(View.VISIBLE);

        // Initial distribution — all members checked, split equally
        redistributeEquallyAmongChecked();
    }

    /**
     * Distributes `amount` equally among all currently-checked members.
     * Uses integer paise arithmetic so shares always sum exactly to total.
     * Last checked member absorbs any rounding difference (at most 1 paise per person).
     *
     * Works for BOTH equal and custom modes when called from checkbox toggle.
     * In custom mode after this, user can freely edit individual amounts.
     */
    private void redistributeEquallyAmongChecked() {
        updatingProgrammatically = true;

        // Collect checked member IDs in stable order
        List<Long> checkedIds = new ArrayList<>();
        for (SplitwiseGroup.Member m : selectedGroup.getMembers()) {
            CheckBox cb = memberCheckboxes.get(m.getId());
            if (cb != null && cb.isChecked()) checkedIds.add(m.getId());
        }

        if (checkedIds.isEmpty()) {
            updatingProgrammatically = false;
            updateRunningTotal();
            return;
        }

        // Integer paise arithmetic — no floating point rounding errors
        long totalPaise = Math.round(amount * 100);
        long perPersonPaise = totalPaise / checkedIds.size();
        long remainderPaise = totalPaise - perPersonPaise * checkedIds.size();

        for (int i = 0; i < checkedIds.size(); i++) {
            EditText et = memberAmountInputs.get(checkedIds.get(i));
            if (et == null) continue;
            // Last person absorbs the remainder (max 1 paisa per person, negligible)
            long sharePaise = perPersonPaise + (i == checkedIds.size() - 1 ? remainderPaise : 0);
            // Display as whole rupees (no decimals shown) but value is exact
            et.setText(String.format("%.2f", sharePaise / 100.0)
                .replaceAll("\\.00$", ""));  // "35" not "35.00", "35.33" stays
        }

        // Zero out unchecked members
        for (Map.Entry<Long, CheckBox> e : memberCheckboxes.entrySet()) {
            if (!e.getValue().isChecked()) {
                EditText et = memberAmountInputs.get(e.getKey());
                if (et != null) et.setText("0");
            }
        }

        updatingProgrammatically = false;
        updateRunningTotal();
    }

    /**
     * After a member's amount is manually edited or a member is excluded/re-included:
     * Sums all locked amounts (manually edited + excluded=0), then spreads
     * the remainder equally among free (checked, not manually edited) members.
     * The last free member absorbs any paise rounding difference.
     *
     * @param changedMemberId the member who just changed (locked; skip from free targets).
     *                        Pass -1 when re-including a member (no extra lock needed).
     */
    private void redistributeRemainingAmongFree(long changedMemberId) {
        updatingProgrammatically = true;

        long lockedPaise = 0;
        List<Long> freeIds = new ArrayList<>();

        for (SplitwiseGroup.Member m : selectedGroup.getMembers()) {
            long id = m.getId();
            CheckBox cb = memberCheckboxes.get(id);
            EditText et = memberAmountInputs.get(id);
            if (cb == null || et == null) continue;

            if (!cb.isChecked()) {
                // Excluded — locked at 0
            } else if (id == changedMemberId || manuallyEditedMembers.contains(id)) {
                // Manually edited or just changed — locked at their current value
                lockedPaise += Math.round(parseAmount(et.getText().toString()) * 100);
            } else {
                // Free — will receive the remaining amount
                freeIds.add(id);
            }
        }

        long totalPaise = Math.round(amount * 100);
        long remainingPaise = Math.max(0, totalPaise - lockedPaise);

        if (!freeIds.isEmpty()) {
            long perFreePaise = remainingPaise / freeIds.size();
            long remainderPaise = remainingPaise - perFreePaise * freeIds.size();

            for (int i = 0; i < freeIds.size(); i++) {
                EditText et = memberAmountInputs.get(freeIds.get(i));
                if (et == null) continue;
                long sharePaise = perFreePaise + (i == freeIds.size() - 1 ? remainderPaise : 0);
                et.setText(String.format("%.2f", sharePaise / 100.0)
                    .replaceAll("\\.00$", ""));
            }
        }

        updatingProgrammatically = false;
        updateRunningTotal();
    }

    private void updateRunningTotal() {
        long totalPaise = Math.round(amount * 100);
        long assignedPaise = 0;
        for (EditText et : memberAmountInputs.values()) {
            assignedPaise += Math.round(parseAmount(et.getText().toString()) * 100);
        }

        binding.tvTotalAssigned.setText(String.format("\u20B9%.0f", amount));

        long diffPaise = totalPaise - assignedPaise;
        // In equal mode: always exact. In custom mode: show warning if off by more than 1 paisa.
        if (splitType == SPLIT_CUSTOM && Math.abs(diffPaise) > 1) {
            String warning = diffPaise > 0
                ? String.format("\u20B9%.2f still unassigned", diffPaise / 100.0)
                : String.format("\u20B9%.2f over total", -diffPaise / 100.0);
            binding.tvSplitWarning.setText(warning);
            binding.tvSplitWarning.setVisibility(View.VISIBLE);
            binding.btnSplitConfirm.setEnabled(false);
        } else {
            binding.tvSplitWarning.setVisibility(View.GONE);
            binding.btnSplitConfirm.setEnabled(selectedGroup != null);
        }
    }

    // ── Confirm split ────────────────────────────────────────────────────────

    private void confirmSplit() {
        if (selectedGroup == null) {
            Toast.makeText(this, "Please select a group", Toast.LENGTH_SHORT).show();
            return;
        }

        // Collect included members and their paise shares
        List<SplitwiseGroup.Member> includedMembers = new ArrayList<>();
        List<Long> paiseShares = new ArrayList<>();

        for (SplitwiseGroup.Member member : selectedGroup.getMembers()) {
            CheckBox cb = memberCheckboxes.get(member.getId());
            EditText et = memberAmountInputs.get(member.getId());
            if (cb == null || et == null || !cb.isChecked()) continue;

            long paise = Math.round(parseAmount(et.getText().toString()) * 100);
            if (paise <= 0) continue;

            includedMembers.add(member);
            paiseShares.add(paise);
        }

        if (includedMembers.isEmpty()) {
            Toast.makeText(this, "Please include at least one member", Toast.LENGTH_SHORT).show();
            return;
        }

        // Final rounding correction — make shares sum exactly to total
        long totalPaise = Math.round(amount * 100);
        long assignedPaise = 0;
        for (long p : paiseShares) assignedPaise += p;
        long diff = totalPaise - assignedPaise;
        if (diff != 0) {
            // Absorb into last member's share (diff is always tiny: ±1 paise per member)
            paiseShares.set(paiseShares.size() - 1,
                paiseShares.get(paiseShares.size() - 1) + diff);
        }

        // Convert paise to rupee doubles for the API
        final List<Double> finalShares = new ArrayList<>();
        for (long p : paiseShares) finalShares.add(p / 100.0);

        binding.btnSplitConfirm.setEnabled(false);
        binding.btnSplitConfirm.setText("Adding to Splitwise...");
        binding.progressBar.setVisibility(View.VISIBLE);

        String rawDesc = binding.tvMerchant.getText().toString().trim();
        final String description = rawDesc.isEmpty() ? (merchant != null ? merchant : "Expense") : rawDesc;
        final List<SplitwiseGroup.Member> finalMembers = includedMembers;

        executor.submit(() -> {
            try {
                // Always use explicit shares — gives us full control over rounding.
                // The API's split_equally=true uses Splitwise's own rounding which
                // sometimes mismatches ours and returns errors[].
                String expenseId = apiClient.createCustomSplit(
                    selectedGroup.getId(), amount, description, finalMembers, finalShares);

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    binding.progressBar.setVisibility(View.GONE);
                    if (expenseId != null) {
                        showSuccess(finalMembers.size());
                    } else {
                        showError("Could not confirm split. Check Splitwise to verify.");
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Split creation error", e);
                final String errMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    binding.progressBar.setVisibility(View.GONE);
                    binding.btnSplitConfirm.setEnabled(true);
                    binding.btnSplitConfirm.setText("Confirm Split");
                    showError(errMsg);
                });
            }
        });
    }

    private void showSuccess(int memberCount) {
        binding.cardPaymentInfo.setVisibility(View.GONE);
        binding.cardGroupSelect.setVisibility(View.GONE);
        binding.cardSplitPreview.setVisibility(View.GONE);
        binding.layoutActions.setVisibility(View.GONE);
        binding.cardSuccess.setVisibility(View.VISIBLE);
        binding.tvSuccessAmount.setText(String.format("\u20B9%.0f", amount));
        binding.tvSuccessGroup.setText(selectedGroup.getName());
        binding.tvSuccessMembers.setText(memberCount + " people notified on Splitwise");
        binding.btnDone.setOnClickListener(v -> finish());
    }

    private void showError(String msg) {
        binding.tvError.setText(msg);
        binding.tvError.setVisibility(View.VISIBLE);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static double parseAmount(String s) {
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) { executor.shutdown(); executor = null; }
    }
}
