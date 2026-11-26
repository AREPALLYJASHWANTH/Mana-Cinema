package com.manacinema.app.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.manacinema.app.BuildConfig

object FirebaseInitializer {

    // Every Firebase identifier (including the Realtime Database URL) is stored
    // XOR-encoded as numbers and decoded at runtime. Nothing readable ends up in
    // resources.arsc / strings.xml, and a plain-text search inside the APK
    // (MT Manager, apktool, strings) finds no database URL at all.
    // google-services.json intentionally has NO "firebase_url" field, so the
    // Google Services Gradle plugin never emits a firebase_database_url resource.
    private const val K = 0x5A
    private fun x(vararg c: Int): String =
        String(CharArray(c.size) { (c[it] xor K).toChar() })

    private val DB_URL: String by lazy {
        x(50, 46, 46, 42, 41, 96, 117, 117, 61, 63, 52, 119, 54, 59, 52, 61, 119, 57, 54, 51,
          63, 52, 46, 119, 106, 108, 98, 111, 105, 110, 104, 105, 104, 104, 119, 62, 63, 60, 59,
          47, 54, 46, 119, 40, 46, 62, 56, 116, 59, 41, 51, 59, 119, 41, 53, 47, 46, 50, 63, 59,
          41, 46, 107, 116, 60, 51, 40, 63, 56, 59, 41, 63, 62, 59, 46, 59, 56, 59, 41, 63, 116,
          59, 42, 42)
    }

    /** Realtime Database URL of the current Firebase project. */
    val databaseUrl: String get() = DB_URL

    private val PROJECT_ID: String by lazy {
        x(61, 63, 52, 119, 54, 59, 52, 61, 119, 57, 54, 51, 63, 52, 46, 119, 106, 108, 98, 111,
          105, 110, 104, 105, 104, 104)
    }
    private val STORAGE_BUCKET: String by lazy {
        x(61, 63, 52, 119, 54, 59, 52, 61, 119, 57, 54, 51, 63, 52, 46, 119, 106, 108, 98, 111,
          105, 110, 104, 105, 104, 104, 116, 60, 51, 40, 63, 56, 59, 41, 63, 41, 46, 53, 40, 59,
          61, 63, 116, 59, 42, 42)
    }
    private val APP_ID: String by lazy {
        x(107, 96, 108, 110, 106, 110, 106, 105, 109, 98, 106, 111, 110, 109, 96, 59, 52, 62, 40,
          53, 51, 62, 96, 109, 109, 104, 105, 63, 108, 98, 59, 98, 62, 110, 110, 57, 99, 108, 108,
          98, 60, 106, 105, 109, 108)
    }
    private val GCM_SENDER_ID: String by lazy {
        x(108, 110, 106, 110, 106, 105, 109, 98, 106, 111, 110, 109)
    }

    fun initialize(context: Context) {
        try {
            val options = FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(APP_ID)
                .setDatabaseUrl(DB_URL)
                .setProjectId(PROJECT_ID)
                .setStorageBucket(STORAGE_BUCKET)
                .setGcmSenderId(GCM_SENDER_ID)
                .build()

            val existing = FirebaseApp.getApps(context)
                .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }

            if (existing == null) {
                FirebaseApp.initializeApp(context, options)
            } else if (existing.options.databaseUrl != DB_URL ||
                existing.options.projectId != PROJECT_ID
            ) {
                // FirebaseInitProvider may have auto-created the default app from
                // the (deliberately neutral) generated resources. Replace it with
                // the real, code-only configuration.
                existing.delete()
                FirebaseApp.initializeApp(context, options)
            }
        } catch (e: Exception) {
            android.util.Log.e("FirebaseInitializer", "FirebaseApp initialization failed", e)
        }

        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                .setCrashlyticsCollectionEnabled(true)
        } catch (e: Exception) {
            android.util.Log.e("FirebaseInitializer", "Crashlytics enable failed", e)
        }
    }
}

// Thin wrapper so screens can log analytics events without touching Firebase APIs directly.
object Analytics {
    private fun fa(context: Context) =
        com.google.firebase.analytics.FirebaseAnalytics.getInstance(context)

    fun logScreen(context: Context, screenName: String) {
        try {
            fa(context).logEvent(
                com.google.firebase.analytics.FirebaseAnalytics.Event.SCREEN_VIEW,
                android.os.Bundle().apply {
                    putString(com.google.firebase.analytics.FirebaseAnalytics.Param.SCREEN_NAME, screenName)
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("Analytics", "logScreen failed", e)
        }
    }

    fun logPlay(context: Context, contentId: String, title: String) {
        try {
            fa(context).logEvent(
                com.google.firebase.analytics.FirebaseAnalytics.Event.SELECT_CONTENT,
                android.os.Bundle().apply {
                    putString(com.google.firebase.analytics.FirebaseAnalytics.Param.ITEM_ID, contentId)
                    putString(com.google.firebase.analytics.FirebaseAnalytics.Param.ITEM_NAME, title)
                    putString(com.google.firebase.analytics.FirebaseAnalytics.Param.CONTENT_TYPE, "video")
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("Analytics", "logPlay failed", e)
        }
    }
}
