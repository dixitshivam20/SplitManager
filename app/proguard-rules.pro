# ── SplitManager ProGuard Rules ──────────────────────────────────────────────

# ── App classes referenced by name in AndroidManifest — MUST NOT be renamed ──
-keep class com.splitmanager.app.ui.**      { *; }
-keep class com.splitmanager.app.service.** { *; }
-keep class com.splitmanager.app.util.**    { *; }

# ── Model classes used by Gson ────────────────────────────────────────────────
-keep class com.splitmanager.app.model.** { *; }
-keep class com.splitmanager.app.api.**   { *; }
-keepattributes Signature
-keepattributes *Annotation*

# ── Gson ──────────────────────────────────────────────────────────────────────
-dontwarn com.google.gson.**
-keep class com.google.gson.** { *; }

# ── OkHttp + Okio ─────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# ── EncryptedSharedPreferences / Tink ─────────────────────────────────────────
# CRITICAL: Tink uses reflection internally.
-keep class androidx.security.crypto.**              { *; }
-keep class com.google.crypto.tink.**                { *; }
-keep class com.google.crypto.tink.subtle.**         { *; }
-keep class com.google.crypto.tink.proto.**          { *; }
-keep class com.google.crypto.tink.shaded.protobuf.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.crypto.tink.subtle.**
-keep class android.security.keystore.** { *; }
-keep class java.security.**  { *; }
-keep class javax.crypto.**   { *; }
-keep class javax.security.** { *; }

# ── Strip debug/verbose logs in release ──────────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# ── Obfuscation ───────────────────────────────────────────────────────────────
-repackageclasses 'sm'
-optimizationpasses 3

# ── Preserve enum names (used in Intent extras via .name()) ──────────────────
-keepclassmembers enum com.splitmanager.app.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
