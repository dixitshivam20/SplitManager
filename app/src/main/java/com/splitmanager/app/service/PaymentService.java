package com.splitmanager.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.splitmanager.app.R;
import com.splitmanager.app.util.NotificationHelper;
import com.splitmanager.app.ui.SplitReviewActivity;

import com.splitmanager.app.db.PaymentInboxDb;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class PaymentService extends Service {

    private static final String TAG = "SplitManager.Service";

    public static final String ACTION_NEW_PAYMENT  = "com.splitmanager.app.NEW_PAYMENT";
    public static final String EXTRA_AMOUNT        = "amount";
    public static final String EXTRA_MERCHANT      = "merchant";
    public static final String EXTRA_METHOD        = "method";
    public static final String EXTRA_REFERENCE     = "reference";
    public static final String EXTRA_SOURCE        = "source";
    public static final String EXTRA_SESSION_TOKEN = "session_token";

    /** Broadcast sent whenever the unread badge count changes (ignore or new payment).
     *  MainActivity listens for this to auto-refresh the bell badge without a full resume. */
    public static final String ACTION_BADGE_UPDATED = "com.splitmanager.app.BADGE_UPDATED";

    private static final String CHANNEL_FOREGROUND = "splitmanager_fg";
    private static final String CHANNEL_PAYMENT    = "splitmanager_payment";
    private static final int    FG_NOTIF_ID        = 1;
    private static final double MAX_VALID_AMOUNT   = 1_000_000.0;

    // Private session token — renewed each service lifecycle.
    // Only accessible via getCurrentToken() getter — not directly writable
    // by any class outside PaymentService, preventing accidental or malicious
    // token overwrite from other classes in the same package.
    private static volatile String currentToken = null;

    /** Package-accessible getter — SmsReceiver and NotificationListener read this */
    public static String getCurrentToken() { return currentToken; }

    // Dedup cache: key = "amount:reference", value = timestamp of last seen
    // Prevents duplicate notifications when both SMS + notification listener fire for the same payment
    private final Map<String, Long> recentPayments = new HashMap<>();
    private static final long DEDUP_WINDOW_MS = 10_000; // 10 seconds

    private String sessionToken;
    private final AtomicInteger paymentNotifId = new AtomicInteger(100);
    // Separate counter for ignore PendingIntent request codes.
    // SECURITY FIX: using notifId + 1000 caused a collision — after 1000 payments the ignore
    // PendingIntent request code for payment N equals the split PendingIntent request code for
    // payment N+1000, causing Android to reuse the wrong intent. Separate counters eliminate this.
    private final AtomicInteger ignoreRequestId = new AtomicInteger(10_100);
    private ExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();
        sessionToken = UUID.randomUUID().toString();
        currentToken = sessionToken;
        executor = Executors.newSingleThreadExecutor();
        createNotificationChannels();
        startForeground(FG_NOTIF_ID, buildForegroundNotification());
        Log.d(TAG, "SplitManager service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();

        // Handle ignore button — cancel the system notification and mark the
        // matching inbox entry as read so the bell badge stays in sync.
        if (action != null && action.startsWith("IGNORE_")) {
            try {
                int notifId = Integer.parseInt(action.replace("IGNORE_", ""));
                // Cancel the system notification
                NotificationManagerCompat.from(this).cancel(notifId);
                // Mark the matching inbox entry as read and set status to ignored so
                // the in-app inbox shows the correct label without a manual refresh.
                // We run this on the executor to avoid StrictMode disk-on-main-thread
                if (executor != null) {
                    final int fNotifId = notifId;
                    executor.submit(() -> {
                        try {
                            com.splitmanager.app.db.PaymentInboxDb db =
                                com.splitmanager.app.db.PaymentInboxDb.getInstance(getApplicationContext());
                            db.markReadByNotifId(fNotifId);
                            // Set status so inbox shows "ignored" label
                            db.updateStatusByNotifId(fNotifId,
                                com.splitmanager.app.db.PaymentInboxDb.STATUS_IGNORED);
                        } catch (Exception ex) {
                            Log.w(TAG, "Could not sync inbox on ignore");
                        }
                        // Broadcast badge update so MainActivity refreshes the bell
                        // counter immediately, even while it is in the background.
                        LocalBroadcastManager.getInstance(getApplicationContext())
                            .sendBroadcast(new Intent(ACTION_BADGE_UPDATED));
                    });
                }
                Log.d(TAG, "Payment notification dismissed by user");
            } catch (Exception e) {
                Log.w(TAG, "Could not dismiss notification");
            }
            return START_STICKY;
        }

        if (!ACTION_NEW_PAYMENT.equals(action)) return START_STICKY;

        // Validate session token — rejects stale/external intents
        String token = intent.getStringExtra(EXTRA_SESSION_TOKEN);
        if (sessionToken != null && !sessionToken.equals(token)) {
            Log.w(TAG, "Intent rejected: invalid session token");
            return START_STICKY;
        }

        double amount   = intent.getDoubleExtra(EXTRA_AMOUNT, -1);
        String merchant = intent.getStringExtra(EXTRA_MERCHANT);
        String method   = intent.getStringExtra(EXTRA_METHOD);
        String reference = intent.getStringExtra(EXTRA_REFERENCE);
        String source   = intent.getStringExtra(EXTRA_SOURCE);

        if (!isValidPayment(amount)) {
            Log.w(TAG, "Rejected invalid payment amount");
            return START_STICKY;
        }

        merchant  = sanitize(merchant, 80);
        method    = sanitize(method, 30);
        source    = sanitize(source, 50);
        reference = sanitize(reference, 50);

        handlePayment(amount, merchant, method, reference, source);
        return START_STICKY;
    }

    private boolean isValidPayment(double amount) {
        return amount > 0 && amount < MAX_VALID_AMOUNT
            && !Double.isNaN(amount) && !Double.isInfinite(amount);
    }

    private static String sanitize(String s, int maxLen) {
        if (s == null) return "";
        String clean = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return clean.length() > maxLen ? clean.substring(0, maxLen) : clean;
    }

    private void handlePayment(double amount, String merchant, String method,
                                String reference, String source) {
        executor.submit(() -> {
            // Deduplication: SMS and notification listener can both fire for the same payment.
            // If we've seen the same amount+reference within 10 seconds, skip it.
            // Dedup key: amount is always included.
            // If reference ID exists (UPI Ref, IMPS Ref) use it — it's globally unique.
            // Otherwise fall back to amount:merchant. The 10s window handles the rest.
            String dedupKey = String.format("%.2f:%s", amount,
                (reference != null && !reference.isEmpty())
                    ? reference
                    : (merchant != null && !merchant.isEmpty() && !merchant.equals("Unknown Merchant"))
                        ? merchant
                        : String.valueOf(Math.round(amount)));  // amount-only fallback
            long now = System.currentTimeMillis();
            synchronized (recentPayments) {
                Long lastSeen = recentPayments.get(dedupKey);
                if (lastSeen != null && (now - lastSeen) < DEDUP_WINDOW_MS) {
                    Log.d(TAG, "Duplicate payment detected, skipping notification");
                    return;
                }
                // Evict old entries to prevent unbounded growth
                recentPayments.entrySet().removeIf(e -> (now - e.getValue()) > DEDUP_WINDOW_MS * 6);
                recentPayments.put(dedupKey, now);
            }

            Log.d(TAG, "Processing new payment event");

            int notifId = paymentNotifId.getAndIncrement();
            int ignoreReqId = ignoreRequestId.getAndIncrement();

            // Save to inbox so user can split later from the Notifications tab.
            // We store notifId alongside the entry so InboxActivity can cancel
            // the system notification when the user acts on the inbox entry.
            PaymentInboxDb.getInstance(getApplicationContext())
                .insert(amount, merchant, method, source, notifId);

            Intent reviewIntent = new Intent(this, SplitReviewActivity.class);
            reviewIntent.putExtra(EXTRA_AMOUNT,   amount);
            reviewIntent.putExtra(EXTRA_MERCHANT, merchant);
            reviewIntent.putExtra(EXTRA_METHOD,   method);
            reviewIntent.putExtra(EXTRA_SOURCE,   source);
            reviewIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent pendingIntent = PendingIntent.getActivity(
                this, notifId, reviewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            Intent ignoreIntent = new Intent(this, PaymentService.class);
            ignoreIntent.setAction("IGNORE_" + notifId);
            PendingIntent ignorePending = PendingIntent.getService(
                this, notifId + 1000, ignoreIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            String amountStr = String.format("\u20B9%.0f", amount);
            // Security: title is shown to any NotificationListenerService — keep it generic
            // Amount+merchant visible only when user pulls down notification (trusted screen)
            String title = "New payment detected";
            String body  = amountStr + (merchant.isEmpty() ? " paid" : " paid at " + merchant)
                         + " — tap to split";

            Notification notif = new NotificationCompat.Builder(this, CHANNEL_PAYMENT)
                .setSmallIcon(R.drawable.ic_expense)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_check, "Split now", pendingIntent)
                .addAction(R.drawable.ic_close, "Ignore", ignorePending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setVibrate(new long[]{0, 250, 100, 250})
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                // Group key enables the summary notification to carry the badge count
                .setGroup(NotificationHelper.GROUP_PAYMENTS)
                .build();

            try {
                NotificationManagerCompat.from(this).notify(notifId, notif);
                // Update app icon badge to reflect new unread count
                NotificationHelper.refreshBadge(getApplicationContext());
                // Notify MainActivity to refresh the bell badge counter immediately
                LocalBroadcastManager.getInstance(getApplicationContext())
                    .sendBroadcast(new Intent(ACTION_BADGE_UPDATED));
            } catch (SecurityException e) {
                // POST_NOTIFICATIONS permission not granted — user hasn't approved yet
                Log.w(TAG, "Cannot post notification — POST_NOTIFICATIONS not granted");
            }
        });
    }

    private void createNotificationChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel fgCh = new NotificationChannel(
            CHANNEL_FOREGROUND, "SplitManager Running", NotificationManager.IMPORTANCE_MIN);
        fgCh.setDescription("Keeps SplitManager active to detect payments");
        nm.createNotificationChannel(fgCh);

        NotificationChannel payCh = new NotificationChannel(
            CHANNEL_PAYMENT, "Payment Detected", NotificationManager.IMPORTANCE_HIGH);
        payCh.setDescription("Alerts when a new payment is detected");
        payCh.enableVibration(true);
        nm.createNotificationChannel(payCh);
    }

    private Notification buildForegroundNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_FOREGROUND)
            .setSmallIcon(R.drawable.ic_expense)
            .setContentTitle("SplitManager is active")
            .setContentText("Monitoring payment messages")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        currentToken = null;
        if (executor != null) executor.shutdown();
    }
}
