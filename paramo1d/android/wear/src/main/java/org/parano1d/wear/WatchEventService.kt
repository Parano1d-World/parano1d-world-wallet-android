package org.parano1d.wear

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/** Receives phone answers, state updates, receipts and send confirmations — also while the app is closed. */
class WatchEventService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val body = String(event.data)
        when (event.path) {
            "/p1/rpc-result" -> PhoneLink.complete(body)
            "/p1/event" -> runCatching { handleEvent(this, JSONObject(body)) }
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != "/p1/state") continue
            val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString("json") ?: continue
            PhoneLink.onState(this, json)
        }
    }

    companion object {
        private const val RECEIPTS = "receipts"
        private const val CONFIRM = "confirm"
        private const val MESSAGES = "messages"

        fun handleEvent(context: Context, event: JSONObject) {
            when (event.optString("type")) {
                "receipt" -> notifyReceipt(context, event)
                "confirm" -> { PhoneLink.showConfirm(event); notifyConfirm(context, event) }
                "message" -> notifyMessage(context, event)
                // Open screens refresh at once; no message text is carried (it is read in the PIN session).
                "chat_update" -> event.optString("chat").takeIf { it.isNotEmpty() }?.let { PhoneLink.pulse(it) }
                "nft_update" -> PhoneLink.nftPulse.value = System.currentTimeMillis()
            }
        }

        private fun channels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(RECEIPTS) == null) manager.createNotificationChannel(NotificationChannel(RECEIPTS, "Receipts", NotificationManager.IMPORTANCE_DEFAULT))
            if (manager.getNotificationChannel(MESSAGES) == null) manager.createNotificationChannel(NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_DEFAULT))
            if (manager.getNotificationChannel(CONFIRM) == null) manager.createNotificationChannel(NotificationChannel(CONFIRM, "Confirm phone sends", NotificationManager.IMPORTANCE_HIGH))
        }

        private fun open(context: Context, extra: String, value: String, code: Int, name: String? = null): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(extra, value)
            if (name != null) intent.putExtra("name", name)
            return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        @SuppressLint("MissingPermission") // checked at runtime by the system; failures are ignored
        private fun notifyReceipt(context: Context, event: JSONObject) {
            channels(context)
            val received = event.optString("direction") == "received"
            val who = event.optString("counterparty")
            val title = if (received) "NOID received" else "Payment final"
            val text = buildString {
                append(event.optString("amount")).append(" NOID")
                if (who.isNotEmpty()) append(if (received) " from " else " to ").append(who)
                if (event.optLong("height") > 0) append(" · block ").append(event.optLong("height"))
            }
            val notification = NotificationCompat.Builder(context, RECEIPTS).setSmallIcon(R.drawable.parano1d_mark)
                .setContentTitle(title).setContentText(text).setAutoCancel(true)
                .setContentIntent(open(context, "open", "receipts", 2001)).setLocalOnly(true).build()
            runCatching { NotificationManagerCompat.from(context).notify(event.optString("key").hashCode(), notification) }
        }

        @SuppressLint("MissingPermission")
        private fun notifyMessage(context: Context, event: JSONObject) {
            channels(context)
            val chat = event.optString("chat"); if (chat.isEmpty()) return
            val name = event.optString("name").ifEmpty { "Contact" }
            val code = 3000 + Math.floorMod(chat.hashCode(), 100000)
            // No message text leaves the phone outside a PIN session; the alert only names the sender.
            val notification = NotificationCompat.Builder(context, MESSAGES).setSmallIcon(R.drawable.parano1d_mark)
                .setContentTitle(name).setContentText("New private message").setCategory(NotificationCompat.CATEGORY_MESSAGE).setAutoCancel(true)
                .setContentIntent(open(context, "chat", chat, code, name)).setLocalOnly(true).build()
            runCatching { NotificationManagerCompat.from(context).notify(code, notification) }
        }

        @SuppressLint("MissingPermission")
        private fun notifyConfirm(context: Context, event: JSONObject) {
            channels(context)
            val text = "${event.optString("amount")} NOID to ${event.optString("to_name").ifEmpty { event.optString("to").take(10) + "…" }}"
            val notification = NotificationCompat.Builder(context, CONFIRM).setSmallIcon(R.drawable.parano1d_mark)
                .setContentTitle("Approve phone send?").setContentText(text).setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX).setAutoCancel(true).setTimeoutAfter(60_000)
                .setContentIntent(open(context, "confirm", event.toString(), 2002)).setLocalOnly(true).build()
            runCatching { NotificationManagerCompat.from(context).notify(2002, notification) }
        }
    }
}
