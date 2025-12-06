package com.nfo.tracker.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nfo.tracker.work.ShiftStateHelper

/**
 * Firebase Cloud Messaging service that handles FCM token updates and incoming push messages.
 *
 * Currently handles:
 * - Token refresh: stores new FCM token locally (and later syncs to Supabase)
 * - WAKE_TRACKING message: shows a notification prompting user to reopen app when tracking has stopped
 *
 * The WAKE_TRACKING message is sent by the server when it detects the device has gone silent
 * (no heartbeats for an extended period). This is a fallback mechanism to alert the NFO.
 */
class NfoFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "NfoFCM"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token received (FULL): $token")
        // Save token locally and send to Supabase
        ShiftStateHelper.updateFcmToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val type = data["type"] ?: ""
        Log.d(TAG, "onMessageReceived: type=$type, data=$data")

        when (type) {
            "WAKE_TRACKING" -> handleWakeTracking()
            else -> Log.d(TAG, "Unknown message type: $type")
        }
    }

    /**
     * Handles WAKE_TRACKING push message from the server.
     * Only shows notification if user is on shift and logged in.
     */
    private fun handleWakeTracking() {
        val isOnShift = ShiftStateHelper.isOnShift(this)
        val isLoggedIn = ShiftStateHelper.isLoggedIn(this)

        Log.d(TAG, "handleWakeTracking: isOnShift=$isOnShift, isLoggedIn=$isLoggedIn")

        if (!isOnShift || !isLoggedIn) {
            Log.d(TAG, "Ignoring WAKE_TRACKING, not on shift or not logged in")
            return
        }

        WakeTrackingNotification.show(this)
    }
}
