package com.example.calling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REJECT_CALL) return
        val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        if (callId.isBlank()) return

        CallNotificationHelper.cancelCallNotification(context, callId)
        ActiveCallTracker.markCallFinished(callId)

        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                CallSignaling(context.applicationContext).rejectCall(callId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_REJECT_CALL = "com.example.calling.ACTION_REJECT_CALL"
        const val EXTRA_CALL_ID = "extra_action_call_id"
        private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
