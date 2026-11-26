package org.parano1d.courier;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;

/**
 * Token and NFT sends, using the iOS 165 cores unchanged (built into the same Android library):
 *   NodeCore   = iOS Node crate: verify_parent, native_prepare (pinned planner, peers' pending slots,
 *                relay fee floor), native_submit, settle_payment, expire_payment.
 *   WalletCore = iOS Native crate: canonical review, authorize (signs and records in the
 *                payment journal: ledger.json v2, exact claims, 20 unresolved maximum), payment_pending.
 * The flow matches iOS MarketsView.prepare/submit: verified parent → plan → canonical review →
 * person approves the exact review (60-second freshness) → parent re-checked → signed → submitted.
 * A signed intent stays reserved in the journal across timeouts and restarts until verified
 * settlement or verified epoch expiry releases it.
 */
final class AssetSend {
    private static final long REVIEW_MS = 60_000;

    private AssetSend() {}

    static JSONObject node(JSONObject request) throws Exception {
        JSONObject result = new JSONObject(NodeCore.call(request.toString()));
        if (!result.optBoolean("ok")) throw new Exception(result.optString("error", "Node operation failed"));
        return result.optJSONObject("result") == null ? new JSONObject() : result.getJSONObject("result");
    }

    static JSONObject wallet(JSONObject request, byte[] secret) throws Exception {
        try {
            JSONObject result = new JSONObject(WalletCore.call(request.toString(), secret));
            if (result.has("error")) throw new Exception(result.getString("error"));
            return result.getJSONObject("result");
        } finally { if (secret != null) java.util.Arrays.fill(secret, (byte) 0); }
    }

    static File journal(File filesDir, String owner) throws Exception {
        if (!owner.matches("o1[0-9a-z]{20,126}")) throw new Exception("Invalid wallet identity");
        File directory = new File(new File(filesDir, "payments"), owner);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new Exception("Protected payment storage unavailable");
        return directory;
    }

    /** Builds the request the planner accepts (same JSON as iOS: transfer_token / transfer_nft). */
    static JSONObject request(JSONObject ui) throws Exception {
        String action = ui.getString("action"), recipient = ui.getString("recipient").trim();
        if (!recipient.matches("o1[0-9a-z]{20,126}")) throw new Exception("Enter a valid NOID address.");
        if (action.equals("transfer_nft")) {
            String collection = ui.getString("collection_id"), serial = ui.getString("serial");
            if (!collection.matches("[0-9a-f]{64}") || !serial.matches("[0-9]{1,20}")) throw new Exception("Choose an NFT you own.");
            return new JSONObject().put("action", "transfer_nft").put("collection_id", collection).put("serial", serial).put("recipient", recipient);
        }
        if (action.equals("transfer_token")) {
            String asset = ui.getString("asset_id"), units = ui.getString("units");
            if (!asset.matches("[0-9a-f]{64}") || !units.matches("[1-9][0-9]{0,19}")) throw new Exception("Enter a token amount greater than zero.");
            return new JSONObject().put("action", "transfer_token").put("asset_id", asset).put("units", units).put("recipient", recipient);
        }
        throw new Exception("Unsupported asset action");
    }

    /** Plans and reviews. Returns the canonical review; the unsigned plan stays on the phone. */
    static JSONObject prepare(JSONObject data, File artifacts, File chain, File filesDir, JSONObject ui) throws Exception {
        String owner = data.getJSONObject("profile").getString("address");
        if (!data.has("seed_hex")) throw new Exception("Create or restore your wallet first.");
        if (data.optLong("backup_saved_ms") <= 0) throw new Exception("Save an encrypted backup before sending tokens or NFTs.");
        JSONObject request = request(ui);
        String chainId = node(new JSONObject().put("op", "chain_identity")).getString("chain_id");
        MainActivity.reviewStep(data.optBoolean("paranoid_mode") ? "Checking the latest block with mainnet peers…" : "Checking the latest block…");
        JSONObject verified = node(new JSONObject().put("op", "verify_parent").put("expected_chain", chainId)
            .put("artifacts", artifacts.getAbsolutePath()).put("data_dir", chain.getAbsolutePath()));
        if (!chainId.equals(verified.optString("chain_id"))) throw new Exception("Device node returned another chain.");
        String parent = verified.getString("verified_parent_hash");
        MainActivity.reviewStep("Preparing the exact terms…");
        JSONObject plan = node(new JSONObject().put("op", "native_prepare").put("owner", owner).put("account_index", data.optInt("account_index"))
            .put("expected_parent_hash", parent).put("request", request)
            .put("journal_directory", journal(filesDir, owner).getAbsolutePath())
            .put("artifacts", artifacts.getAbsolutePath()).put("data_dir", chain.getAbsolutePath()));
        if (!owner.equals(plan.optString("owner"))) throw new Exception("Plan belongs to another wallet.");
        JSONObject operation = new JSONObject().put("native_plan", plan).put("expected_request", request)
            .put("expected_chain", chainId).put("expected_parent_hash", parent).put("maximum_fee_micronoid", "100000");
        JSONObject reviewed = wallet(new JSONObject().put("op", "review").put("operation", operation), null);
        JSONObject review = reviewed.getJSONObject("review");
        String hash = reviewed.getString("review_hash");
        if (hash.isEmpty()) throw new Exception("The verifier returned an incomplete review. Nothing was signed.");
        data.put("pending_asset_review", new JSONObject().put("operation", operation).put("review_hash", hash)
            .put("review", review).put("deadline_ms", System.currentTimeMillis() + REVIEW_MS).put("request", request));
        return new JSONObject().put("review", review).put("review_hash", hash).put("request", request).put("expires_in_ms", REVIEW_MS);
    }

    /** Signs exactly the reviewed operation, then submits the identical signed bytes. */
    static JSONObject approve(JSONObject data, File artifacts, File chain, File filesDir, JSONObject ui, boolean paranoid) throws Exception {
        JSONObject pending = data.optJSONObject("pending_asset_review");
        if (pending == null || !pending.getString("review_hash").equals(ui.optString("review_hash"))) throw new Exception("This review changed. Review again.");
        data.remove("pending_asset_review");
        if (System.currentTimeMillis() > pending.getLong("deadline_ms")) throw new Exception("Review expired. Review again before approving.");
        JSONObject request = pending.getJSONObject("request");
        // The person confirmed the full recipient (and amount) on screen; it must be the reviewed one.
        if (!request.getString("recipient").equals(ui.optString("confirmed_to"))) throw new Exception("The confirmed address differs from the review. Nothing was signed.");
        JSONObject operation = pending.getJSONObject("operation");
        String owner = data.getJSONObject("profile").getString("address");
        String id = operation.getJSONObject("native_plan").getString("intent_id");
        // Final chain check: the reviewed parent must still be the device's verified tip.
        node(new JSONObject().put("op", "verify_parent").put("expected_chain", operation.getString("expected_chain"))
            .put("expected_parent_hash", operation.getString("expected_parent_hash"))
            .put("artifacts", artifacts.getAbsolutePath()).put("data_dir", chain.getAbsolutePath()));
        String directory = journal(filesDir, owner).getAbsolutePath();
        byte[] secret = hex(data.getString("seed_hex"));
        JSONObject signed = wallet(new JSONObject().put("op", "authorize").put("operation", operation)
            .put("approved_review_hash", pending.getString("review_hash")).put("account_index", data.optInt("account_index")).put("journal_directory", directory), secret);
        if (!id.equals(signed.optString("txid")) || signed.optString("intent_hex").isEmpty())
            throw new Exception("The signing result did not match the review. Nothing was submitted; the saved authorization is kept.");
        JSONObject sent;
        try {
            sent = node(new JSONObject().put("op", "native_submit").put("expected_txid", id).put("journal_directory", directory)
                .put("paranoid_mode", paranoid).put("artifacts", artifacts.getAbsolutePath()));
        } catch (Exception relay) {
            return new JSONObject().put("txid", id).put("submitted", false).put("error", relay.getMessage() + " The signed transfer is saved and reserved; retry from pending transfers.");
        }
        if (!id.equals(sent.optString("txid"))) throw new Exception("Submission identity mismatch. The saved authorization is kept.");
        return new JSONObject().put("txid", id).put("submitted", true).put("request", request);
    }

    /** Resubmits the identical saved signed bytes (never a new authorization). */
    static JSONObject retry(JSONObject data, File artifacts, File filesDir, String txid, boolean paranoid) throws Exception {
        String owner = data.getJSONObject("profile").getString("address");
        return node(new JSONObject().put("op", "native_submit").put("expected_txid", txid).put("journal_directory", journal(filesDir, owner).getAbsolutePath())
            .put("paranoid_mode", paranoid).put("artifacts", artifacts.getAbsolutePath()));
    }

    /** Journal entries for the UI: pending transfers and which NFTs/tokens they lock. Local only. */
    static JSONObject pending(JSONObject data, File filesDir) throws Exception {
        if (data.optJSONObject("profile") == null) return new JSONObject().put("entries", new JSONArray());
        String owner = data.getJSONObject("profile").getString("address");
        JSONObject result = wallet(new JSONObject().put("op", "payment_pending").put("journal_directory", journal(filesDir, owner).getAbsolutePath()), null);
        JSONArray entries = result.optJSONArray("entries"), out = new JSONArray();
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            JSONObject item = new JSONObject().put("txid", e.optString("txid")).put("state", e.optString("state"))
                .put("kind", e.optString("operation_kind")).put("fee", e.optString("local_fee"))
                .put("request", e.optJSONObject("local_native_request") == null ? new JSONObject() : e.getJSONObject("local_native_request"));
            out.put(item);
        }
        return new JSONObject().put("entries", out);
    }

    /**
     * Releases reservations only on independently verified evidence: a finalized receipt
     * (settle_payment) or a verified epoch expiry (expire_payment). Otherwise keeps them.
     */
    static JSONObject reconcile(JSONObject data, File artifacts, File chain, File filesDir) throws Exception {
        String owner = data.getJSONObject("profile").getString("address");
        String directory = journal(filesDir, owner).getAbsolutePath();
        JSONArray entries = pending(data, filesDir).getJSONArray("entries");
        JSONArray observations = data.optJSONArray("observations");
        int settled = 0, expired = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            if (!e.optString("state").equals("authorized") || !e.optString("kind").equals("native")) continue;
            String txid = e.getString("txid"), receipt = "";
            if (observations != null) for (int j = observations.length() - 1; j >= 0 && receipt.isEmpty(); j--) {
                JSONObject message = observations.getJSONObject(j).optJSONObject("message");
                if (message != null && txid.equals(message.optString("txid")) && !message.isNull("receipt_hex")) receipt = message.optString("receipt_hex");
            }
            try {
                if (!receipt.isEmpty()) {
                    node(new JSONObject().put("op", "settle_payment").put("expected_txid", txid).put("receipt_hex", receipt)
                        .put("journal_directory", directory).put("data_dir", chain.getAbsolutePath()).put("artifacts", artifacts.getAbsolutePath()));
                    settled++;
                } else {
                    node(new JSONObject().put("op", "expire_payment").put("expected_txid", txid).put("journal_directory", directory)
                        .put("data_dir", chain.getAbsolutePath()).put("artifacts", artifacts.getAbsolutePath()));
                    expired++;
                }
            } catch (Exception notYet) { /* reservation retained until verified evidence exists */ }
        }
        return new JSONObject().put("settled", settled).put("expired", expired).put("pending", pending(data, filesDir));
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
