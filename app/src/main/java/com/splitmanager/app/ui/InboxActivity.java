package com.splitmanager.app.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.splitmanager.app.R;
import com.splitmanager.app.db.PaymentInboxDb;
import com.splitmanager.app.service.PaymentService;

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
        executor = Executors.newSingleThreadExecutor();

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Payment Inbox");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        rvInbox = findViewById(R.id.rv_inbox);
        tvEmpty = findViewById(R.id.tv_empty_inbox);
        rvInbox.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.btn_mark_all_read).setOnClickListener(v -> {
            executor.submit(() -> {
                PaymentInboxDb.getInstance(this).markAllRead();
                runOnUiThread(this::loadInbox);
            });
        });

        findViewById(R.id.btn_clear_inbox).setOnClickListener(v ->
            new AlertDialog.Builder(this)
                .setTitle("Clear inbox?")
                .setMessage("This removes all payment notifications from the inbox.")
                .setPositiveButton("Clear", (d, w) -> {
                    executor.submit(() -> {
                        PaymentInboxDb.getInstance(this).deleteAll();
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
        if (executor != null) executor.shutdown();
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

            // Tap → mark read + open split screen
            h.itemView.setOnClickListener(v -> {
                // Mark read in DB
                executor.submit(() -> {
                    PaymentInboxDb.getInstance(InboxActivity.this).markRead(e.id);
                });
                e.read = true;
                notifyItemChanged(pos);

                // Open SplitReviewActivity with this payment
                Intent intent = new Intent(InboxActivity.this, SplitReviewActivity.class);
                intent.putExtra(PaymentService.EXTRA_AMOUNT,   e.amount);
                intent.putExtra(PaymentService.EXTRA_MERCHANT, e.merchant);
                intent.putExtra(PaymentService.EXTRA_METHOD,   e.method);
                intent.putExtra(PaymentService.EXTRA_SOURCE,   e.source);
                startActivity(intent);
            });

            // Long press → delete
            h.itemView.setOnLongClickListener(v -> {
                new AlertDialog.Builder(InboxActivity.this)
                    .setTitle("Remove from inbox?")
                    .setMessage("This only removes it from your inbox, not from Splitwise.")
                    .setPositiveButton("Remove", (d, w) -> {
                        executor.submit(() ->
                            PaymentInboxDb.getInstance(InboxActivity.this).delete(e.id));
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
            TextView tvAmount, tvMerchant, tvMeta, tvUnreadDot;
            VH(View v) {
                super(v);
                tvAmount    = v.findViewById(R.id.tv_inbox_amount);
                tvMerchant  = v.findViewById(R.id.tv_inbox_merchant);
                tvMeta      = v.findViewById(R.id.tv_inbox_meta);
                tvUnreadDot = v.findViewById(R.id.tv_unread_dot);
            }
        }
    }
}
