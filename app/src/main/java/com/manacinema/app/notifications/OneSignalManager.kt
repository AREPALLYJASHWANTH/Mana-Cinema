package com.manacinema.app.notifications

import android.app.Application
import android.content.Context
import com.onesignal.OneSignal
import com.onesignal.debug.LogLevel
import com.onesignal.user.subscriptions.IPushSubscriptionObserver
import com.onesignal.user.subscriptions.PushSubscriptionChangedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single boundary for all OneSignal SDK calls.
 */
object OneSignalManager {
    private const val APP_ID = "01d0e8d5-9ea8-48c6-86f1-56ff2281f928"
    private const val PREFS = "onesignal_integration"
    private const val PERMISSION_REQUESTED = "permission_requested"

    private val initialized = AtomicBoolean(false)
    private val permissionRequested = AtomicBoolean(false)

    // OneSignal stores observers weakly, so this must remain strongly referenced.
    private var pushSubscriptionObserver: IPushSubscriptionObserver? = null

    @Volatile
    private var subscriptionId: String? = null

    fun initialize(application: Application) {
        if (!initialized.compareAndSet(false, true)) return
        val preferences = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        permissionRequested.set(preferences.getBoolean(PERMISSION_REQUESTED, false))

        // Keep production logs quiet; enable verbose logging only while diagnosing locally.
        OneSignal.Debug.logLevel = LogLevel.NONE
        OneSignal.initWithContext(application, APP_ID)

        val observer = object : IPushSubscriptionObserver {
            override fun onPushSubscriptionChange(state: PushSubscriptionChangedState) {
                recordSubscriptionId(state.current.id)
            }
        }
        pushSubscriptionObserver = observer
        OneSignal.User.pushSubscription.addObserver(observer)

        // Registration may have completed before the observer was attached.
        recordSubscriptionId(OneSignal.User.pushSubscription.id)

    }

    /** Latest OneSignal subscription ID once device registration has completed. */
    val lastSubscriptionId: String?
        get() = subscriptionId

    /**
     * Requests permission after onboarding. Subsequent calls are no-ops.
     */
    fun requestPermissionIfNeeded(context: Context) {
        if (permissionRequested.getAndSet(true)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PERMISSION_REQUESTED, true).apply()
        CoroutineScope(Dispatchers.Main).launch {
            OneSignal.Notifications.requestPermission(false)
        }
    }

    fun login(externalId: String) {
        if (externalId.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch { OneSignal.login(externalId) }
    }

    fun logout() {
        CoroutineScope(Dispatchers.IO).launch { OneSignal.logout() }
    }

    private fun recordSubscriptionId(id: String?) {
        if (id.isNullOrBlank() || id.startsWith("local-")) return
        subscriptionId = id
    }
}
