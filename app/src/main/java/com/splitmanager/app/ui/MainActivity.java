package com.splitmanager.app.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.splitmanager.app.BuildConfig;
import com.splitmanager.app.util.NotificationHelper;
import com.splitmanager.app.db.PaymentInboxDb;
import com.splitmanager.app.R;
import com.splitmanager.app.databinding.ActivityMainBinding;
import com.splitmanager.app.service.PaymentService;
import com.splitmanager.app.service.PaymentNotificationListener;
import com.splitmanager.app.util.SecurePrefsHelper;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SplitManager.Main";
    private ActivityMainBinding binding;

    /**
     * Receives ACTION_BADGE_UPDATED broadcasts from PaymentService so the bell
     * badge refreshes immediately when the user taps Split / Ignore on a system
     * notification — without needing to navigate away and back.
     */
    private final BroadcastReceiver badgeUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            updateInboxBadge();
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), results -> {
            updatePermissionStatus();
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Check API key safely — SecurePrefsHelper can fail on first launch
        boolean hasKey = false;
        try {
            hasKey = SecurePrefsHelper.hasApiKey(this);
        } catch (Exception e) {
            Log.e(TAG, "SecurePrefs not ready on first launch — this is normal", e);
        }

        if (!hasKey) {
            startActivity(new Intent(this, SetupActivity.class));
        }

        setupUI();
        updatePermissionStatus();
        startBotService();
    }

    private void setupUI() {
        binding.btnGrantSms.setOnClickListener(v -> requestSmsPermissions());
        binding.btnInbox.setOnClickListener(v ->
            startActivity(new Intent(this, InboxActivity.class)));
        binding.btnHistory.setOnClickListener(v ->
            startActivity(new Intent(this, HistoryActivity.class)));
        binding.btnGrantNotification.setOnClickListener(v -> openNotificationListenerSettings());
        binding.btnOpenSettings.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
        binding.btnConfigureSplitwise.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));

        // Test button only in debug builds
        binding.btnTestPayment.setVisibility(BuildConfig.DEBUG ? View.VISIBLE : View.GONE);
        binding.btnTestPayment.setOnClickListener(v -> {
            Intent intent = new Intent(this, PaymentService.class);
            intent.setAction(PaymentService.ACTION_NEW_PAYMENT);
            intent.putExtra(PaymentService.EXTRA_AMOUNT, 850.0);
            intent.putExtra(PaymentService.EXTRA_MERCHANT, "Swiggy");
            intent.putExtra(PaymentService.EXTRA_METHOD, "UPI");
            intent.putExtra(PaymentService.EXTRA_SOURCE, "Test");
            intent.putExtra(PaymentService.EXTRA_SESSION_TOKEN, PaymentService.getCurrentToken());
            try {
                startForegroundService(intent);
                Toast.makeText(this, "Test payment triggered!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Start service first", Toast.LENGTH_SHORT).show();
            }
        });

        // Update API status label safely
        boolean hasKey = false;
        try { hasKey = SecurePrefsHelper.hasApiKey(this); } catch (Exception ignored) {}
        if (hasKey) {
            binding.tvApiStatus.setText("Splitwise connected \u2713");
            binding.tvApiStatus.setTextColor(0xFF2E7D32);
        } else {
            binding.tvApiStatus.setText("Splitwise not configured");
            binding.tvApiStatus.setTextColor(0xFFF57C00);
        }
    }

    private void requestSmsPermissions() {
        List<String> needed = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
                != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECEIVE_SMS);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.READ_SMS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS);

        if (!needed.isEmpty()) {
            permissionLauncher.launch(needed.toArray(new String[0]));
        } else {
            Toast.makeText(this, "SMS permissions already granted!", Toast.LENGTH_SHORT).show();
        }
    }

    private void openNotificationListenerSettings() {
        new AlertDialog.Builder(this)
            .setTitle("Enable Notification Access")
            .setMessage("In the next screen, find 'SplitManager' and toggle it ON.\n\nThis lets the app catch payment notifications from PhonePe, GPay, Paytm etc.")
            .setPositiveButton("Open Settings", (d, w) ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
            .setNegativeButton("Later", null)
            .show();
    }

    private boolean isNotificationListenerEnabled() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            if (flat == null) return false;
            ComponentName cn = new ComponentName(this, PaymentNotificationListener.class);
            return flat.contains(cn.flattenToString());
        } catch (Exception e) {
            return false;
        }
    }

    private void updatePermissionStatus() {
        boolean hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
            == PackageManager.PERMISSION_GRANTED;
        boolean hasNotif = isNotificationListenerEnabled();

        binding.ivSmsStatus.setImageResource(hasSms ? R.drawable.ic_check : R.drawable.ic_close);
        binding.tvSmsStatus.setText(hasSms ? "SMS permission granted" : "SMS permission needed");
        binding.btnGrantSms.setVisibility(hasSms ? View.GONE : View.VISIBLE);

        binding.ivNotifStatus.setImageResource(hasNotif ? R.drawable.ic_check : R.drawable.ic_close);
        binding.tvNotifStatus.setText(hasNotif ? "Notification access granted" : "Notification access needed");
        binding.btnGrantNotification.setVisibility(hasNotif ? View.GONE : View.VISIBLE);

        boolean hasKey = false;
        try { hasKey = SecurePrefsHelper.hasApiKey(this); } catch (Exception ignored) {}

        boolean allGood = hasSms && hasNotif && hasKey;
        binding.tvBotStatus.setText(allGood ? "Bot is active and listening" : "Setup incomplete");
        binding.ivBotStatus.setImageResource(allGood ? R.drawable.ic_check : R.drawable.ic_warning);
    }

    private void startBotService() {
        try {
            startForegroundService(new Intent(this, PaymentService.class));
        } catch (Exception e) {
            // Service start can fail if POST_NOTIFICATIONS not yet granted on Android 13+
            Log.e(TAG, "Could not start PaymentService — will retry on next open", e);
        }
    }

    /** Lightweight refresh — only updates status indicators, never re-registers listeners */
    private void refreshStatus() {
        updatePermissionStatus();
        // Refresh API status label only
        boolean hasKey = false;
        try { hasKey = SecurePrefsHelper.hasApiKey(this); } catch (Exception ignored) {}
        if (hasKey) {
            binding.tvApiStatus.setText("Splitwise connected ✓");
            binding.tvApiStatus.setTextColor(0xFF2E7D32);
        } else {
            binding.tvApiStatus.setText("Splitwise not configured");
            binding.tvApiStatus.setTextColor(0xFFF57C00);
        }
        // Update unread badge on inbox card
        updateInboxBadge();
    }

    private void updateInboxBadge() {
        new Thread(() -> {
            int count = PaymentInboxDb.getInstance(this).unreadCount();
            // Sync the system-level badge (app icon) with the same count
            NotificationHelper.refreshBadge(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                // Update the in-app bell badge on the home screen header
                if (count > 0) {
                    binding.tvInboxBadge.setText(count > 99 ? "99+" : String.valueOf(count));
                    binding.tvInboxBadge.setVisibility(android.view.View.VISIBLE);
                } else {
                    binding.tvInboxBadge.setVisibility(android.view.View.GONE);
                }
            });
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus(); // lightweight — does NOT re-register click listeners
        // Register for live badge updates while the activity is visible.
        // Paired with unregister in onPause to avoid leaks.
        LocalBroadcastManager.getInstance(this).registerReceiver(
            badgeUpdateReceiver,
            new IntentFilter(com.splitmanager.app.service.PaymentService.ACTION_BADGE_UPDATED));
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Unregister so we don't receive broadcasts when the activity is not visible.
        LocalBroadcastManager.getInstance(this).unregisterReceiver(badgeUpdateReceiver);
    }
}
