package org.parano1d.courier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Audit fixes 176 (app side), 177a, 177b, R1, R2, R3: the regression cases. */
public class ChatInboxTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final String OWN = id('a'), CONTACT = id('c'), STRANGER = id('e'), BLOCKED = id('b');

    private static String id(char c) { return String.valueOf(c).repeat(64); }
    private static String msgId(int n) { return String.format("%064x", n); }

    private static JSONObject contact(String id) throws Exception { return new JSONObject().put("id", id).put("name", "N" + id.charAt(0)).put("box_key", "k"); }
    private static JSONObject wallet() throws Exception {
        return new JSONObject().put("profile", contact(OWN)).put("contacts", new JSONArray().put(contact(CONTACT)))
            .put("messages", new JSONArray()).put("outbox", new JSONArray())
            .put("chat_safety", new JSONObject().put("blocked", new JSONArray().put(BLOCKED)).put("order", new JSONArray().put("d:" + CONTACT)))
            .put("read_messages", new JSONObject().put("d:" + CONTACT, msgId(1)))
            .put("cursors", new JSONObject().put("https://relay|inbox", 42));
    }
    private static JSONObject message(int n, String chat, String sender, long created) throws Exception {
        return new JSONObject().put("id", msgId(n)).put("chat", chat).put("sender", contact(sender))
            .put("message", new JSONObject().put("type", "text").put("text", "hello " + n)).put("created_ms", created).put("status", "received");
    }
    private static JSONObject envelope(int n, String sender, int padding) throws Exception {
        return new JSONObject().put("id", msgId(n)).put("data", new JSONObject().put("sender", contact(sender)).put("created_ms", (long) n)
            .put("expires_ms", NOW + 1000).put("pad", "x".repeat(padding)));
    }

    // ---- Blocked senders (177a) ----

    @Test public void blockedSenderIsDroppedAndAResendAfterUnblockingIsIgnored() throws Exception {
        JSONObject data = wallet();
        assertEquals(ChatInbox.DROP, ChatInbox.decide(data, BLOCKED));
        ChatInbox.tombstone(data, msgId(7), NOW); // dropped: ID remembered, the cursor still advances
        data.getJSONObject("chat_safety").put("blocked", new JSONArray());
        assertTrue("a resend of the dropped message is ignored", ChatInbox.tombstoned(data, msgId(7), NOW + 1000));
        assertEquals(0, data.getJSONArray("messages").length());
    }

    @Test public void blockingAContactKeepsHistoryAndDropsNewMessages() throws Exception {
        JSONObject data = wallet();
        data.getJSONArray("messages").put(message(1, "d:" + CONTACT, CONTACT, 1));
        data.getJSONObject("chat_safety").getJSONArray("blocked").put(CONTACT);
        assertEquals(ChatInbox.DROP, ChatInbox.decide(data, CONTACT));
        assertEquals(1, data.getJSONArray("messages").length());
        data.getJSONObject("chat_safety").put("blocked", new JSONArray());
        assertEquals(ChatInbox.ACCEPT, ChatInbox.decide(data, CONTACT));
    }

    @Test public void blockingAStrangerDiscardsTheirRequests() throws Exception {
        JSONObject data = wallet(), requests = new JSONObject();
        ChatInbox.addRequest(requests, message(1, "d:" + STRANGER, STRANGER, 1));
        ChatInbox.addRequest(requests, message(2, "d:" + id('f'), id('f'), 2));
        JSONArray gone = ChatInbox.takeRequests(requests, STRANGER);
        for (int i = 0; i < gone.length(); i++) ChatInbox.tombstone(data, gone.getJSONObject(i).getString("id"), NOW);
        assertEquals(1, ChatInbox.requestSummary(requests).length());
        assertTrue(ChatInbox.tombstoned(data, msgId(1), NOW));
    }

    // ---- Strangers and Message requests (177b) ----

    @Test public void strangerGoesToRequestsAndMovesIntoChatOnceAdded() throws Exception {
        JSONObject data = wallet(), requests = new JSONObject();
        assertEquals(ChatInbox.REQUEST, ChatInbox.decide(data, STRANGER));
        ChatInbox.addRequest(requests, message(1, "d:" + STRANGER, STRANGER, 1).put("status", "request"));
        JSONArray summary = ChatInbox.requestSummary(requests);
        assertEquals(1, summary.length());
        assertEquals(1, summary.getJSONObject(0).getInt("count"));
        assertEquals("requests are not chat messages, so there is no unread count", 0, data.getJSONArray("messages").length());
        data.getJSONArray("contacts").put(contact(STRANGER)); // added from their invitation
        ChatInbox.accept(data, STRANGER);
        JSONArray moved = ChatInbox.takeRequests(requests, STRANGER);
        for (int i = 0; i < moved.length(); i++) data.getJSONArray("messages").put(moved.getJSONObject(i).put("status", "received"));
        assertEquals(1, data.getJSONArray("messages").length());
        assertEquals(0, ChatInbox.requestSummary(requests).length());
        assertEquals(ChatInbox.ACCEPT, ChatInbox.decide(data, STRANGER));
    }

    @Test public void replyingToAStrangerAcceptsThem() throws Exception {
        JSONObject data = wallet();
        data.getJSONArray("messages").put(message(1, "d:" + STRANGER, OWN, 1));
        assertEquals(ChatInbox.ACCEPT, ChatInbox.decide(data, STRANGER));
    }

    @Test public void deletedOrBlockedRequestsIgnoreAResend() throws Exception {
        JSONObject data = wallet(), requests = new JSONObject();
        ChatInbox.addRequest(requests, message(1, "d:" + STRANGER, STRANGER, 1));
        JSONArray taken = ChatInbox.takeRequests(requests, STRANGER);
        ChatInbox.tombstone(data, taken.getJSONObject(0).getString("id"), NOW);
        assertTrue(ChatInbox.tombstoned(data, msgId(1), NOW + 60_000));
    }

    @Test public void floodFromOneStrangerKeepsTwentyAndContactsStillLand() throws Exception {
        JSONObject data = wallet(), requests = new JSONObject();
        long cursor = 0;
        for (int page = 0; page < 20; page++) for (int k = 0; k < 30; k++) {
            int n = page * 30 + k + 1;
            String route = ChatInbox.decide(data, STRANGER);
            assertEquals(ChatInbox.REQUEST, route);
            ChatInbox.addRequest(requests, message(n, "d:" + STRANGER, STRANGER, n));
            cursor = n; // the cursor advances past every delivery
        }
        assertEquals(600, cursor);
        assertEquals(ChatInbox.REQUESTS_PER_SENDER, ChatInbox.requestSummary(requests).getJSONObject(0).getInt("count"));
        assertEquals("the newest are kept", msgId(600), requests.getJSONArray("messages").getJSONObject(19).getString("id"));
        assertEquals(ChatInbox.ACCEPT, ChatInbox.decide(data, CONTACT));
    }

    @Test public void requestsStayWithinTheTotalLimit() throws Exception {
        JSONObject requests = new JSONObject();
        for (int s = 0; s < 30; s++) for (int k = 0; k < 10; k++) {
            String sender = String.format("%064x", 1000 + s);
            ChatInbox.addRequest(requests, message(s * 100 + k, "d:" + sender, sender, s * 100 + k));
        }
        assertEquals(ChatInbox.REQUESTS_TOTAL, requests.getJSONArray("messages").length());
    }

    @Test public void requestsShowTextOnlyAndKeepPaymentsClosed() throws Exception {
        JSONObject requests = new JSONObject();
        ChatInbox.addRequest(requests, message(1, "d:" + STRANGER, STRANGER, 1));
        JSONObject invoice = message(2, "d:" + STRANGER, STRANGER, 2);
        invoice.put("message", new JSONObject().put("type", "invoice").put("amount", "5"));
        ChatInbox.addRequest(requests, invoice);
        JSONArray shown = ChatInbox.requestMessages(requests, STRANGER);
        assertEquals("hello 1", shown.getJSONObject(0).getString("text"));
        assertFalse(shown.getJSONObject(1).has("text"));
        assertTrue(shown.getJSONObject(1).getString("hidden").contains("Add this person as a contact"));
    }

    @Test public void duplicateRequestsAfterACrashAreMergedNotPutAside() throws Exception {
        JSONObject requests = new JSONObject().put("messages", new JSONArray()
            .put(message(1, "d:" + STRANGER, STRANGER, 1)).put(message(1, "d:" + STRANGER, STRANGER, 1)).put(JSONObject.NULL));
        ChatInbox.normalizeRequests(requests);
        assertEquals(1, requests.getJSONArray("messages").length());
        assertFalse(ChatInbox.addRequest(requests, message(1, "d:" + STRANGER, STRANGER, 1)));
    }

    // ---- History never jams (R1, R2) ----

    @Test public void sixHundredMessagesInOneConversationRollOverAndKeepWorking() throws Exception {
        JSONObject data = wallet(), older = new JSONObject();
        for (int n = 1; n <= 600; n++) {
            data.getJSONArray("messages").put(message(n, "d:" + CONTACT, n % 2 == 0 ? OWN : CONTACT, n));
            ChatInbox.addOlder(older, ChatInbox.rollover(data, new HashSet<>(), NOW)); // after every send or receive
        }
        int main = data.getJSONArray("messages").length();
        assertTrue("main <= 500, was " + main, main <= ChatInbox.CONVERSATION_LIMIT);
        assertEquals(600, main + older.getJSONArray("messages").length());
        JSONArray kept = data.getJSONArray("messages");
        assertEquals("the newest is kept", msgId(600), kept.getJSONObject(kept.length() - 1).getString("id"));
        assertEquals(600 - main, ChatInbox.olderFor(older, data, "d:" + CONTACT).length());
        assertTrue("a rolled-out message re-sent over Nearby doesn't come back", ChatInbox.tombstoned(data, msgId(1), NOW));
        // Deleting a single older message works.
        Set<String> removed = ChatInbox.removeOlder(older, new HashSet<>(List.of(msgId(1))), null);
        assertEquals(Set.of(msgId(1)), removed);
        assertEquals(600 - main - 1, ChatInbox.olderFor(older, data, "d:" + CONTACT).length());
    }

    @Test public void queuedMessagesNeverRollOut() throws Exception {
        JSONObject data = wallet();
        for (int n = 1; n <= 510; n++) data.getJSONArray("messages").put(message(n, "d:" + CONTACT, OWN, n));
        JSONArray moved = ChatInbox.rollover(data, Set.of(msgId(1)), NOW);
        for (int i = 0; i < moved.length(); i++) assertFalse(msgId(1).equals(moved.getJSONObject(i).getString("id")));
    }

    @Test public void totalAndByteLimitsRollInBatches() throws Exception {
        JSONObject data = wallet();
        for (int n = 1; n <= 5001; n++) { String peer = String.format("%064x", 5000 + n % 20); data.getJSONArray("messages").put(message(n, "d:" + peer, peer, n)); }
        ChatInbox.rollover(data, new HashSet<>(), NOW);
        assertTrue(data.getJSONArray("messages").length() <= ChatInbox.TOTAL_LIMIT * 4 / 5);
    }

    @Test public void showOlderCountHasNoDuplicatesOfMainHistory() throws Exception {
        JSONObject data = wallet(), older = new JSONObject().put("messages", new JSONArray().put(message(1, "d:" + CONTACT, CONTACT, 1)).put(message(2, "d:" + CONTACT, CONTACT, 2)));
        data.getJSONArray("messages").put(message(2, "d:" + CONTACT, CONTACT, 2)); // crash between the two writes
        assertEquals(1, ChatInbox.olderCounts(older, data).getInt("d:" + CONTACT));
    }

    @Test public void versionOneUpgradeKeepsEverythingAndIsIdempotent() throws Exception {
        JSONObject data = wallet();
        String nonContact = id('9');
        data.getJSONArray("messages").put(message(1, "d:" + CONTACT, CONTACT, 1)).put(message(2, "d:" + nonContact, nonContact, 2));
        JSONObject cleared = new JSONObject();
        for (int n = 0; n < 5000; n++) cleared.put(msgId(100_000 + n), NOW + 1000);
        data.put("cleared_messages", cleared);
        assertTrue(ChatInbox.migrate(data, NOW));
        String once = data.toString();
        assertFalse(ChatInbox.migrate(data, NOW));
        assertEquals("running the upgrade twice changes nothing", once, data.toString());
        assertEquals(2, data.getJSONArray("messages").length());
        assertEquals("d:" + nonContact, data.getJSONArray("messages").getJSONObject(1).getString("chat"));
        assertEquals(ChatInbox.ACCEPT, ChatInbox.decide(data, nonContact));
        assertEquals(msgId(1), data.getJSONObject("read_messages").getString("d:" + CONTACT));
        assertEquals(42, data.getJSONObject("cursors").getLong("https://relay|inbox"));
        assertEquals("d:" + CONTACT, data.getJSONObject("chat_safety").getJSONArray("order").getString(0));
        assertTrue("a cleared ID is still ignored", ChatInbox.tombstoned(data, msgId(100_000), NOW + 7L * 24 * 3600 * 1000));
        for (int n = 0; n < 2000; n++) ChatInbox.tombstone(data, msgId(200_000 + n), NOW); // deleting works past 5,000
        assertTrue(ChatInbox.tombstoned(data, msgId(201_999), NOW));
    }

    @Test public void tombstonesExpireAfterEightDaysAndNeverBlockADelete() throws Exception {
        JSONObject data = wallet();
        ChatInbox.tombstone(data, msgId(1), NOW);
        assertTrue(ChatInbox.tombstoned(data, msgId(1), NOW + 7L * 24 * 3600 * 1000));
        assertFalse(ChatInbox.tombstoned(data, msgId(1), NOW + 8L * 24 * 3600 * 1000 + 1));
        JSONObject full = data.getJSONObject("cleared_messages");
        for (int n = 0; n < ChatInbox.TOMBSTONE_LIMIT + 10; n++) full.put(msgId(10 + n), NOW + 1000 + n);
        ChatInbox.tombstone(data, msgId(5), NOW); // at the limit the soonest-expiring go; deleting never fails
        assertTrue(data.getJSONObject("cleared_messages").length() <= ChatInbox.TOMBSTONE_LIMIT);
        assertTrue(ChatInbox.tombstoned(data, msgId(5), NOW));
    }

    @Test public void removingAContactNeverMovesOrCutsTheirHistory() throws Exception {
        JSONObject data = wallet();
        for (int n = 1; n <= 30; n++) data.getJSONArray("messages").put(message(n, "d:" + CONTACT, CONTACT, n));
        ChatInbox.migrate(data, NOW);
        data.put("contacts", new JSONArray());
        assertEquals(ChatInbox.ACCEPT, ChatInbox.decide(data, CONTACT));
        assertEquals(30, data.getJSONArray("messages").length());
    }

    @Test public void acceptingFromAnInvitationLastsAcrossARefresh() throws Exception {
        JSONObject data = wallet();
        ChatInbox.accept(data, STRANGER);
        JSONObject reloaded = new JSONObject(data.toString());
        assertEquals(ChatInbox.ACCEPT, ChatInbox.decide(reloaded, STRANGER));
    }

    // ---- Fair Nearby carry queue (R3) ----

    @Test public void oneSenderCannotFillTheQueue() throws Exception {
        List<JSONObject> queue = new ArrayList<>();
        for (int n = 1; n <= 60; n++) queue = ChatInbox.fairAdd(queue, envelope(n, STRANGER, 10), OWN, 4L << 20, NOW);
        assertEquals(ChatInbox.QUEUE_PER_SENDER, queue.size());
        assertEquals("a sender's own oldest packet makes way", msgId(60), queue.get(queue.size() - 1).getString("id"));
    }

    @Test public void yourOwnPacketsAlwaysFit() throws Exception {
        List<JSONObject> queue = new ArrayList<>();
        int n = 0;
        for (int s = 0; s < 12; s++) for (int k = 0; k < 16; k++) queue = ChatInbox.fairAdd(queue, envelope(++n, String.format("%064x", 9000 + s), 10), OWN, 4L << 20, NOW);
        assertTrue(queue.size() <= ChatInbox.QUEUE_OTHERS);
        for (int k = 0; k < 40; k++) queue = ChatInbox.fairAdd(queue, envelope(++n, OWN, 10), OWN, 4L << 20, NOW);
        int own = 0; for (JSONObject e : queue) if (ChatInbox.envelopeSender(e).equals(OWN)) own++;
        assertEquals(40, own);
        assertTrue(queue.size() <= ChatInbox.QUEUE_TOTAL);
        // Refused only when the queue is entirely your own packets.
        List<JSONObject> mine = new ArrayList<>();
        for (int k = 0; k < ChatInbox.QUEUE_TOTAL; k++) mine = ChatInbox.fairAdd(mine, envelope(50_000 + k, OWN, 10), OWN, 4L << 20, NOW);
        assertNull(ChatInbox.fairAdd(mine, envelope(60_000, OWN, 10), OWN, 4L << 20, NOW));
        assertNotNull(ChatInbox.fairAdd(mine, envelope(60_001, STRANGER, 10), OWN, 4L << 20, NOW));
    }

    @Test public void largePacketsMakeRoomByBytesWithoutTouchingYourOwn() throws Exception {
        List<JSONObject> queue = new ArrayList<>();
        queue = ChatInbox.fairAdd(queue, envelope(1, OWN, 1000), OWN, 10_000, NOW);
        for (int n = 2; n < 12; n++) queue = ChatInbox.fairAdd(queue, envelope(n, String.format("%064x", 700 + n), 2000), OWN, 10_000, NOW);
        long size = 0; boolean own = false;
        for (JSONObject e : queue) { size += ChatInbox.bytes(e); own |= ChatInbox.envelopeSender(e).equals(OWN); }
        assertTrue(size <= 10_000);
        assertTrue(own);
    }

    // ---- Payment approval (176, the app side) ----

    @Test public void phoneSendCarriesOnlyThePhoneSource() throws Exception {
        JSONObject args = WalletKeys.approval(new JSONObject(), new JSONObject().put("op", "payment_send"));
        assertEquals("phone", args.getString("approval_source"));
        assertFalse(args.has("confirmed_to") || args.has("confirmed_amount_micronoid") || args.has("confirmed_review_id"));
    }

    @Test public void wearSendCarriesExactlyWhatTheWatchShowed() throws Exception {
        JSONObject request = new JSONObject().put("watch", true).put("confirmed_to", "o1x").put("confirmed_amount_micronoid", WalletKeys.micronoid("1.250000")).put("confirmed_review_id", "ab");
        JSONObject args = WalletKeys.approval(new JSONObject(), request);
        assertEquals("watch", args.getString("approval_source"));
        assertEquals("1250000", args.getString("confirmed_amount_micronoid"));
        assertEquals("o1x", args.getString("confirmed_to"));
        assertEquals("ab", args.getString("confirmed_review_id"));
    }

    @Test public void wearApprovalWithFieldsMissingIsRefused() throws Exception {
        for (String missing : new String[]{"confirmed_to", "confirmed_amount_micronoid", "confirmed_review_id"}) {
            JSONObject request = new JSONObject().put("watch", true).put("confirmed_to", "o1x").put("confirmed_amount_micronoid", "1").put("confirmed_review_id", "ab");
            request.remove(missing);
            try { WalletKeys.approval(new JSONObject(), request); fail("refused without " + missing); } catch (Exception expected) { }
        }
        try { WalletKeys.micronoid("0.0000001"); fail("finer than a micronoid"); } catch (ArithmeticException expected) { }
    }
}
