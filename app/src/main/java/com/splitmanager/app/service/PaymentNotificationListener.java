package com.splitmanager.app.service;

import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.splitmanager.app.model.PaymentEvent;
import com.splitmanager.app.parser.PaymentParser;

public class PaymentNotificationListener extends NotificationListenerService {

    private static final String TAG = "SplitManager.Notif";
    private static final int MAX_NOTIF_TEXT_LENGTH = 1000;

    private static final String[] PAYMENT_PACKAGES = {
        // UPI / Wallet apps
        "com.phonepe.app",
        "com.google.android.apps.nbu.paisa.user",
        "net.one97.paytm",
        "com.cred.club",
        "com.amazon.mShop.android.shopping",
        "com.mobikwik_new",
        "com.freecharge.android",
        "in.org.npci.upiapp",
        // Bank apps
        "com.axis.mobile",
        "com.csam.icici.bank.imobile",
        "com.snapwork.hdfc",
        "com.sbi.lotusintouch",
        // Messaging apps — catches RCS/RBM bank messages (e.g. Yes Bank via rbm.goog)
        "com.google.android.apps.messaging",   // Google Messages (RCS)
        "com.samsung.android.messaging",        // Samsung Messages (RCS)
        "com.android.mms",                      // Stock Android Messages
        "com.oneplus.mms",                      // OnePlus Messages
    };

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        String packageName = sbn.getPackageName();
        if (!isPaymentApp(packageName)) return;

        Bundle extras = sbn.getNotification().extras;
        if (extras == null) return;

        CharSequence titleCs   = extras.getCharSequence("android.title");
        CharSequence textCs    = extras.getCharSequence("android.text");
        CharSequence bigTextCs = extras.getCharSequence("android.bigText");

        String title   = titleCs   != null ? titleCs.toString()   : "";
        String text    = textCs    != null ? textCs.toString()    : "";
        String bigText = bigTextCs != null ? bigTextCs.toString() : "";

        // Combine and cap length to prevent memory abuse
        String fullText = (title + " " + bigText + " " + text).trim();
        if (fullText.length() > MAX_NOTIF_TEXT_LENGTH) {
            fullText = fullText.substring(0, MAX_NOTIF_TEXT_LENGTH);
        }

        // SECURITY: Do NOT log notification content — contains financial PII
        Log.d(TAG, "Payment notification received from " + getAppLabel(packageName));

        // For messaging apps carrying RCS/RBM messages (e.g. Yes Bank via rbm.goog),
        // the notification title is the bank/sender name — use it as the "sender"
        // for isBankSender check when the package is a messaging app.
        boolean isMessagingApp = packageName.contains("messaging") || packageName.contains("mms");
        if (isMessagingApp) {
            // Check title as sender (bank name in RCS appears as conversation title)
            boolean titleIsBank = PaymentParser.isBankSender(title.replaceAll("\\s+", ""));
            if (!titleIsBank && !PaymentParser.isPaymentMessage(fullText)) return;
        }

        if (!PaymentParser.isPaymentMessage(fullText)) return;

        PaymentEvent event = PaymentParser.parse(fullText, packageName);
        if (event == null) return;

        // Validate parsed amount
        if (event.getAmount() <= 0 || event.getAmount() >= 1_000_000) {
            Log.w(TAG, "Amount out of valid range, ignoring");
            return;
        }

        event.setSource("NOTIF:" + getAppLabel(packageName));

        // Explicit component intent — not raw message passed
        Intent serviceIntent = new Intent(this, PaymentService.class);
        serviceIntent.setAction(PaymentService.ACTION_NEW_PAYMENT);
        serviceIntent.putExtra(PaymentService.EXTRA_AMOUNT,   event.getAmount());
        serviceIntent.putExtra(PaymentService.EXTRA_MERCHANT, event.getMerchant());
        serviceIntent.putExtra(PaymentService.EXTRA_METHOD,   event.getMethod().name());
        serviceIntent.putExtra(PaymentService.EXTRA_SOURCE,   event.getSource());
        serviceIntent.putExtra(PaymentService.EXTRA_SESSION_TOKEN, PaymentService.getCurrentToken());
        try {
            startForegroundService(serviceIntent);
        } catch (Exception e) {
            // Android 12+: startForegroundService can throw if app is background-restricted
            Log.e(TAG, "Could not start PaymentService from notification listener");
        }
    }

    private boolean isPaymentApp(String packageName) {
        if (packageName == null) return false;
        for (String pkg : PAYMENT_PACKAGES) {
            if (pkg.equals(packageName)) return true;
        }
        return false;
    }

    private String getAppLabel(String packageName) {
        switch (packageName) {
            case "com.phonepe.app":                           return "PhonePe";
            case "com.google.android.apps.nbu.paisa.user":   return "GPay";
            case "net.one97.paytm":                          return "Paytm";
            case "com.cred.club":                            return "CRED";
            case "com.amazon.mShop.android.shopping":        return "AmazonPay";
            case "com.google.android.apps.messaging":         return "GoogleMessages";
            case "com.samsung.android.messaging":              return "SamsungMessages";
            case "com.android.mms":                            return "Messages";
            case "com.oneplus.mms":                            return "OnePlusMessages";
            default:                                           return "PaymentApp";
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {}
}
