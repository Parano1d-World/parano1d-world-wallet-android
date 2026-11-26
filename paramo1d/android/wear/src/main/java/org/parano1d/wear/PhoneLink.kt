package org.parano1d.wear

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the phone shares without a PIN session: balance, contacts, receipts, unread counts. */
data class WatchState(
    val hasWallet: Boolean,
    val hidden: Boolean,
    val balance: String?,
    val height: Long,
    val checkedAtMs: Long,
    val online: Boolean,
    val address: String,
    val sendsEnabled: Boolean,
    val pinSet: Boolean,
    val perSendLimit: String,
    val dailyLimit: String,
    val spentToday: String,
    val contacts: List<Contact>,
    val receipts: List<Receipt>,
    val unread: Int,
    val openRequests: Int,
    val priceUsd: String?,
    val demo: Boolean = false,
) {
    companion object {
        fun parse(json: JSONObject, demo: Boolean = false): WatchState {
            val settings = json.optJSONObject("settings") ?: JSONObject()
            WatchStyle.apply(json.optString("style"))
            return WatchState(
                hasWallet = json.optBoolean("has_wallet"),
                hidden = json.optBoolean("hidden"),
                balance = if (json.isNull("balance")) null else json.optString("balance"),
                height = json.optLong("height"),
                checkedAtMs = json.optLong("checked_at_ms"),
                online = json.optBoolean("online"),
                address = json.optString("address"),
                sendsEnabled = settings.optBoolean("sends_enabled"),
                pinSet = settings.optJSONObject("pin")?.optBoolean("set") ?: false,
                perSendLimit = settings.optString("per_send_limit", "100"),
                dailyLimit = settings.optString("daily_limit", "250"),
                spentToday = settings.optString("spent_today", "0"),
                contacts = json.optJSONArray("contacts").objects().map { Contact(it.optString("id"), it.optString("name", "Contact"), it.optString("address"), it.optBoolean("address_changed")) },
                receipts = json.optJSONArray("receipts").objects().map { Receipt.parse(it) },
                unread = json.optInt("unread"),
                openRequests = json.optInt("open_requests"),
                priceUsd = if (json.isNull("price_usd")) null else json.optString("price_usd"),
                demo = demo,
            )
        }
    }
}

data class Contact(val id: String, val name: String, val address: String, val addressChanged: Boolean)

data class Receipt(val key: String, val direction: String, val amount: String, val counterparty: String, val txid: String, val height: Long) {
    companion object {
        fun parse(it: JSONObject) = Receipt(it.optString("key"), it.optString("direction"), it.optString("amount"), it.optString("counterparty"), it.optString("txid"), it.optLong("height"))
    }
}

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

class PhoneError(message: String, val code: String = "", val contactId: String = "", val amount: String = "") : Exception(message)

/**
 * Talks to the Parano1d phone app over the Wearable Data Layer.
 * Responses and events arrive through WatchEventService, which calls [complete] and [onState].
 */
object PhoneLink {
    private const val PHONE_CAPABILITY = "parano1d_phone"
    private val counter = AtomicLong()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    private val _state = MutableStateFlow<WatchState?>(null)
    val state: StateFlow<WatchState?> = _state
    private val _connected = MutableStateFlow(true)
    val connected: StateFlow<Boolean> = _connected
    private val _pendingConfirm = MutableStateFlow<JSONObject?>(null)
    val pendingConfirm: StateFlow<JSONObject?> = _pendingConfirm

    /** When the phone last sent fresh state (for "From phone · N min ago"). */
    private val _updatedAt = MutableStateFlow(0L)
    val updatedAt: StateFlow<Long> = _updatedAt
    /** Last loaded messages per chat, so a conversation opens instantly while it refreshes. */
    val threadCache = ConcurrentHashMap<String, JSONObject>()
    /** Phone said a conversation changed (chat id → time), like the Apple Watch "chat_update" pulse. */
    val chatPulse = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Phone finished verifying NFT holdings ("nft_update"). */
    val nftPulse = MutableStateFlow(0L)
    /** Last chat list the phone shared; kept when the phone can't read its chats (e.g. while locked). */
    @Volatile var chatsCache: JSONObject? = null
    /** Last NFT list shown, so the gallery opens instantly. */
    @Volatile var nftCache: JSONObject? = null
    fun pulse(chat: String) { chatPulse.value = chatPulse.value + (chat to System.currentTimeMillis()) }

    /** PIN session token from the phone. Survives wrist-down; the phone ends it after 2 idle minutes or 30 minutes. */
    @Volatile var session: String? = null
    @Volatile var demo: Boolean = false

    fun loadSaved(context: Context) {
        if (_state.value != null) return
        val saved = context.getSharedPreferences("watch", Context.MODE_PRIVATE).getString("state", null) ?: return
        runCatching { _state.value = WatchState.parse(JSONObject(saved)) }
        _updatedAt.value = context.getSharedPreferences("watch", Context.MODE_PRIVATE).getLong("updated_at", 0L)
    }

    fun onState(context: Context, json: String) {
        context.getSharedPreferences("watch", Context.MODE_PRIVATE).edit().putString("state", json).apply()
        runCatching { _state.value = WatchState.parse(JSONObject(json)) }
        _updatedAt.value = System.currentTimeMillis()
        context.getSharedPreferences("watch", Context.MODE_PRIVATE).edit().putLong("updated_at", _updatedAt.value).apply()
        _connected.value = true
        BalanceComplicationService.requestUpdate(context)
    }

    fun showConfirm(event: JSONObject?) { _pendingConfirm.value = event }

    fun complete(json: String) {
        val response = runCatching { JSONObject(json) }.getOrNull() ?: return
        waiting.remove(response.optString("id"))?.complete(response)
    }

    private suspend fun phoneNode(context: Context): String? {
        val info = Wearable.getCapabilityClient(context).getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
        return info.nodes.firstOrNull { it.isNearby }?.id ?: info.nodes.firstOrNull()?.id
    }

    /** Asks the phone to republish state. In debug builds with no phone, switches to demo data. */
    suspend fun hello(context: Context) {
        val node = runCatching { phoneNode(context) }.getOrNull()
        if (node == null) {
            _connected.value = false
            if (BuildConfig.DEBUG) { demo = true; _state.value = DemoPhone.state(); _connected.value = true }
            return
        }
        demo = false
        runCatching { call(context, "hello") }
    }

    /** Sends one request and waits for the phone's answer. Throws [PhoneError] on failure. */
    suspend fun call(context: Context, op: String, params: JSONObject = JSONObject(), timeoutMs: Long = 30_000): JSONObject {
        val request = JSONObject(params.toString()).put("op", op)
        session?.let { if (!request.has("session")) request.put("session", it) }
        val response = if (demo) DemoPhone.handle(request) else {
            val node = runCatching { phoneNode(context) }.getOrNull()
            if (node == null) { _connected.value = false; throw PhoneError("Phone not connected", "offline") }
            val id = "r" + counter.incrementAndGet()
            request.put("id", id)
            val deferred = CompletableDeferred<JSONObject>()
            waiting[id] = deferred
            try {
                Wearable.getMessageClient(context).sendMessage(node, "/p1/rpc", request.toString().toByteArray()).await()
                _connected.value = true
                withTimeout(timeoutMs) { deferred.await() }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                throw PhoneError("The phone didn't answer. Check that Parano1d is set up on your phone.", "timeout")
            } finally {
                waiting.remove(id)
            }
        }
        if (!response.optBoolean("ok")) {
            val code = response.optString("code")
            if (code == "session_expired") session = null
            throw PhoneError(response.optString("error", "The phone couldn't complete this."), code, response.optString("contact_id"), response.optString("amount"))
        }
        return response.optJSONObject("result") ?: JSONObject()
    }

    suspend fun lock(context: Context) {
        val had = session != null
        session = null
        if (had) runCatching { call(context, "lock", timeoutMs = 5_000) }
    }
}

/** Follows the phone's appearance: "Parano1d World" switches the watch to lime on black (never the default). */
/** Follows the phone's appearance: "world", "cyber" or the standard look, with "+orange" while Paranoid mode
 *  (private-only chat) is on — the same values as the Apple Watch (WatchTheme). */
object WatchStyle {
    val world = androidx.compose.runtime.mutableStateOf(false)
    val cyber = androidx.compose.runtime.mutableStateOf(false)
    val orange = androidx.compose.runtime.mutableStateOf(false)
    fun apply(style: String) { world.value = style.startsWith("world"); cyber.value = style.startsWith("cyber"); orange.value = style.endsWith("+orange") }
}
