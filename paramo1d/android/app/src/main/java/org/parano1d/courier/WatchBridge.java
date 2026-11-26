package org.parano1d.courier;

import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Phone side of the Wear OS companion. The watch holds no keys and runs no node.
 *
 * Protocol (JSON over the Wearable Data Layer):
 *   watch → phone  message  /p1/rpc         {id, op, ...}
 *   phone → watch  message  /p1/rpc-result  {id, ok, result | error}
 *   phone → watch  message  /p1/event       {type: receipt | confirm | settings}
 *   phone → watch  data     /p1/state       {json}  balance, contacts, receipts (no message text)
 *
 * Message text is only sent inside an open PIN session (op "chats" / "messages").
 */
final class WatchBridge {
    static final String PHONE_CAPABILITY = "parano1d_phone";
    static final String WATCH_CAPABILITY = "parano1d_watch";
    private static final String COMMUNITY = "g:parano1d";
    private static final AtomicLong counter = new AtomicLong();
    private static final Map<String, Consumer<JSONObject>> pending = new HashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static String lastPublished = "";

    private WatchBridge() {}

    // ------------------------------------------------------------------ RPC entry

    /** Called by WatchListenerService for each /p1/rpc message. */
    static void onRpc(Context context, String sourceNode, byte[] payload) {
        final Context app = context.getApplicationContext();
        MainActivity.onWorker(() -> {
            String id = "";
            try {
                JSONObject request = new JSONObject(new String(payload, StandardCharsets.UTF_8));
                id = request.optString("id");
                final String rpcId = id;
                dispatch(app, request, result -> respond(app, sourceNode, rpcId, result, null), error -> respond(app, sourceNode, rpcId, null, error));
            } catch (Exception error) {
                respond(app, sourceNode, id, null, error);
            }
        });
    }

    private interface Ok { void accept(JSONObject value) throws Exception; }

    private static void dispatch(Context c, JSONObject request, Ok ok, Consumer<Exception> fail) throws Exception {
        JSONObject data = MainActivity.watchData();
        String op = request.getString("op");
        switch (op) {
            case "hello":
                lastPublished = ""; publish(c);
                ok.accept(new JSONObject().put("phone", true));
                return;
            case "pin_unlock": {
                JSONObject check = WalletPin.verify(c, request.optString("pin"));
                if (!check.optBoolean("ok")) throw new Exception(check.optString("error"));
                ok.accept(new JSONObject().put("session", WatchPolicy.openSession())
                    .put("idle_seconds", WatchPolicy.SESSION_IDLE_MS / 1000).put("max_seconds", WatchPolicy.SESSION_MAX_MS / 1000));
                return;
            }
            case "lock":
                WatchPolicy.endSessions();
                ok.accept(new JSONObject().put("locked", true));
                return;
            case "confirm_reply":
                confirmReply(request.getString("confirm_id"), request.optBoolean("approved"));
                ok.accept(new JSONObject().put("received", true));
                return;
            case "handoff": {
                requireIdentity(data);
                String contactId = request.optString("contact_id");
                postHandoff(c, contactId, request.optString("amount"), request.optString("reason", "Finish this payment on your phone."));
                ok.accept(new JSONObject().put("handoff", true));
                return;
            }
            default: break;
        }
        // NFT gallery, like the balance on the watch's home screen, needs no PIN session.
        if (op.equals("nfts")) { requireIdentity(data); ok.accept(nfts(c, data)); return; }
        // Everything below needs an open PIN session.
        if (!WatchPolicy.touchSession(request.optString("session"))) throw new SessionExpired();
        requireIdentity(data);
        switch (op) {
            case "chats": ok.accept(chats(c, data)); return;
            case "messages": ok.accept(messages(c, data, request.getString("chat"))); return;
            case "chat_send": {
                String chat = request.getString("chat"), text = request.getString("text").trim();
                checkChat(data, chat);
                if (text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > 2000) throw new Exception("Message is empty or too long.");
                MainActivity.watchSendMessage(chat, new JSONObject().put("type", "text").put("text", text));
                ok.accept(new JSONObject().put("queued", true));
                return;
            }
            case "request_create": {
                String contactId = request.getString("contact_id");
                BigDecimal amount = WatchPolicy.amount(request.getString("amount"));
                findContact(data, contactId);
                String address = data.getJSONObject("profile").getString("address");
                JSONObject invoice = new JSONObject().put("type", "invoice").put("invoice_id", WatchPolicy.randomHex(16))
                    .put("address", address).put("amount", amount.toPlainString()).put("memo", request.optString("memo"));
                MainActivity.watchSendMessage("d:" + contactId, invoice);
                ok.accept(new JSONObject().put("requested", true));
                return;
            }
            case "request_decline": {
                JSONObject message = findMessage(data, request.getString("message_id"));
                if (!message.getJSONObject("message").optString("type").equals("invoice")) throw new Exception("Not a payment request.");
                WatchPolicy.markDeclined(c, message.getString("id"));
                MainActivity.watchSendMessage(message.getString("chat"), new JSONObject().put("type", "text")
                    .put("text", "Declined your payment request for " + message.getJSONObject("message").optString("amount") + " NOID."));
                ok.accept(new JSONObject().put("declined", true));
                return;
            }
            case "send_review": sendReview(c, data, request, ok, fail); return;
            case "send_approve": sendApprove(c, request, ok, fail); return;
            default: throw new Exception("Unknown watch request");
        }
    }

    static final class SessionExpired extends Exception { SessionExpired() { super("session_expired"); } }

    // ------------------------------------------------------------------ Sending

    private static void sendReview(Context c, JSONObject data, JSONObject request, Ok ok, Consumer<Exception> fail) throws Exception {
        if (!WatchPolicy.sendsEnabled(c)) throw new Exception("Watch sends are off. Turn them on in the phone app: Settings → Wear OS watch.");
        String contactId, address, invoiceId = "", invoiceMessageId = "";
        BigDecimal amount;
        if (!request.optString("invoice_message_id").isEmpty()) {
            JSONObject message = findMessage(data, request.getString("invoice_message_id"));
            JSONObject invoice = message.getJSONObject("message");
            if (!invoice.optString("type").equals("invoice") || !message.getString("chat").startsWith("d:")) throw new Exception("Not a payment request.");
            if (WatchPolicy.declined(c, message.getString("id"))) throw new Exception("You declined this request.");
            contactId = message.getString("chat").substring(2);
            JSONObject contact = findContact(data, contactId);
            address = invoice.getString("address");
            if (!address.equals(contact.optString("address"))) throw new Handoff("This request pays a different address than the contact's saved one.", contactId, invoice.optString("amount"));
            amount = WatchPolicy.amount(invoice.getString("amount"));
            invoiceId = invoice.optString("invoice_id");
            invoiceMessageId = message.getString("id");
        } else {
            contactId = request.getString("contact_id");
            JSONObject contact = findContact(data, contactId);
            address = contact.optString("address");
            if (address.isEmpty()) throw new Exception("This contact has no saved NOID address.");
            amount = WatchPolicy.amount(request.getString("amount"));
        }
        String pinned = WatchPolicy.pinnedAddress(c, contactId);
        if (pinned != null && !pinned.equals(address)) throw new Handoff("Address changed. Confirm this contact on your phone.", contactId, amount.toPlainString());
        if (c.getSystemService(KeyguardManager.class).isDeviceLocked()) throw new Handoff("Unlock your phone to send from the watch.", contactId, amount.toPlainString());

        JSONObject preview = new JSONObject().put("op", "payment_preview").put("address", address).put("amount", amount.toPlainString())
            .put("invoice_id", invoiceId).put("chat", "d:" + contactId).put("use_cached", true).put("replaces_txid", "").put("watch", true);
        final String fContact = contactId, fAddress = address, fInvoiceMessage = invoiceMessageId;
        final String contactName = findContact(data, contactId).optString("name", "Contact");
        chainCall(preview, response -> {
            try {
                if (!response.optBoolean("ok")) throw new Exception(response.optString("error", "Payment review failed"));
                JSONObject review = response.getJSONObject("result");
                if (!review.optString("to").equals(fAddress)) throw new Exception("The reviewed recipient does not match the contact.");
                WatchPolicy.Review saved = new WatchPolicy.Review(review.getString("review_id"), fContact, review.getString("to"),
                    review.optString("amount"), review.optString("fee"), review.optString("total"), fInvoiceMessage);
                WatchPolicy.putReview(saved);
                ok.accept(new JSONObject().put("review_id", saved.reviewId).put("contact_name", contactName).put("to", saved.to)
                    .put("amount", saved.amount).put("fee", saved.fee).put("total", saved.total)
                    .put("expires_seconds", WatchPolicy.REVIEW_MS / 1000).put("using_saved_state", review.optBoolean("using_saved_state")));
            } catch (Exception error) { fail.accept(error); }
        }, fail, contactId, amount.toPlainString());
    }

    private static void sendApprove(Context c, JSONObject request, Ok ok, Consumer<Exception> fail) throws Exception {
        WatchPolicy.Review review = WatchPolicy.claimReview(request.getString("review_id"));
        // The watch must echo the exact address and amount the user confirmed on screen.
        if (!review.to.equals(request.optString("confirmed_to")) || !review.amount.equals(request.optString("confirmed_amount")))
            throw new Exception("Confirm the recipient address and amount on the watch before sending.");
        // Like iOS 164: the payment the phone will actually sign must be this exact reviewed payment.
        JSONObject pendingReview = MainActivity.watchData().optJSONObject("pending_review");
        if (pendingReview == null || !review.reviewId.equals(pendingReview.optString("review_id")) || !review.to.equals(pendingReview.optString("to")) || !review.amount.equals(pendingReview.optString("amount")))
            throw new Exception("The payment changed on the phone. Review it again on the watch.");
        JSONObject check = WalletPin.verify(c, request.optString("pin"));
        if (!check.optBoolean("ok")) throw new Exception(check.optString("error")); // a typo keeps the review
        BigDecimal amount = new BigDecimal(review.amount);
        if (c.getSystemService(KeyguardManager.class).isDeviceLocked()) throw new Handoff("Unlock your phone to send from the watch.", review.contactId, review.amount);
        WatchPolicy.markUsed(review);
        // Audit 176: tell the core exactly what the watch showed and the person confirmed.
        JSONObject send = new JSONObject().put("op", "payment_send").put("review_id", review.reviewId).put("watch", true)
            .put("confirmed_to", review.to).put("confirmed_amount_micronoid", WalletKeys.micronoid(review.amount))
            .put("confirmed_review_id", review.reviewId);
        chainCall(send, response -> {
            try {
                if (!response.optBoolean("ok")) throw new Exception(response.optString("error", "Payment was not signed"));
                JSONObject result = response.getJSONObject("result");
                WatchPolicy.recordSigned(c, amount);
                WatchPolicy.pinAddress(c, review.contactId, review.to);
                publish(c);
                ok.accept(new JSONObject().put("txid", result.optString("txid")).put("submitted", result.optBoolean("submitted"))
                    .put("delivery_queued", result.optBoolean("delivery_queued")));
            } catch (Exception error) { fail.accept(error); }
        }, fail, review.contactId, review.amount);
    }

    static final class Handoff extends Exception {
        final String contactId, amount;
        Handoff(String message, String contactId, String amount) { super(message); this.contactId = contactId; this.amount = amount; }
    }

    private static void chainCall(JSONObject request, Consumer<JSONObject> done, Consumer<Exception> fail, String contactId, String amount) throws Exception {
        String id = "w:" + counter.incrementAndGet();
        synchronized (pending) { pending.put(id, done); }
        boolean started;
        try { started = MainActivity.watchChainCall(id, request); }
        catch (Exception error) { synchronized (pending) { pending.remove(id); } throw error; }
        if (!started) {
            synchronized (pending) { pending.remove(id); }
            throw new Handoff("Open Parano1d on your phone to send.", contactId, amount);
        }
        // Long node work can take a while; give up after 2 minutes so the watch isn't left waiting.
        main.postDelayed(() -> {
            Consumer<JSONObject> late; synchronized (pending) { late = pending.remove(id); }
            if (late != null) fail.accept(new Exception("The phone is still working. Check the phone app."));
        }, 120_000);
    }

    /** MainActivity.reply() routes ids starting with "w:" here. */
    static void deliver(String id, JSONObject response) {
        Consumer<JSONObject> callback; synchronized (pending) { callback = pending.remove(id); }
        if (callback != null) MainActivity.onWorker(() -> callback.accept(response));
    }

    // ------------------------------------------------------------------ Confirm phone sends on the watch

    private static final Map<String, Consumer<Exception>> confirmations = new HashMap<>();

    /** Asks the watch to approve a phone-initiated send. done(null) means approved. */
    static void confirmOnWatch(Context context, JSONObject review, Consumer<Exception> done) {
        final Context c = context.getApplicationContext();
        new Thread(() -> {
            try {
                Set<Node> nodes = watchNodes(c);
                if (nodes.isEmpty()) { done.accept(new Exception("No watch is reachable. Keep Parano1d on your watch nearby, or turn off Confirm phone sends on watch.")); return; }
                String confirmId = WatchPolicy.randomHex(12);
                synchronized (confirmations) { confirmations.put(confirmId, done); }
                JSONObject event = new JSONObject().put("type", "confirm").put("confirm_id", confirmId)
                    .put("to", review.optString("to")).put("to_name", nameForAddress(review.optString("to")))
                    .put("amount", review.optString("amount")).put("fee", review.optString("fee")).put("total", review.optString("total"))
                    .put("expires_seconds", WatchPolicy.CONFIRM_MS / 1000);
                byte[] bytes = event.toString().getBytes(StandardCharsets.UTF_8);
                for (Node node : nodes) Wearable.getMessageClient(c).sendMessage(node.getId(), "/p1/event", bytes);
                main.postDelayed(() -> {
                    Consumer<Exception> late; synchronized (confirmations) { late = confirmations.remove(confirmId); }
                    if (late != null) late.accept(new Exception("The watch did not confirm within 60 seconds. Nothing was signed."));
                }, WatchPolicy.CONFIRM_MS);
            } catch (Exception error) { done.accept(error); }
        }).start();
    }

    private static void confirmReply(String confirmId, boolean approved) {
        Consumer<Exception> done; synchronized (confirmations) { done = confirmations.remove(confirmId); }
        if (done == null) return;
        done.accept(approved ? null : new Exception("Declined on your watch. Nothing was signed."));
    }

    // ------------------------------------------------------------------ State publishing

    /** Called from MainActivity.publishView() on the state worker. Cheap when nothing changed. */
    static void publish(Context context) {
        try {
            JSONObject data = MainActivity.watchData();
            Context c = context.getApplicationContext();
            JSONObject state = state(c, data);
            String body = state.toString();
            if (body.equals(lastPublished)) return;
            lastPublished = body;
            announceReceipts(c, data);
            PutDataMapRequest map = PutDataMapRequest.create("/p1/state");
            map.getDataMap().putString("json", body);
            map.getDataMap().putLong("published_ms", System.currentTimeMillis());
            PutDataRequest put = map.asPutDataRequest().setUrgent();
            Wearable.getDataClient(c).putDataItem(put);
        } catch (Exception ignored) { }
    }

    private static JSONObject state(Context c, JSONObject data) throws Exception {
        JSONObject chain = data.optJSONObject("chain"); if (chain == null) chain = new JSONObject();
        boolean hide = WatchPolicy.hideBalances(c);
        JSONObject state = new JSONObject().put("v", 1).put("has_wallet", data.has("seed_hex"))
            .put("hidden", hide)
            .put("balance", hide || chain.optString("balance").isEmpty() ? JSONObject.NULL : chain.optString("balance"))
            .put("height", chain.optLong("balance_height", chain.optLong("height")))
            .put("checked_at_ms", chain.optLong("checked_at_ms"))
            .put("online", data.optJSONObject("connection") != null && data.getJSONObject("connection").optBoolean("online"))
            .put("address", data.optJSONObject("profile") == null ? "" : data.getJSONObject("profile").optString("address"))
            .put("settings", WatchPolicy.settings(c))
            // The watch follows the phone's appearance, like the Apple Watch.
            .put("style", watchStyle(data))
            // The same NOID/USD price the phone shows (fresh only); null when there is none. No source is sent.
            .put("price_usd", AssetIndex.freshUsd() == null ? JSONObject.NULL : AssetIndex.freshUsd())
            .put("nfts", new JSONArray());
        JSONArray contacts = new JSONArray(), source = data.optJSONArray("contacts");
        if (source != null) for (int i = 0; i < source.length() && contacts.length() < 200; i++) {
            JSONObject contact = source.getJSONObject(i);
            String address = contact.optString("address");
            String pinned = WatchPolicy.pinnedAddress(c, contact.getString("id"));
            contacts.put(new JSONObject().put("id", contact.getString("id")).put("name", contact.optString("name", "Contact"))
                .put("address", address).put("address_changed", pinned != null && !pinned.equals(address)));
        }
        state.put("contacts", contacts);
        state.put("receipts", receipts(c, data, 30));
        // Unread counts only; message text stays on the phone until the watch opens a PIN session.
        int unread = 0, requests = 0;
        JSONObject read = data.optJSONObject("read_messages"); JSONArray messages = data.optJSONArray("messages");
        String own = data.optJSONObject("profile") == null ? "" : data.getJSONObject("profile").optString("id");
        if (messages != null && read != null) {
            Map<String, Boolean> seenMarker = new HashMap<>();
            for (int i = messages.length() - 1; i >= 0 && i >= messages.length() - 500; i--) {
                JSONObject m = messages.getJSONObject(i); String scope = m.getString("chat");
                if (scope.equals(COMMUNITY)) continue;
                if (m.getString("id").equals(read.optString(scope))) seenMarker.put(scope, true);
                if (Boolean.TRUE.equals(seenMarker.get(scope)) || m.getJSONObject("sender").optString("id").equals(own)) continue;
                unread++;
                if (m.getJSONObject("message").optString("type").equals("invoice") && !WatchPolicy.declined(c, m.getString("id"))) requests++;
            }
        }
        return state.put("unread", unread).put("open_requests", requests);
    }

    private static JSONArray receipts(Context c, JSONObject data, int limit) throws Exception {
        JSONArray out = new JSONArray();
        JSONArray receipts = data.optJSONArray("receipts"), prepared = data.optJSONArray("prepared_payments");
        if (receipts == null) return out;
        Map<String, JSONObject> sent = new HashMap<>();
        if (prepared != null) for (int i = 0; i < prepared.length(); i++) sent.put(prepared.getJSONObject(i).optString("txid"), prepared.getJSONObject(i));
        for (int i = receipts.length() - 1; i >= 0 && out.length() < limit; i--) {
            JSONObject r = receipts.getJSONObject(i);
            if (!r.optBoolean("verified")) continue; // only finalized, phone-verified payments
            JSONObject mine = sent.get(r.optString("txid"));
            out.put(new JSONObject().put("key", "tx:" + r.optString("txid")).put("direction", mine != null ? "sent" : "received")
                .put("amount", mine != null ? mine.optString("amount") : r.optString("amount"))
                .put("counterparty", mine != null ? nameForAddress(mine.optString("to")) : "")
                .put("txid", r.optString("txid")).put("height", r.optLong("height")));
        }
        return out;
    }

    /** Sends a watch notification for each newly verified receipt and for verified balance increases. */
    private static void announceReceipts(Context c, JSONObject data) throws Exception {
        JSONArray recent = receipts(c, data, 10);
        Set<Node> nodes = null;
        for (int i = recent.length() - 1; i >= 0; i--) {
            JSONObject r = recent.getJSONObject(i);
            if (WatchPolicy.receiptAnnounced(c, r.getString("key"))) continue;
            WatchPolicy.markReceiptAnnounced(c, r.getString("key"));
            if (nodes == null) nodes = watchNodesQuiet(c);
            sendEvent(c, nodes, new JSONObject(r.toString()).put("type", "receipt"));
        }
        JSONObject chain = data.optJSONObject("chain"); if (chain == null) return;
        String balance = chain.optString("balance"); long height = chain.optLong("balance_height", chain.optLong("height"));
        if (balance.isEmpty() || height <= 0) return;
        String previous = WatchPolicy.lastBalance(c); long previousHeight = WatchPolicy.lastBalanceHeight(c);
        WatchPolicy.recordBalance(c, balance, height);
        if (previous == null || height <= previousHeight) return;
        try {
            BigDecimal delta = new BigDecimal(balance).subtract(new BigDecimal(previous));
            if (delta.signum() > 0) {
                if (nodes == null) nodes = watchNodesQuiet(c);
                sendEvent(c, nodes, new JSONObject().put("type", "receipt").put("key", "balance:" + height).put("direction", "received")
                    .put("amount", delta.toPlainString()).put("height", height).put("counterparty", ""));
            }
        } catch (NumberFormatException ignored) { }
    }

    /** Like the iPhone's "chat_update"/"nft_update" pulses: an open watch screen refreshes at once. IDs only, no text. */
    static void chatUpdate(Context context, String chat) { pulse(context, new JSONObject(), "chat_update", chat); }
    static void nftUpdate(Context context) { pulse(context, new JSONObject(), "nft_update", null); }
    private static void pulse(Context context, JSONObject event, String type, String chat) {
        final Context c = context.getApplicationContext();
        new Thread(() -> {
            try { event.put("type", type); if (chat != null) event.put("chat", chat); sendEvent(c, watchNodesQuiet(c), event); } catch (Exception ignored) { }
        }).start();
    }

    /** True once a watch with Parano1d has been seen, so phone message alerts are not mirrored twice. */
    static volatile boolean watchAppSeen;

    /** Tells the watch app a private message arrived, so tapping its alert opens that conversation. No message text is sent. */
    static void messageEvent(Context context, String chat, String name) {
        final Context c = context.getApplicationContext();
        new Thread(() -> {
            try {
                Set<Node> nodes = watchNodesQuiet(c);
                watchAppSeen = !nodes.isEmpty();
                sendEvent(c, nodes, new JSONObject().put("type", "message").put("chat", chat).put("name", name));
            } catch (Exception ignored) { }
        }).start();
    }

    private static void sendEvent(Context c, Set<Node> nodes, JSONObject event) {
        byte[] bytes = event.toString().getBytes(StandardCharsets.UTF_8);
        for (Node node : nodes) Wearable.getMessageClient(c).sendMessage(node.getId(), "/p1/event", bytes);
    }

    // ------------------------------------------------------------------ Chats (session only)

    /** Verified NFT holdings for the watch gallery: names and small thumbnails only (hash-checked on the phone). */
    private static JSONObject nfts(Context c, JSONObject data) throws Exception {
        java.io.File files = c.getFilesDir();
        JSONObject assets = AssetIndex.assets(new java.io.File(files, "artifacts"), new java.io.File(files, "chain"), data.getJSONObject("profile").getString("address"));
        JSONArray out = new JSONArray();
        if (!assets.optBoolean("installed")) return new JSONObject().put("items", out).put("note", "Open the NFTs tab on your phone once to load your NFTs.");
        JSONObject holdings = assets.getJSONObject("holdings");
        // Store edition: artwork and names load only after the person chose "Show artwork" on the phone (like iOS).
        JSONObject prefs = data.optJSONObject("prefs");
        boolean showMedia = prefs == null || prefs.optBoolean("external_media", true); // on by default in every edition; a saved "off" stays off
        java.util.Map<String, String> names = new java.util.HashMap<>();
        JSONArray collections = holdings.optJSONArray("collections");
        if (collections != null && showMedia) for (int i = 0; i < collections.length(); i++) {
            JSONObject col = collections.getJSONObject(i);
            try { names.put(col.getString("collection_id"), AssetIndex.media(c, col.getString("metadata_hash"), false).optString("name")); } catch (Exception ignored) { }
        }
        java.util.Set<String> locked = new java.util.HashSet<>();
        try {
            JSONArray entries = AssetSend.pending(data, files).getJSONArray("entries");
            for (int i = 0; i < entries.length(); i++) { JSONObject e = entries.getJSONObject(i), r = e.optJSONObject("request");
                if (r != null && ("authorized".equals(e.optString("state")) || "preparing".equals(e.optString("state")))) locked.add(r.optString("collection_id") + ":" + r.optString("serial")); }
        } catch (Exception ignored) { }
        JSONArray items = holdings.getJSONArray("items");
        for (int i = 0; i < items.length() && out.length() < 24; i++) {
            JSONObject n = items.getJSONObject(i);
            if (!"nft".equals(n.optString("type"))) continue;
            JSONObject row = new JSONObject().put("serial", n.optString("serial")).put("collection", names.getOrDefault(n.optString("collection_id"), "Collection"))
                .put("name", "#" + n.optString("serial")).put("pending", locked.contains(n.optString("collection_id") + ":" + n.optString("serial")));
            if (showMedia) try {
                JSONObject media = AssetIndex.media(c, n.getString("metadata_hash"), false);
                row.put("name", media.optString("name", row.getString("name")));
                String thumb = media.optString("thumbnail");
                if (thumb.startsWith("data:image/png;base64,")) row.put("thumb", AssetIndex.shrink(thumb.substring(22), 96));
            } catch (Exception artworkUnavailable) { }
            out.put(row);
        }
        // iOS 177: the last holdings this phone verified, with their block height.
        return new JSONObject().put("items", out).put("height", holdings.optLong("height"));
    }

    private static Boolean store;
    static boolean storeEdition(Context c) {
        if (store == null) try (java.io.InputStream in = c.getAssets().open("edition.js")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int n;
            while ((n = in.read(buffer)) != -1 && out.size() < 4096) out.write(buffer, 0, n);
            store = out.toString("UTF-8").contains("'store'");
        } catch (Exception e) { store = false; }
        return store;
    }

    private static JSONObject chats(Context c, JSONObject data) throws Exception {
        JSONArray list = new JSONArray(), messages = data.getJSONArray("messages");
        Map<String, JSONObject> last = new HashMap<>();
        for (int i = 0; i < messages.length(); i++) last.put(messages.getJSONObject(i).getString("chat"), messages.getJSONObject(i));
        JSONArray contacts = data.getJSONArray("contacts");
        for (int i = 0; i < contacts.length(); i++) {
            JSONObject contact = contacts.getJSONObject(i); String scope = "d:" + contact.getString("id");
            if (MainActivity.blockedSender(contact.getString("id"))) continue;
            JSONObject m = last.get(scope);
            list.put(new JSONObject().put("chat", scope).put("name", contact.optString("name", "Contact"))
                .put("preview", m == null ? "" : preview(m)).put("time", m == null ? 0 : m.optLong("created_ms")));
        }
        JSONObject community = last.get(COMMUNITY);
        return new JSONObject().put("chats", list).put("community", new JSONObject().put("chat", COMMUNITY).put("name", "Community").put("paused", MainActivity.communityPaused())
            .put("preview", community == null ? "" : preview(community)).put("public", true));
    }

    private static JSONObject messages(Context c, JSONObject data, String chat) throws Exception {
        checkChat(data, chat);
        String own = data.getJSONObject("profile").optString("id");
        JSONObject contact = chat.startsWith("d:") ? findContact(data, chat.substring(2)) : null;
        JSONArray all = data.getJSONArray("messages"), out = new JSONArray();
        java.util.ArrayList<JSONObject> selected = new java.util.ArrayList<>();
        for (int i = all.length() - 1; i >= 0 && selected.size() < 40; i--) if (all.getJSONObject(i).getString("chat").equals(chat) && !MainActivity.blockedSender(all.getJSONObject(i).getJSONObject("sender").optString("id"))) selected.add(0, all.getJSONObject(i));
        for (JSONObject m : selected) {
            JSONObject body = m.getJSONObject("message");
            boolean mine = m.getJSONObject("sender").optString("id").equals(own);
            JSONObject item = new JSONObject().put("id", m.getString("id")).put("mine", mine)
                .put("sender", m.getJSONObject("sender").optString("name")).put("type", body.optString("type"))
                .put("text", preview(m)).put("time", m.optLong("created_ms")).put("status", m.optString("status"));
            if (body.optString("type").equals("invoice")) {
                boolean declined = WatchPolicy.declined(c, m.getString("id"));
                boolean payable = !mine && !declined && contact != null && body.optString("address").equals(contact.optString("address"));
                item.put("amount", body.optString("amount")).put("memo", body.optString("memo")).put("declined", declined).put("payable", payable);
            }
            out.put(item);
        }
        if (!selected.isEmpty()) {
            JSONObject latest = selected.get(selected.size() - 1);
            data.getJSONObject("read_messages").put(chat, latest.getString("id"));
        }
        return new JSONObject().put("chat", chat).put("public", chat.equals(COMMUNITY)).put("messages", out);
    }

    private static String preview(JSONObject m) {
        JSONObject body = m.optJSONObject("message"); if (body == null) return "";
        switch (body.optString("type")) {
            case "text": {
                // A shared avatar recipe (same as iOS) shows as a short line, never the recipe.
                String text = body.optString("text");
                return text.startsWith("Parano1d avatar v1: ") ? "Shared a selected avatar." : text;
            }
            case "invoice": return "Payment request · " + body.optString("amount") + " NOID";
            case "receipt": return "Payment receipt";
            case "transaction": return "Approved NOID payment";
            default: return "";
        }
    }

    // ------------------------------------------------------------------ Helpers

    private static void requireIdentity(JSONObject data) throws Exception { if (!data.has("seed_hex")) throw new Exception("Set up your wallet on the phone first."); }

    private static void checkChat(JSONObject data, String chat) throws Exception {
        if (chat.equals(COMMUNITY)) {
            if (MainActivity.communityPaused()) throw new Exception("Private-only chat is on, so the public room is off. Private chats still work. To turn it off: on your phone, Settings → Chat → Private-only chat.");
            return;
        }
        if (!chat.matches("d:[0-9a-f]{64}")) throw new Exception("Choose a conversation.");
        findContact(data, chat.substring(2));
    }

    private static JSONObject findContact(JSONObject data, String id) throws Exception {
        JSONArray contacts = data.getJSONArray("contacts");
        for (int i = 0; i < contacts.length(); i++) if (contacts.getJSONObject(i).getString("id").equals(id)) return contacts.getJSONObject(i);
        throw new Exception("Contact not found on the phone.");
    }

    private static JSONObject findMessage(JSONObject data, String id) throws Exception {
        JSONArray messages = data.getJSONArray("messages");
        for (int i = messages.length() - 1; i >= 0; i--) if (messages.getJSONObject(i).getString("id").equals(id)) return messages.getJSONObject(i);
        throw new Exception("Message not found on the phone.");
    }

    private static String nameForAddress(String address) {
        try {
            JSONArray contacts = MainActivity.watchData().getJSONArray("contacts");
            for (int i = 0; i < contacts.length(); i++) if (contacts.getJSONObject(i).optString("address").equals(address)) return contacts.getJSONObject(i).optString("name", "Contact");
        } catch (Exception ignored) { }
        return "";
    }

    private static Set<Node> watchNodes(Context c) throws Exception {
        CapabilityInfo info = Tasks.await(Wearable.getCapabilityClient(c).getCapability(WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE), 10, TimeUnit.SECONDS);
        return new HashSet<>(info.getNodes());
    }

    private static Set<Node> watchNodesQuiet(Context c) {
        try { return watchNodes(c); } catch (Exception unavailable) { return new HashSet<>(); }
    }

    private static void respond(Context c, String node, String id, JSONObject result, Exception error) {
        try {
            JSONObject response = new JSONObject().put("id", id).put("ok", error == null);
            if (error == null) response.put("result", result);
            else {
                response.put("error", error.getMessage() == null ? "Operation failed" : error.getMessage());
                if (error instanceof SessionExpired) response.put("code", "session_expired");
                if (error instanceof Handoff) {
                    response.put("code", "handoff").put("contact_id", ((Handoff) error).contactId).put("amount", ((Handoff) error).amount);
                }
            }
            Wearable.getMessageClient(c).sendMessage(node, "/p1/rpc-result", response.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** "Continue on phone": a notification that opens the conversation with this contact. */
    static void postHandoff(Context c, String contactId, String amount, String reason) {
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel("watch") == null) manager.createNotificationChannel(new NotificationChannel("watch", "Watch hand-off", NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (contactId != null && contactId.matches("[0-9a-f]{64}")) open.putExtra("open_chat", "d:" + contactId);
        if (amount != null && amount.matches("\\d+(\\.\\d{1,6})?")) open.putExtra("send_amount", amount); // opens Send NOID prefilled
        PendingIntent intent = PendingIntent.getActivity(c, 7101, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = (amount == null || amount.isEmpty() ? "" : amount + " NOID · ") + reason;
        manager.notify(7101, new android.app.Notification.Builder(c, "watch").setSmallIcon(R.drawable.ic_message)
            .setContentTitle("Finish payment on phone").setContentText(text).setStyle(new android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(intent).setAutoCancel(true).setLocalOnly(true).build());
    }

    /** The phone's appearance for the watch, as the iPhone sends it: "world", "cyber" or "system", plus "+orange"
     *  while private-only chat (Paranoid mode) is on. */
    static String watchStyle(JSONObject data) {
        String theme = data.optJSONObject("settings") == null ? "" : data.optJSONObject("settings").optString("theme");
        String base = "world".equals(theme) || "cyber".equals(theme) ? theme : "system";
        boolean orange = data.optBoolean("paranoid_mode"); // Parano1d mode (Peers route), as on iOS 173
        return orange ? base + "+orange" : base;
    }
}
