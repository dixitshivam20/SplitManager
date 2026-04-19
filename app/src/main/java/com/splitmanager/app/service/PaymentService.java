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

import com.splitmanager.app.R;
import com.splitmanager.app.ui.SplitReviewActivity;

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

    private static final String CHANNEL_FOREGROUND = "splitmanager_fg";
    private static final String CHANNEL_PAYMENT    = "splitmanager_payment";
    private static final int    FG_NOTIF_ID        = 1;
    private static final double MAX_VALID_AMOUNT   = 1_000_000.0;

    // Package-private session token — renewed each service lifecycle
    // Internal components (SmsReceiver, NotificationListener) read this
    // Package-private — internal components in same package access directly
    // UI package accesses via getCurrentToken() getter
    static volatile String currentToken = null;

    /** Public getter so UI layer can read token without exposing the field */
    public static String getCurrentToken() { return currentToken; }

    private String sessionToken;
    private final AtomicInteger paymentNotifId = new AtomicInteger(100);
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

        // Handle ignore button — cancel the specific notification and return
        if (action != null && action.startsWith("IGNORE_")) {
            try {
                int notifId = Integer.parseInt(action.replace("IGNORE_", ""));
                NotificationManagerCompat.from(this).cancel(notifId);
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
            Log.d(TAG, "Processing new payment event");

            int notifId = paymentNotifId.getAndIncrement();

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
                .build();

            try {
                NotificationManagerCompat.from(this).notify(notifId, notif);
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
