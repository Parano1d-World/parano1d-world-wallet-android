package org.parano1d.wear

import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only stand-in for the phone so the watch UI can be tried in the Wear OS emulator
 * without a paired phone. Every screen shows a DEMO banner while this is in use.
 * Demo PIN: 1234. Nothing here touches a wallet or the network.
 */
object DemoPhone {
    private val contacts = listOf(
        Triple("a".repeat(64), "Alex", "o1demoalexaddress000000000000000000000000"),
        Triple("b".repeat(64), "Sam", "o1demosamaddress0000000000000000000000000"),
    )
    private val messages = mutableMapOf(
        "d:" + "a".repeat(64) to mutableListOf(
            msg("m1", false, "Alex", "text", "Lunch was great, thanks!"),
            msg("m2", false, "Alex", "invoice", "Payment request · 12.5 NOID").put("amount", "12.5").put("payable", true).put("declined", false),
        ),
        "d:" + "b".repeat(64) to mutableListOf(msg("m3", true, "You", "text", "See you at 6")),
        "g:parano1d" to mutableListOf(msg("m4", false, "noid_fan", "text", "Welcome to the NOID community")),
    )
    private var reviewId = 0

    private fun msg(id: String, mine: Boolean, sender: String, type: String, text: String) =
        JSONObject().put("id", id).put("mine", mine).put("sender", sender).put("type", type).put("text", text).put("time", System.currentTimeMillis())

    fun state(): WatchState = WatchState.parse(JSONObject()
        .put("has_wallet", true).put("hidden", false).put("balance", "88.282987").put("height", 322_099).put("checked_at_ms", System.currentTimeMillis())
        .put("online", true).put("address", "o1demowalletaddress00000000000000000000000")
        .put("settings", JSONObject().put("sends_enabled", true).put("per_send_limit", "100").put("daily_limit", "250").put("spent_today", "0").put("pin", JSONObject().put("set", true)))
        .put("contacts", JSONArray().apply { contacts.forEach { put(JSONObject().put("id", it.first).put("name", it.second).put("address", it.third)) } })
        .put("receipts", JSONArray().put(JSONObject().put("key", "demo1").put("direction", "received").put("amount", "5").put("counterparty", "Sam").put("txid", "demo-tx").put("height", 322_090)))
        .put("unread", 2).put("open_requests", 1).put("price_usd", JSONObject.NULL), demo = true)

    fun handle(request: JSONObject): JSONObject {
        val ok = { result: JSONObject -> JSONObject().put("ok", true).put("result", result) }
        val fail = { error: String -> JSONObject().put("ok", false).put("error", error) }
        return when (request.optString("op")) {
            "pin_unlock" -> if (request.optString("pin") == "1234") ok(JSONObject().put("session", "demo")) else fail("Wrong PIN. Try again. (Demo PIN is 1234)")
            "lock", "hello", "confirm_reply", "handoff" -> ok(JSONObject())
            "chats" -> ok(JSONObject()
                .put("chats", JSONArray().apply { contacts.forEach { c -> put(JSONObject().put("chat", "d:" + c.first).put("name", c.second).put("preview", messages["d:" + c.first]?.lastOrNull()?.optString("text") ?: "")) } })
                .put("community", JSONObject().put("chat", "g:parano1d").put("name", "Community").put("public", true)))
            "messages" -> ok(JSONObject().put("chat", request.optString("chat")).put("public", request.optString("chat") == "g:parano1d")
                .put("messages", JSONArray(messages[request.optString("chat")] ?: emptyList<JSONObject>())))
            "chat_send" -> { messages.getOrPut(request.optString("chat")) { mutableListOf() }.add(msg("s" + System.nanoTime(), true, "You", "text", request.optString("text"))); ok(JSONObject()) }
            "request_create" -> ok(JSONObject())
            "request_decline" -> { messages.values.flatten().firstOrNull { it.optString("id") == request.optString("message_id") }?.put("declined", true)?.put("payable", false); ok(JSONObject()) }
            "send_review" -> {
                val amount = request.optString("amount").ifEmpty { "12.5" }
                ok(JSONObject().put("review_id", "demo" + (++reviewId)).put("contact_name", contacts.firstOrNull { it.first == request.optString("contact_id") }?.second ?: "Alex")
                    .put("to", contacts.firstOrNull { it.first == request.optString("contact_id") }?.third ?: contacts[0].third).put("amount", amount).put("fee", "0.01").put("total", (amount.toBigDecimal() + java.math.BigDecimal("0.01")).toPlainString()).put("expires_seconds", 90))
            }
            "send_approve" -> if (request.optString("pin") == "1234") ok(JSONObject().put("txid", "demo-" + System.currentTimeMillis()).put("delivery_queued", true)) else fail("Wrong PIN. Try again. (Demo PIN is 1234)")
            "nfts" -> ok(JSONObject().put("items", JSONArray()).put("note", "Demo mode: NFTs come from your phone's verified holdings."))
            else -> fail("Not available in demo mode")
        }
    }
}
