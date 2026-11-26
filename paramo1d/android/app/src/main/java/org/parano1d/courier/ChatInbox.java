package org.parano1d.courier;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Audit fixes 177a, 177b, R1, R2 and R3 (same rules as the iOS build that passed its audit tests).
 * Pure functions over the saved JSON, so they can be unit tested; MainActivity does the storage.
 *
 * - One inbound gate before anything is saved: blocked senders are dropped, accepted senders go to
 *   chat, anyone else goes to Message requests (20 per sender, 200 in total).
 * - Saved history is never moved into Requests and never cut; everyone already in it is accepted.
 * - History never jams: a conversation over 500 drops to 400, over 5,000 in total drops to 4,000,
 *   over 12 MB drops to about 9.6 MB. The oldest messages move to "Older messages" (20,000 / 64 MB).
 * - Deleted, dropped and rolled-out IDs are remembered for 8 days (at most 20,000; oldest first out).
 * - The Nearby carry queue is fair: 128 in total, 16 per sender, other people's packets at most 96,
 *   your own packets always fit. A packet is refused only when the queue is entirely your own.
 */
final class ChatInbox {
    static final String DROP = "drop", ACCEPT = "accept", REQUEST = "request";
    static final int CONVERSATION_LIMIT = 500, TOTAL_LIMIT = 5000;
    static final long BYTE_LIMIT = 12L * 1024 * 1024;
    static final int OLDER_LIMIT = 20000;
    static final long OLDER_BYTES = 64L * 1024 * 1024;
    static final int REQUESTS_PER_SENDER = 20, REQUESTS_TOTAL = 200;
    static final long REQUEST_BYTES = 8L * 1024 * 1024;
    static final long TOMBSTONE_MS = 8L * 24 * 60 * 60 * 1000;
    static final int TOMBSTONE_LIMIT = 20000;
    static final int QUEUE_TOTAL = 128, QUEUE_PER_SENDER = 16, QUEUE_OTHERS = 96;
    static final int CHAT_VERSION = 2;

    private ChatInbox() { }

    static JSONArray array(JSONObject object, String key) throws Exception {
        JSONArray value = object.optJSONArray(key);
        if (value == null) { value = new JSONArray(); object.put(key, value); }
        return value;
    }
    static long bytes(Object value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }
    static String ownId(JSONObject data) {
        JSONObject profile = data.optJSONObject("profile");
        return profile == null ? "" : profile.optString("id");
    }
    static String senderOf(JSONObject message) {
        JSONObject sender = message.optJSONObject("sender");
        return sender == null ? "" : sender.optString("id");
    }
    /** The signed sender of an envelope (visible without opening it). */
    static String envelopeSender(JSONObject envelope) {
        JSONObject data = envelope.optJSONObject("data");
        JSONObject sender = data == null ? null : data.optJSONObject("sender");
        return sender == null ? "" : sender.optString("id");
    }

    // ---- Upgrade -------------------------------------------------------------------------------

    /** Version 1 to 2. Idempotent: running it twice changes nothing. Keeps history, read state, order, cursors. */
    static boolean migrate(JSONObject data, long now) throws Exception {
        if (data.optInt("chat_version") >= CHAT_VERSION) return false;
        String own = ownId(data);
        Set<String> accepted = new LinkedHashSet<>();
        JSONArray previous = data.optJSONArray("accepted_senders");
        if (previous != null) for (int i = 0; i < previous.length(); i++) accepted.add(previous.optString(i));
        JSONArray contacts = array(data, "contacts");
        for (int i = 0; i < contacts.length(); i++) accepted.add(contacts.getJSONObject(i).optString("id"));
        // Everyone already in the saved history is accepted (contact or not), so nothing saved is ever demoted.
        JSONArray messages = array(data, "messages");
        for (int i = 0; i < messages.length(); i++) {
            String chat = messages.getJSONObject(i).optString("chat");
            if (chat.startsWith("d:")) accepted.add(chat.substring(2));
        }
        accepted.remove(own); accepted.remove("");
        data.put("accepted_senders", new JSONArray(accepted));
        // Existing cleared IDs carry over with an 8-day expiry.
        JSONObject cleared = data.optJSONObject("cleared_messages"), next = new JSONObject();
        if (cleared != null) for (Iterator<String> it = cleared.keys(); it.hasNext();) next.put(it.next(), now + TOMBSTONE_MS);
        data.put("cleared_messages", next);
        pruneTombstones(data, now);
        data.put("chat_version", CHAT_VERSION);
        return true;
    }

    // ---- Inbound gate --------------------------------------------------------------------------

    static boolean blocked(JSONObject data, String sender) {
        JSONObject safety = data.optJSONObject("chat_safety");
        JSONArray list = safety == null ? null : safety.optJSONArray("blocked");
        if (list != null) for (int i = 0; i < list.length(); i++) if (sender.equals(list.optString(i))) return true;
        return false;
    }
    static boolean isContact(JSONObject data, String sender) {
        JSONArray contacts = data.optJSONArray("contacts");
        if (contacts != null) for (int i = 0; i < contacts.length(); i++) if (sender.equals(contacts.optJSONObject(i).optString("id"))) return true;
        return false;
    }
    static boolean accepted(JSONObject data, String sender) {
        if (isContact(data, sender)) return true;
        JSONArray list = data.optJSONArray("accepted_senders");
        if (list != null) for (int i = 0; i < list.length(); i++) if (sender.equals(list.optString(i))) return true;
        // Someone you have sent a message to.
        String own = ownId(data); JSONArray messages = data.optJSONArray("messages");
        if (messages != null) for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null && ("d:" + sender).equals(message.optString("chat")) && own.equals(senderOf(message))) return true;
        }
        return false;
    }
    /** The one gate every incoming private message goes through before anything is saved. */
    static String decide(JSONObject data, String sender) {
        if (!sender.matches("[0-9a-f]{64}")) return DROP;
        if (blocked(data, sender)) return DROP;
        return accepted(data, sender) ? ACCEPT : REQUEST;
    }
    static void accept(JSONObject data, String sender) throws Exception {
        if (sender.isEmpty() || sender.equals(ownId(data))) return;
        JSONArray list = array(data, "accepted_senders");
        for (int i = 0; i < list.length(); i++) if (sender.equals(list.optString(i))) return;
        list.put(sender);
    }

    // ---- Tombstones (R2) -----------------------------------------------------------------------

    static void tombstone(JSONObject data, String id, long now) throws Exception {
        JSONObject cleared = data.optJSONObject("cleared_messages");
        if (cleared == null) { cleared = new JSONObject(); data.put("cleared_messages", cleared); }
        cleared.put(id, Math.max(cleared.optLong(id), now + TOMBSTONE_MS));
        pruneTombstones(data, now);
    }
    /** Drops expired IDs; past the safety limit the soonest-expiring go first. Never fails. */
    static void pruneTombstones(JSONObject data, long now) throws Exception {
        JSONObject cleared = data.optJSONObject("cleared_messages");
        if (cleared == null) return;
        List<Map.Entry<String, Long>> live = new ArrayList<>();
        for (Iterator<String> it = cleared.keys(); it.hasNext();) { String id = it.next(); long until = cleared.optLong(id); if (until > now) live.add(new java.util.AbstractMap.SimpleEntry<>(id, until)); }
        if (live.size() == cleared.length() && live.size() <= TOMBSTONE_LIMIT) return;
        live.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        JSONObject next = new JSONObject();
        for (int i = 0; i < Math.min(TOMBSTONE_LIMIT, live.size()); i++) next.put(live.get(i).getKey(), live.get(i).getValue());
        data.put("cleared_messages", next);
    }
    static boolean tombstoned(JSONObject data, String id, long now) {
        JSONObject cleared = data.optJSONObject("cleared_messages");
        return cleared != null && cleared.optLong(id) > now;
    }

    // ---- History rollover (R1) -----------------------------------------------------------------

    /**
     * Moves the oldest messages out of the main history when a limit is passed (in batches, down to 80%).
     * Messages still waiting in the outbox stay. Returns the moved messages, oldest first, and remembers
     * their IDs so a relay or a nearby phone can't bring them back as new.
     */
    static JSONArray rollover(JSONObject data, Set<String> queued, long now) throws Exception {
        JSONArray messages = array(data, "messages");
        int total = messages.length();
        Map<String, Integer> perChat = new HashMap<>();
        long size = 0;
        for (int i = 0; i < total; i++) { JSONObject m = messages.getJSONObject(i); perChat.merge(m.optString("chat"), 1, Integer::sum); size += bytes(m); }
        boolean over = total > TOTAL_LIMIT || size > BYTE_LIMIT;
        for (int count : perChat.values()) over |= count > CONVERSATION_LIMIT;
        if (!over) return new JSONArray();
        boolean[] move = new boolean[total];
        // 1. Each conversation over 500 drops to 400.
        Map<String, Integer> excess = new HashMap<>();
        for (Map.Entry<String, Integer> e : perChat.entrySet()) if (e.getValue() > CONVERSATION_LIMIT) excess.put(e.getKey(), e.getValue() - CONVERSATION_LIMIT * 4 / 5);
        int remaining = total; long remainingBytes = size;
        for (int i = 0; i < total; i++) {
            JSONObject m = messages.getJSONObject(i); String chat = m.optString("chat");
            Integer left = excess.get(chat);
            if (left != null && left > 0 && !queued.contains(m.optString("id"))) { move[i] = true; excess.put(chat, left - 1); remaining--; remainingBytes -= bytes(m); }
        }
        // 2. Over 5,000 in total drops to 4,000; 3. over 12 MB drops to about 9.6 MB.
        boolean totalOver = remaining > TOTAL_LIMIT, bytesOver = remainingBytes > BYTE_LIMIT;
        for (int i = 0; i < total && (totalOver && remaining > TOTAL_LIMIT * 4 / 5 || bytesOver && remainingBytes > BYTE_LIMIT * 4 / 5); i++) {
            if (move[i]) continue;
            JSONObject m = messages.getJSONObject(i);
            if (queued.contains(m.optString("id"))) continue;
            move[i] = true; remaining--; remainingBytes -= bytes(m);
        }
        JSONArray kept = new JSONArray(), moved = new JSONArray();
        for (int i = 0; i < total; i++) {
            JSONObject m = messages.getJSONObject(i);
            if (move[i]) { moved.put(m); tombstone(data, m.getString("id"), now); } else kept.put(m);
        }
        data.put("messages", kept);
        return moved;
    }

    /** Adds rolled-out messages to the Older messages store. Past 20,000 or 64 MB the oldest older messages go. */
    static void addOlder(JSONObject older, JSONArray moved) throws Exception {
        JSONArray list = array(older, "messages"), next = new JSONArray();
        Set<String> seen = new HashSet<>();
        List<JSONObject> all = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) all.add(list.getJSONObject(i));
        for (int i = 0; i < moved.length(); i++) all.add(moved.getJSONObject(i));
        List<JSONObject> unique = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) if (seen.add(all.get(i).optString("id"))) unique.add(0, all.get(i));
        long size = 0; for (JSONObject m : unique) size += bytes(m);
        int start = 0;
        while (unique.size() - start > OLDER_LIMIT || size > OLDER_BYTES && start < unique.size()) { size -= bytes(unique.get(start)); start++; }
        for (int i = start; i < unique.size(); i++) next.put(unique.get(i));
        older.put("messages", next);
    }
    /** One conversation's older messages, without duplicates of the main history. */
    static JSONArray olderFor(JSONObject older, JSONObject data, String chat) throws Exception {
        Set<String> main = new HashSet<>();
        JSONArray messages = array(data, "messages");
        for (int i = 0; i < messages.length(); i++) main.add(messages.getJSONObject(i).optString("id"));
        JSONArray list = older.optJSONArray("messages"), result = new JSONArray();
        Set<String> seen = new HashSet<>();
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject m = list.getJSONObject(i); String id = m.optString("id");
            if (chat.equals(m.optString("chat")) && !main.contains(id) && seen.add(id)) result.put(m);
        }
        return result;
    }
    /** Per-conversation counts for "Show older messages (n)", without duplicates of the main history. */
    static JSONObject olderCounts(JSONObject older, JSONObject data) throws Exception {
        Set<String> main = new HashSet<>(), seen = new HashSet<>();
        JSONArray messages = array(data, "messages");
        for (int i = 0; i < messages.length(); i++) main.add(messages.getJSONObject(i).optString("id"));
        JSONObject counts = new JSONObject();
        JSONArray list = older.optJSONArray("messages");
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject m = list.getJSONObject(i); String id = m.optString("id");
            if (!main.contains(id) && seen.add(id)) counts.put(m.optString("chat"), counts.optInt(m.optString("chat")) + 1);
        }
        return counts;
    }
    /** Removes older messages by ID, or a whole conversation (chat != null). Returns the removed IDs. */
    static Set<String> removeOlder(JSONObject older, Set<String> ids, String chat) throws Exception {
        JSONArray list = array(older, "messages"), next = new JSONArray();
        Set<String> removed = new HashSet<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject m = list.getJSONObject(i);
            if (ids.contains(m.optString("id")) || chat != null && chat.equals(m.optString("chat"))) removed.add(m.optString("id"));
            else next.put(m);
        }
        older.put("messages", next);
        return removed;
    }

    // ---- Message requests (177b) ---------------------------------------------------------------

    /** Adds a request. Duplicates are merged; past 20 per sender, 200 in total or 8 MB the oldest is dropped. */
    static boolean addRequest(JSONObject requests, JSONObject message) throws Exception {
        JSONArray list = array(requests, "messages");
        String id = message.optString("id");
        for (int i = 0; i < list.length(); i++) if (id.equals(list.getJSONObject(i).optString("id"))) return false;
        list.put(message);
        normalizeRequests(requests);
        return true;
    }
    /** Merges duplicates (for example after a crash between two writes) and applies the limits. */
    static void normalizeRequests(JSONObject requests) throws Exception {
        JSONArray list = array(requests, "messages");
        List<JSONObject> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.length(); i++) { JSONObject m = list.optJSONObject(i); if (m != null && seen.add(m.optString("id"))) items.add(m); }
        items.sort((a, b) -> Long.compare(a.optLong("created_ms"), b.optLong("created_ms")));
        Map<String, Integer> perSender = new HashMap<>();
        for (JSONObject m : items) perSender.merge(senderOf(m), 1, Integer::sum);
        List<JSONObject> kept = new ArrayList<>();
        for (JSONObject m : items) {
            String s = senderOf(m); int n = perSender.get(s);
            if (n > REQUESTS_PER_SENDER) { perSender.put(s, n - 1); continue; } // oldest of that sender makes way
            kept.add(m);
        }
        long size = 0; for (JSONObject m : kept) size += bytes(m);
        int start = 0;
        while (kept.size() - start > REQUESTS_TOTAL || size > REQUEST_BYTES && start < kept.size()) { size -= bytes(kept.get(start)); start++; }
        JSONArray next = new JSONArray();
        for (int i = start; i < kept.size(); i++) next.put(kept.get(i));
        requests.put("messages", next);
    }
    /** "Message requests (n)": one row per sender, newest first. Names are as claimed by the sender. */
    static JSONArray requestSummary(JSONObject requests) throws Exception {
        Map<String, JSONObject> rows = new LinkedHashMap<>();
        JSONArray list = array(requests, "messages");
        for (int i = 0; i < list.length(); i++) {
            JSONObject m = list.getJSONObject(i); String s = senderOf(m);
            JSONObject row = rows.get(s);
            if (row == null) { row = new JSONObject().put("id", s).put("short", s.length() > 12 ? s.substring(0, 6) + "…" + s.substring(s.length() - 4) : s).put("count", 0).put("last_ms", 0L); rows.put(s, row); }
            String name = m.optJSONObject("sender") == null ? "" : m.getJSONObject("sender").optString("name");
            row.put("name", name.isEmpty() ? "Unknown sender" : name).put("count", row.getInt("count") + 1).put("last_ms", Math.max(row.getLong("last_ms"), m.optLong("created_ms")));
        }
        List<JSONObject> sorted = new ArrayList<>(rows.values());
        sorted.sort((a, b) -> Long.compare(b.optLong("last_ms"), a.optLong("last_ms")));
        return new JSONArray(sorted);
    }
    /** One sender's requests, text only. Payments and anything that isn't plain text stay closed. */
    static JSONArray requestMessages(JSONObject requests, String sender) throws Exception {
        JSONArray list = array(requests, "messages"), result = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject m = list.getJSONObject(i);
            if (!sender.equals(senderOf(m))) continue;
            JSONObject message = m.optJSONObject("message"); String type = message == null ? "" : message.optString("type");
            JSONObject shown = new JSONObject().put("id", m.optString("id")).put("created_ms", m.optLong("created_ms"));
            if (type.equals("text")) {
                String text = message.optString("text");
                if (text.length() > 4000) text = text.substring(0, 4000) + "…";
                shown.put("text", text);
            } else if (type.equals("transaction") || type.equals("invoice") || type.equals("receipt") || type.equals("payment_request") || message != null && message.has("intent_hex")) {
                shown.put("hidden", "Contains a payment or payment request. Add this person as a contact to view it.");
            } else shown.put("hidden", "Message hidden: this kind of message is only shown from contacts.");
            result.put(shown);
        }
        return result;
    }
    /** Removes and returns one sender's requests, oldest first. */
    static JSONArray takeRequests(JSONObject requests, String sender) throws Exception {
        JSONArray list = array(requests, "messages"), kept = new JSONArray(), taken = new JSONArray();
        for (int i = 0; i < list.length(); i++) { JSONObject m = list.getJSONObject(i); if (sender.equals(senderOf(m))) taken.put(m); else kept.put(m); }
        requests.put("messages", kept);
        return taken;
    }

    // ---- Fair Nearby carry queue (R3) ----------------------------------------------------------

    /**
     * Adds a packet to a carry queue fairly. When there's no room, the oldest packet of the busiest other
     * sender makes way; your own packets are never evicted. Returns the new queue (oldest first), or null
     * only when the queue is entirely your own packets and has no room.
     */
    static List<JSONObject> fairAdd(List<JSONObject> queue, JSONObject incoming, String own, long byteLimit, long now) {
        List<JSONObject> items = new ArrayList<>();
        String id = incoming.optString("id");
        for (JSONObject item : queue) {
            JSONObject data = item.optJSONObject("data");
            if (id.equals(item.optString("id")) || data != null && data.optLong("expires_ms", Long.MAX_VALUE) <= now) continue;
            items.add(item);
        }
        items.add(incoming);
        items.sort((a, b) -> Long.compare(created(a), created(b)));
        long size = 0; for (JSONObject item : items) size += bytes(item);
        while (true) {
            Map<String, Integer> counts = new HashMap<>(); int others = 0;
            for (JSONObject item : items) { String s = envelopeSender(item); counts.merge(s, 1, Integer::sum); if (!s.equals(own)) others++; }
            String incomingSender = envelopeSender(incoming);
            String evictFrom = null;
            if (!incomingSender.equals(own) && counts.getOrDefault(incomingSender, 0) > QUEUE_PER_SENDER) evictFrom = incomingSender;
            else if (items.size() > QUEUE_TOTAL || others > QUEUE_OTHERS || size > byteLimit) {
                int busiest = 0;
                for (Map.Entry<String, Integer> e : counts.entrySet()) if (!e.getKey().equals(own) && e.getValue() > busiest) { busiest = e.getValue(); evictFrom = e.getKey(); }
                if (evictFrom == null) return null; // the whole queue is your own packets
            } else return items;
            for (int i = 0; i < items.size(); i++) if (envelopeSender(items.get(i)).equals(evictFrom)) { size -= bytes(items.get(i)); items.remove(i); break; }
        }
    }
    private static long created(JSONObject envelope) {
        JSONObject data = envelope.optJSONObject("data");
        return data == null ? 0 : data.optLong("created_ms");
    }
}
