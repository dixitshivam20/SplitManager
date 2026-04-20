package com.splitmanager.app.ui;

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
import com.splitmanager.app.db.SplitHistoryDb;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HistoryActivity extends AppCompatActivity {

    private RecyclerView rvHistory;
    private TextView tvEmpty;
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        executor = Executors.newSingleThreadExecutor();

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Split History");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        rvHistory = findViewById(R.id.rv_history);
        tvEmpty   = findViewById(R.id.tv_empty_history);
        rvHistory.setLayoutManager(new LinearLayoutManager(this));

        // Clear all button
        findViewById(R.id.btn_clear_history).setOnClickListener(v ->
            new AlertDialog.Builder(this)
                .setTitle("Clear history?")
                .setMessage("This will delete all local split history. Splitwise records are not affected.")
                .setPositiveButton("Clear", (d, w) -> {
                    executor.submit(() -> {
                        SplitHistoryDb.getInstance(this).deleteAll();
                        runOnUiThread(this::loadHistory);
                    });
                })
                .setNegativeButton("Cancel", null)
                .show()
        );

        loadHistory();
    }

    private void loadHistory() {
        executor.submit(() -> {
            List<SplitHistoryDb.SplitRecord> records =
                SplitHistoryDb.getInstance(this).getAll();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (records.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    rvHistory.setVisibility(View.GONE);
                } else {
                    tvEmpty.setVisibility(View.GONE);
                    rvHistory.setVisibility(View.VISIBLE);
                    rvHistory.setAdapter(new HistoryAdapter(records));
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

    private static class HistoryAdapter
            extends RecyclerView.Adapter<HistoryAdapter.VH> {

        private final List<SplitHistoryDb.SplitRecord> items;
        private static final SimpleDateFormat SDF =
            new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault());

        HistoryAdapter(List<SplitHistoryDb.SplitRecord> items) { this.items = items; }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            SplitHistoryDb.SplitRecord r = items.get(pos);
            h.tvAmount.setText(String.format("\u20B9%.0f", r.amount));
            h.tvMerchant.setText(r.merchant != null && !r.merchant.isEmpty()
                ? r.merchant : "Unknown merchant");
            h.tvGroup.setText(r.groupName + " · " + r.memberCount + " people · " + r.splitType);
            h.tvDate.setText(SDF.format(new Date(r.timestamp)));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvAmount, tvMerchant, tvGroup, tvDate;
            VH(View v) {
                super(v);
                tvAmount   = v.findViewById(R.id.tv_hist_amount);
                tvMerchant = v.findViewById(R.id.tv_hist_merchant);
                tvGroup    = v.findViewById(R.id.tv_hist_group);
                tvDate     = v.findViewById(R.id.tv_hist_date);
            }
        }
    }
}
