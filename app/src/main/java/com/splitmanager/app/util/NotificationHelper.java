package com.splitmanager.app.util;

import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

import com.splitmanager.app.db.PaymentInboxDb;

/**
 * Central helper for keeping the device notification drawer, the in-app inbox,
 * and the app icon badge all in sync.
 *
 * SYNC RULES:
 * - Every payment posts a system notification AND saves an inbox entry with the same notifId.
 * - When the user acts on a notification (from either direction), BOTH are updated:
 *     • Cancel system notification via NotificationManagerCompat
 *     • Mark inbox entry as read (or delete it) via PaymentInboxDb
 * - The app icon badge count reflects the number of unread inbox entries.
 *
 * Android does not provide a universal badge API across all launchers.
 * We use the standard approach: post a summary/group notification whose count
 * the launcher reads. Samsung, MIUI, and most modern launchers respect this.
 */
public class NotificationHelper {

    private static final String TAG = "SplitManager.NotifHelper";

    // Channel IDs — must match those declared in PaymentService
    public static final String CHANNEL_PAYMENT    = "splitmanager_payment";
    public static final String CHANNEL_FOREGROUND = "splitmanager_fg";

    // Group key for bundled notifications — allows badge count via group summary
    public static final String GROUP_PAYMENTS = "com.splitmanager.app.PAYMENTS";

    // Notification ID reserved for the group summary (badge holder)
    public static final int SUMMARY_NOTIF_ID = 99;

    /**
     * Cancel a single system notification and mark its inbox entry as read.
     * Call this when the user taps Split / Ignore on a notification from within the app.
     *
     * @param context  application context
     * @param notifId  the Android notification ID to cancel (-1 = no system notification)
     * @param inboxId  the inbox entry ID to mark read (-1 = skip inbox update)
     */
    public static void cancelNotificationAndMarkRead(Context context, int notifId, long inboxId) {
        // Cancel the system notification
        if (notifId != -1) {
            try {
                NotificationManagerCompat.from(context).cancel(notifId);
            } catch (Exception e) {
                Log.w(TAG, "Could not cancel a payment notification");
            }
        }

        // Mark inbox entry as read so bell badge updates
        if (inboxId != -1) {
            try {
                PaymentInboxDb.getInstance(context).markRead(inboxId);
            } catch (Exception e) {
                Log.w(TAG, "Could not mark inbox entry read");
            }
        }

        // Refresh the badge to reflect the new unread count
        refreshBadge(context);
    }

    /**
     * Cancel a single system notification and DELETE its inbox entry entirely.
     * Call this when the user explicitly removes an item from the inbox
     * (long-press → Remove, or Clear All).
     *
     * @param context  application context
     * @param notifId  the Android notification ID to cancel (-1 = no system notification)
     * @param inboxId  the inbox entry ID to delete (-1 = skip)
     */
    public static void cancelNotificationAndDelete(Context context, int notifId, long inboxId) {
        if (notifId != -1) {
            try {
                NotificationManagerCompat.from(context).cancel(notifId);
            } catch (Exception e) {
                Log.w(TAG, "Could not cancel a payment notification");
            }
        }

        if (inboxId != -1) {
            try {
                PaymentInboxDb.getInstance(context).delete(inboxId);
            } catch (Exception e) {
                Log.w(TAG, "Could not delete inbox entry");
            }
        }

        refreshBadge(context);
    }

    /**
     * Cancel ALL payment notifications and clear the entire inbox.
     * Call this from "Clear all" in InboxActivity.
     */
    public static void cancelAllNotificationsAndClearInbox(Context context) {
        try {
            // Retrieve all notif_ids before deleting, then cancel each
            java.util.List<Integer> ids =
                PaymentInboxDb.getInstance(context).deleteAllAndGetNotifIds();
            NotificationManagerCompat nm = NotificationManagerCompat.from(context);
            for (int id : ids) {
                try { nm.cancel(id); } catch (Exception ignored) {}
            }
            // Also cancel the group summary
            try { nm.cancel(SUMMARY_NOTIF_ID); } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.w(TAG, "Could not cancel all notifications");
        }

        refreshBadge(context);
    }

    /**
     * Update the app icon badge count to the current unread inbox count.
     *
     * Standard Android does not have a badge API before API 26 badge channels.
     * We use the Notification group summary approach:
     * - All payment notifications share a group key.
     * - A summary notification carries setNumber() = unreadCount.
     * - Most launchers (Samsung One UI, MIUI, Pixel) read this number for the badge.
     *
     * If unreadCount == 0, the summary is cancelled (badge disappears).
     */
    public static void refreshBadge(Context context) {
        try {
            int unread = PaymentInboxDb.getInstance(context).unreadCount();
            NotificationManagerCompat nm = NotificationManagerCompat.from(context);

            if (unread == 0) {
                // No unread — remove the badge entirely
                nm.cancel(SUMMARY_NOTIF_ID);
                return;
            }

            // Post/update a silent summary notification carrying the badge count.
            // The summary itself is invisible to the user (no sound, no pop-up, min priority)
            // but the launcher reads its number as the app icon badge.
            androidx.core.app.NotificationCompat.Builder summary =
                new androidx.core.app.NotificationCompat.Builder(context, CHANNEL_PAYMENT)
                    .setSmallIcon(com.splitmanager.app.R.drawable.ic_expense)
                    .setContentTitle("SplitManager")
                    .setContentText(unread + " payment" + (unread == 1 ? "" : "s") + " to split")
                    .setNumber(unread)                         // badge count
                    .setGroup(GROUP_PAYMENTS)
                    .setGroupSummary(true)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
                    .setSilent(true)                           // no sound / vibration
                    .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE);

            try {
                nm.notify(SUMMARY_NOTIF_ID, summary.build());
            } catch (SecurityException e) {
                Log.w(TAG, "Cannot update badge — POST_NOTIFICATIONS not granted");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not refresh badge");
        }
    }
}
