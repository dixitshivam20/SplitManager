package com.splitmanager.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import com.splitmanager.app.model.PaymentEvent;
import com.splitmanager.app.parser.PaymentParser;

public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SplitManager.SMS";
    private static final String SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";
    private static final int MAX_SMS_BODY_LENGTH = 2000;

    @Override
    @SuppressWarnings("deprecation") // SmsMessage.createFromPdu(byte[], String) is the correct API
    public void onReceive(Context context, Intent intent) {
        if (!SMS_RECEIVED.equals(intent.getAction())) return;

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        String format = bundle.getString("format");
        if (pdus == null || pdus.length == 0) return;

        if (pdus.length > 10) {
            Log.w(TAG, "Rejecting SMS with unusual PDU count: " + pdus.length);
            return;
        }

        StringBuilder fullBody = new StringBuilder();
        String sender = null;

        for (Object pdu : pdus) {
            try {
                SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu, format);
                if (sms != null) {
                    if (sender == null) sender = sms.getOriginatingAddress();
                    fullBody.append(sms.getMessageBody());
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse PDU — skipping");
                return;
            }
        }

        String body = fullBody.toString().trim();

        if (body.length() > MAX_SMS_BODY_LENGTH) {
            Log.w(TAG, "Oversized SMS, truncating");
            body = body.substring(0, MAX_SMS_BODY_LENGTH);
        }

        // SECURITY: never log body or sender — contains financial PII
        Log.d(TAG, "SMS received, parsing...");

        // Require: known bank sender AND valid payment message format
        // This prevents random SMS from triggering the payment flow
        if (!PaymentParser.isBankSender(sender) || !PaymentParser.isPaymentMessage(body)) return;

        PaymentEvent event = PaymentParser.parse(body, sender);
        if (event == null) return;

        if (event.getAmount() <= 0 || event.getAmount() >= 1_000_000) {
            Log.w(TAG, "Amount out of valid range, ignoring");
            return;
        }

        event.setSource("SMS");
        Log.d(TAG, "Payment parsed successfully");

        Intent serviceIntent = new Intent(context, PaymentService.class);
        serviceIntent.setAction(PaymentService.ACTION_NEW_PAYMENT);
        serviceIntent.putExtra(PaymentService.EXTRA_AMOUNT,        event.getAmount());
        serviceIntent.putExtra(PaymentService.EXTRA_MERCHANT,      event.getMerchant());
        serviceIntent.putExtra(PaymentService.EXTRA_METHOD,        event.getMethod().name());
        serviceIntent.putExtra(PaymentService.EXTRA_REFERENCE,     event.getReferenceId());
        serviceIntent.putExtra(PaymentService.EXTRA_SOURCE,        "SMS");
        serviceIntent.putExtra(PaymentService.EXTRA_SESSION_TOKEN, PaymentService.getCurrentToken());

        try {
            context.startForegroundService(serviceIntent);
        } catch (Exception e) {
            Log.w(TAG, "Could not start PaymentService from SMS receiver");
        }
    }
}
