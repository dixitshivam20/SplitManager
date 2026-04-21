package com.splitmanager.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.splitmanager.app.R;
import com.splitmanager.app.model.PaymentEvent;
import com.splitmanager.app.parser.PaymentParser;
import com.splitmanager.app.service.PaymentService;

/**
 * Share target — appears in the Android share sheet when the user shares text
 * from any app (Messages, WhatsApp, bank apps, etc.).
 *
 * Flow:
 *   1. Receives the shared plain text via ACTION_SEND intent.
 *   2. Runs it through PaymentParser.
 *   3a. If parsing succeeds → launches SplitReviewActivity pre-filled.
 *   3b. If parsing fails (no amount found, or promotional message) →
 *       shows the raw text + a manual amount entry field so the user can
 *       still split the payment after correcting the amount.
 *
 * Security:
 *   - FLAG_SECURE prevents screenshots of financial text shared into the app.
 *   - Shared text is never stored or logged — only parsed in memory.
 *   - The intent extra EXTRA_TEXT is sanitised before passing downstream.
 *   - Max input length enforced before passing to PaymentParser.
 */
public class ShareTargetActivity extends AppCompatActivity {

    private static final int MAX_SHARED_TEXT_LENGTH = 2000;
    private static final int MAX_SANITISED_LENGTH   = 80;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // FLAG_SECURE: shared text may contain financial PII — prevent screenshots
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                             WindowManager.LayoutParams.FLAG_SECURE);

        Intent intent = getIntent();
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            finish();
            return;
        }

        // Extract and cap the shared text
        String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (sharedText == null || sharedText.trim().isEmpty()) {
            Toast.makeText(this, "No text received to parse.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (sharedText.length() > MAX_SHARED_TEXT_LENGTH) {
            sharedText = sharedText.substring(0, MAX_SHARED_TEXT_LENGTH);
        }
        final String text = sharedText.trim();

        // Try to parse as a payment message
        // isBankSender check is skipped — the user is manually sharing the message,
        // which implies intent. We still run isPaymentMessage to catch promotional
        // messages (loan offers, etc.) and extract a valid amount.
        PaymentEvent event = null;
        if (PaymentParser.isPaymentMessage(text)) {
            event = PaymentParser.parse(text, "share");
        }

        if (event != null && event.getAmount() > 0) {
            // Parsing succeeded — go straight to SplitReviewActivity
            launchSplitReview(event.getAmount(),
                    event.getMerchant(),
                    event.getMethod() != null ? event.getMethod().name() : "UPI",
                    "Shared");
        } else {
            // Parsing failed — show manual entry screen
            showManualEntry(text);
        }
    }

    // ── Direct launch path ────────────────────────────────────────────────

    private void launchSplitReview(double amount, String merchant, String method, String source) {
        Intent reviewIntent = new Intent(this, SplitReviewActivity.class);
        reviewIntent.putExtra(PaymentService.EXTRA_AMOUNT,  amount);
        reviewIntent.putExtra(PaymentService.EXTRA_MERCHANT, sanitize(merchant));
        reviewIntent.putExtra(PaymentService.EXTRA_METHOD,   sanitize(method));
        reviewIntent.putExtra(PaymentService.EXTRA_SOURCE,   "Shared");
        // inbox_entry_id = -1 (shared payments don't come from the inbox)
        startActivity(reviewIntent);
        finish();
    }

    // ── Manual entry path ─────────────────────────────────────────────────

    /**
     * Shows an inline UI when auto-parsing fails:
     *   - The raw shared text (for user reference)
     *   - An amount field pre-populated from any number found in the text
     *   - A merchant field pre-populated from parsed merchant (if any)
     *   - A "Split" button to proceed
     */
    private void showManualEntry(String rawText) {
        setContentView(R.layout.activity_share_target);

        // Display the raw message so the user can read the amount themselves
        TextView tvRawText = findViewById(R.id.tv_shared_text);
        tvRawText.setText(rawText);

        // Pre-fill amount from any number found in the text (best-effort)
        EditText etAmount  = findViewById(R.id.et_share_amount);
        EditText etMerchant = findViewById(R.id.et_share_merchant);
        Button   btnSplit  = findViewById(R.id.btn_share_split);
        Button   btnCancel = findViewById(R.id.btn_share_cancel);
        TextView tvHint    = findViewById(R.id.tv_parse_hint);

        double guessedAmount = guessAmountFromText(rawText);
        String guessedMerchant = guessedMerchant(rawText);

        if (guessedAmount > 0) {
            // Format without trailing .00
            String formatted = guessedAmount == Math.floor(guessedAmount)
                    ? String.valueOf((long) guessedAmount)
                    : String.valueOf(guessedAmount);
            etAmount.setText(formatted);
            tvHint.setText("Couldn't auto-detect this as a payment. Check the amount and tap Split.");
        } else {
            tvHint.setText("Enter the amount you want to split from this message.");
        }
        if (!guessedMerchant.isEmpty()) {
            etMerchant.setText(guessedMerchant);
        }

        // Enable Split button only when amount field is non-empty and valid
        etAmount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                double val = parseAmountSafe(s.toString());
                btnSplit.setEnabled(val > 0 && val < 1_000_000);
            }
        });
        btnSplit.setEnabled(guessedAmount > 0);

        btnSplit.setOnClickListener(v -> {
            double amount = parseAmountSafe(etAmount.getText().toString());
            if (amount <= 0 || amount >= 1_000_000) {
                etAmount.setError("Enter a valid amount (₹0.01 – ₹9,99,999)");
                return;
            }
            String merchant = etMerchant.getText().toString().trim();
            if (merchant.isEmpty()) merchant = "Payment";
            launchSplitReview(amount, merchant, "UPI", "Shared");
        });

        btnCancel.setOnClickListener(v -> finish());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Best-effort: extract the first plausible rupee amount from any text.
     * Used for the manual-entry pre-fill when PaymentParser couldn't parse
     * the message as a debit (e.g. a forwarded WhatsApp message without
     * standard bank SMS formatting).
     */
    private static double guessAmountFromText(String text) {
        // Pattern: optional Rs./₹/INR prefix, then digits with optional commas/decimal
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?:Rs\\.?|INR|₹)\\s*([0-9]{1,8}(?:,[0-9]{2,3})*(?:\\.[0-9]{1,2})?)|"
                + "([0-9]{1,8}(?:,[0-9]{2,3})*(?:\\.[0-9]{1,2})?)\\s*(?:Rs\\.?|INR|₹)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(text);
        while (m.find()) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            if (raw != null) {
                try {
                    double val = Double.parseDouble(raw.replace(",", ""));
                    if (val >= 1 && val < 1_000_000) return val;
                } catch (NumberFormatException ignored) {}
            }
        }
        // Fallback: first standalone number ≥ 1
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(
                "\\b([0-9]{2,8}(?:\\.[0-9]{1,2})?)\\b").matcher(text);
        while (m2.find()) {
            try {
                double val = Double.parseDouble(m2.group(1));
                if (val >= 1 && val < 1_000_000) return val;
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    /** Extract a short merchant-like name from the text for the merchant field pre-fill. */
    private static String guessedMerchant(String text) {
        // Look for "at <Name>" or "to <Name>" patterns (common in forwarded payment texts)
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:at|to|paid\\s+to|for)\\s+([A-Za-z][A-Za-z0-9 &'.]{1,30}?)(?=[\\s,.]|$)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            String candidate = m.group(1).trim();
            if (candidate.length() > 1) return candidate;
        }
        return "";
    }

    private static double parseAmountSafe(String s) {
        try {
            return Double.parseDouble(s.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        String clean = s.replaceAll("[\\p{Cntrl}\"\\\\]", " ").trim();
        return clean.length() > MAX_SANITISED_LENGTH
                ? clean.substring(0, MAX_SANITISED_LENGTH) : clean;
    }
}
