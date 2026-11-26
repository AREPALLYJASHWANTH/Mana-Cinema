package com.manacinema.app.firebase

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.manacinema.app.MainActivity
import com.manacinema.app.R

/**
 * Persists the FCM token under the signed-in user's own record so the Cloud Function
 * `sendNotificationOnCreate` can target this device: `users/{uid}/fcmTokens/{token}`.
 *
 * RTDB rules only allow a user to write to their own `fcmTokens` subtree, so the token
 * must be keyed by `uid`, never under a global `installations` node.
 */
class AppFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            registerToken(this, token, uid)
        } else {
            // Sign-in may not have finished yet. Remember it and let the ViewModel
            // flush it to the user's record once an anonymous uid exists.
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(PENDING_FCM_TOKEN_KEY, token).apply()
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "Mana Cinema"
        val body = message.notification?.body
            ?: message.data["message"]
            ?: ""
        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val channelId = "mana_cinema_default"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "General Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        const val PREFS_NAME = "mana_fcm_prefs"
        private const val PENDING_FCM_TOKEN_KEY = "pending_fcm_token"

        /** Persist any FCM token captured before auth was ready, once a uid exists. */
        fun flushPendingToken(context: Context, uid: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val token = prefs.getString(PENDING_FCM_TOKEN_KEY, null)
            if (token.isNullOrBlank()) return
            prefs.edit().remove(PENDING_FCM_TOKEN_KEY).apply()
            registerToken(context, token, uid)
        }

        private fun registerToken(context: Context, token: String, uid: String) {
            try {
                val usersRef = FirebaseDatabase
                    .getInstance(FirebaseInitializer.databaseUrl)
                    .getReference("users")
                // Multi-device map: keeps every device's token instead of the newest
                // token silently overwriting the rest and killing push on older devices.
                usersRef.child(uid).child("fcmTokens").child(token).child("updatedAt")
                    .setValue(System.currentTimeMillis())
                    .addOnFailureListener {
                        android.util.Log.e("FCM", "Failed to register token for $uid", it)
                    }
                // Keep the legacy single-value field for older backend tooling.
                usersRef.child(uid).child("fcmToken").setValue(token)
            } catch (e: Exception) {
                android.util.Log.e("FCM", "Failed to save token", e)
            }
        }
    }
}
