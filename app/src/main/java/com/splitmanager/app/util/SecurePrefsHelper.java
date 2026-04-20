package com.splitmanager.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Secure wrapper around EncryptedSharedPreferences (AES-256-GCM/SIV).
 * Keys are stored in Android Keystore hardware.
 *
 * SECURITY: No plaintext fallback. If encryption fails, we throw — we never
 * silently downgrade to plain SharedPreferences.
 *
 * Design: no static instance field is cached. create() is called on every get()
 * invocation. EncryptedSharedPreferences.create() is idempotent — Android caches
 * the underlying encrypted file handle internally, so repeated calls are fast (μs).
 * Avoiding a static cache eliminates three risks:
 *   1. Stale instance after Keystore re-keying (OS upgrade / factory reset on
 *      some devices) — a cached instance would silently fail all decryptions.
 *   2. clearAll() not reflecting in a cached reference — callers reading from
 *      the old cached object would see the cleared values only after a restart.
 *   3. Long-lived static reference to application context that prevents GC of
 *      any objects transitively held by the SharedPreferences implementation.
 */
public class SecurePrefsHelper {

    private static final String TAG = "SplitManager.SecurePrefs";
    private static final String PREFS_FILE = "splitmanager_secure_prefs";

    public static final String KEY_SPLITWISE_API_KEY = "splitwise_api_key";
    public static final String KEY_SPLITWISE_USER_ID = "splitwise_user_id";
    public static final String KEY_DEFAULT_GROUP_ID  = "default_group_id";
    public static final String KEY_ONBOARDING_DONE   = "onboarding_done";

    /**
     * Returns a fresh EncryptedSharedPreferences instance backed by the
     * Android Keystore. Always uses applicationContext — never retains Activity.
     *
     * EncryptedSharedPreferences.create() is idempotent: Android's implementation
     * caches the underlying encrypted file handle, so this is fast after the first
     * call and safe to invoke on every read/write without performance penalty.
     *
     * @throws SecurityException if the Keystore is unavailable — caller must handle.
     */
    public static SharedPreferences get(Context context) {
        return create(context.getApplicationContext());
    }

    private static SharedPreferences create(Context ctx) {
        try {
            MasterKey masterKey = new MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(false) // StrongBox not universal; regular Keystore is fine
                .build();

            SharedPreferences encrypted = EncryptedSharedPreferences.create(
                ctx,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            Log.d(TAG, "Encrypted prefs initialised");
            return encrypted;

        } catch (Exception e) {
            // Do NOT fall back to plaintext. Throw so callers can show a proper error.
            Log.e(TAG, "Keystore unavailable — cannot initialise secure storage");
            throw new SecurityException("Secure storage unavailable. Device Keystore may be compromised.", e);
        }
    }

    // ── Safe accessors — return empty string / false if prefs unavailable ──

    public static String getApiKey(Context ctx) {
        try { return get(ctx).getString(KEY_SPLITWISE_API_KEY, ""); }
        catch (SecurityException e) { return ""; }
    }

    public static void saveApiKey(Context ctx, String key) {
        get(ctx).edit().putString(KEY_SPLITWISE_API_KEY, key).apply();
    }

    public static boolean hasApiKey(Context ctx) {
        return !getApiKey(ctx).isEmpty();
    }

    public static void clearAll(Context ctx) {
        try { get(ctx).edit().clear().apply(); }
        catch (SecurityException e) { Log.e(TAG, "Could not clear prefs"); }
    }
}
