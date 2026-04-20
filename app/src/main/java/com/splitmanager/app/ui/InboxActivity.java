package com.splitmanager.app.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.splitmanager.app.R;
import com.splitmanager.app.db.PaymentInboxDb;
import com.splitmanager.app.service.PaymentService;
import com.splitmanager.app.util.NotificationHelper;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class InboxActivity extends AppCompatActivity {

    private RecyclerView rvInbox;
    private TextView tvEmpty;
    private ExecutorService executor;
    private InboxAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inbox);
        // FLAG_SECURE: prevents screenshots and screen recording of this screen.
        // The inbox displays payment amounts and merchant names — PII that should
        // not appear in recent-apps thumbnails or be captured by screen recorders.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                             WindowManager.LayoutParams.FLAG_SECURE);
        executor = Executors.newSingleThreadExecutor();

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Payment Inbox");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        rvInbox = findViewById(R.id.rv_inbox);
        tvEmpty = findViewById(R.id.tv_empty_inbox);
        rvInbox.setLayoutManager(new LinearLayoutManager(this));

        // Mark all read — cancels no system notifications (user may still want to act on them),
        // but clears the bell badge inside the app.
        findViewById(R.id.btn_mark_all_read).setOnClickListener(v -> {
            executor.submit(() -> {
                PaymentInboxDb.getInstance(this).markAllRead();
                // Refresh badge — all are now read so badge count drops to 0
                NotificationHelper.refreshBadge(getApplicationContext());
                // Notify MainActivity to refresh bell badge immediately
                LocalBroadcastManager.getInstance(getApplicationContext())
                    .sendBroadcast(new Intent(PaymentService.ACTION_BADGE_UPDATED));
                runOnUiThread(this::loadInbox);
            });
        });

        // Clear all — cancels ALL system notifications and removes all inbox entries
        findViewById(R.id.btn_clear_inbox).setOnClickListener(v ->
            new AlertDialog.Builder(this)
                .setTitle("Clear inbox?")
                .setMessage("This removes all payment notifications from the inbox and clears them from your notification drawer.")
                .setPositiveButton("Clear", (d, w) -> {
                    executor.submit(() -> {
                        // Cancel all system notifications + clear DB + refresh badge
                        NotificationHelper.cancelAllNotificationsAndClearInbox(getApplicationContext());
                        // Notify MainActivity to refresh bell badge immediately
                        LocalBroadcastManager.getInstance(getApplicationContext())
                            .sendBroadcast(new Intent(PaymentService.ACTION_BADGE_UPDATED));
                        runOnUiThread(this::loadInbox);
                    });
                })
                .setNegativeButton("Cancel", null)
                .show()
        );

        loadInbox();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadInbox();
    }

    private void loadInbox() {
        executor.submit(() -> {
            List<PaymentInboxDb.InboxEntry> entries =
                PaymentInboxDb.getInstance(this).getAll();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (entries.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    rvInbox.setVisibility(View.GONE);
                    findViewById(R.id.btn_mark_all_read).setEnabled(false);
                } else {
                    tvEmpty.setVisibility(View.GONE);
                    rvInbox.setVisibility(View.VISIBLE);
                    findViewById(R.id.btn_mark_all_read).setEnabled(true);
                    adapter = new InboxAdapter(entries);
                    rvInbox.setAdapter(adapter);
                }
            });
        });
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) { executor.shutdown(); executor = null; }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class InboxAdapter extends RecyclerView.Adapter<InboxAdapter.VH> {

        private final List<PaymentInboxDb.InboxEntry> items;
        private static final SimpleDateFormat SDF =
            new SimpleDateFormat("d MMM, h:mm a", Locale.getDefault());

        InboxAdapter(List<PaymentInboxDb.InboxEntry> items) { this.items = items; }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_inbox, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            PaymentInboxDb.InboxEntry e = items.get(pos);

            h.tvAmount.setText(String.format("\u20B9%.0f", e.amount));
            h.tvMerchant.setText(e.merchant != null && !e.merchant.isEmpty()
                ? e.merchant : "Unknown merchant");
            h.tvMeta.setText(
                (e.method != null && !e.method.isEmpty()
                    ? e.method.replace("_", " ") + " · " : "") +
                SDF.format(new Date(e.timestamp)));

            // Status pill — show "Added" or "Ignored" after merchant name, hide for pending
            if (PaymentInboxDb.STATUS_ADDED.equals(e.status)) {
                h.tvStatus.setText("Added");
                h.tvStatus.setBackgroundResource(R.drawable.bg_badge);
                h.tvStatus.getBackground().setTint(0xFF2E7D32); // green
                h.tvStatus.setVisibility(android.view.View.VISIBLE);
            } else if (PaymentInboxDb.STATUS_IGNORED.equals(e.status)) {
                h.tvStatus.setText("Ignored");
                h.tvStatus.setBackgroundResource(R.drawable.bg_badge);
                h.tvStatus.getBackground().setTint(0xFF9E9E9E); // grey
                h.tvStatus.setVisibility(android.view.View.VISIBLE);
            } else {
                // Pending — no pill shown
                h.tvStatus.setVisibility(android.view.View.GONE);
            }

            if (e.read) {
                // Read — faded, normal weight
                h.tvAmount.setAlpha(0.5f);
                h.tvMerchant.setAlpha(0.5f);
                h.tvMeta.setAlpha(0.4f);
                h.tvMerchant.setTypeface(null, Typeface.NORMAL);
                h.tvAmount.setTypeface(null, Typeface.NORMAL);
                h.tvUnreadDot.setVisibility(View.INVISIBLE);
                h.itemView.setBackgroundColor(0xFFFAFAFA);
            } else {
                // Unread — full opacity, bold, green dot
                h.tvAmount.setAlpha(1f);
                h.tvMerchant.setAlpha(1f);
                h.tvMeta.setAlpha(0.8f);
                h.tvMerchant.setTypeface(null, Typeface.BOLD);
                h.tvAmount.setTypeface(null, Typeface.BOLD);
                h.tvUnreadDot.setVisibility(View.VISIBLE);
                h.itemView.setBackgroundColor(0xFFFFFFFF);
            }

            // Tap → cancel system notification + mark read + open split screen
            h.itemView.setOnClickListener(v -> {
                // Cancel the system notification for this entry so the drawer stays clean
                final int notifId = e.notifId;
                final long inboxId = e.id;
                executor.submit(() ->
                    NotificationHelper.cancelNotificationAndMarkRead(
                        getApplicationContext(), notifId, inboxId));

                // Optimistically update the UI immediately (don't wait for DB)
                e.read = true;
                notifyItemChanged(pos);

                // Open SplitReviewActivity with this payment
                // Pass the inbox entry ID so SplitReviewActivity can delete it on success
                Intent intent = new Intent(InboxActivity.this, SplitReviewActivity.class);
                intent.putExtra(PaymentService.EXTRA_AMOUNT,   e.amount);
                intent.putExtra(PaymentService.EXTRA_MERCHANT, e.merchant);
                intent.putExtra(PaymentService.EXTRA_METHOD,   e.method);
                intent.putExtra(PaymentService.EXTRA_SOURCE,   e.source);
                // Pass inbox entry ID so SplitReviewActivity can delete it from inbox on success
                intent.putExtra("inbox_entry_id", e.id);
                // Pass notifId so SplitReviewActivity doesn't need to re-query it
                intent.putExtra("inbox_notif_id", e.notifId);
                startActivity(intent);
            });

            // Long press → cancel system notification + delete from inbox entirely
            h.itemView.setOnLongClickListener(v -> {
                new AlertDialog.Builder(InboxActivity.this)
                    .setTitle("Remove from inbox?")
                    .setMessage("This removes it from your inbox and clears the notification from your drawer.")
                    .setPositiveButton("Remove", (d, w) -> {
                        final int notifId = e.notifId;
                        final long inboxId = e.id;
                        // Cancel notification + delete from DB + refresh badge
                        executor.submit(() ->
                            NotificationHelper.cancelNotificationAndDelete(
                                getApplicationContext(), notifId, inboxId));
                        items.remove(pos);
                        notifyItemRemoved(pos);
                        if (items.isEmpty()) {
                            tvEmpty.setVisibility(View.VISIBLE);
                            rvInbox.setVisibility(View.GONE);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
                return true;
            });
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvAmount, tvMerchant, tvMeta, tvUnreadDot, tvStatus;
            VH(View v) {
                super(v);
                tvAmount    = v.findViewById(R.id.tv_inbox_amount);
                tvMerchant  = v.findViewById(R.id.tv_inbox_merchant);
                tvMeta      = v.findViewById(R.id.tv_inbox_meta);
                tvUnreadDot = v.findViewById(R.id.tv_unread_dot);
                tvStatus    = v.findViewById(R.id.tv_inbox_status);
            }
        }
    }
}
