//! Ordinary NOID payments built from authenticated live and empty slot proofs.

use anyhow::{ensure, Context, Result};
use noid_chain::{fri_state::SlotValue, BlockHeader};
use noid_core::Block128;
use noid_poseidon2b::primitives::Address;
use noid_tx::{
    output_bitmap_bit, PagedSpendIntent, TxBody, TxInput, TxOutput, TxPage, PAGED_SPEND_END_BIT,
    PAGED_SPEND_START_BIT,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::File,
    path::{Path, PathBuf},
};
use zeroize::Zeroizing;

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Slot {
    pub(crate) slot_index: u32,
    pub(crate) value: u64,
    pub(crate) creation_id: u64,
    pub(crate) owner: String,
    pub(crate) empty: bool,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Proof {
    pub(crate) height: u64,
    pub(crate) block_hash: String,
    pub(crate) state_root: String,
    pub(crate) log_slots: u32,
    pub(crate) slots: Vec<Slot>,
    pub(crate) siblings: Vec<String>,
}
struct Boundary {
    header: BlockHeader,
    bundle: PathBuf,
    anchor: Option<BlockHeader>,
}

fn arg<'a>(args: &'a Value, name: &str) -> Result<&'a str> {
    args[name]
        .as_str()
        .with_context(|| format!("Missing {name}"))
}
fn directory(args: &Value) -> Result<PathBuf> {
    Ok(PathBuf::from(arg(args, "data_dir")?))
}
fn boundary(args: &Value, fresh: bool) -> Result<Boundary> {
    let data = directory(args)?;
    let (height, hash, root, bundle) = if fresh {
        let result =
            crate::mobile::run(PathBuf::from(arg(args, "artifacts")?), data.clone(), true)?;
        let state = &result["verified"];
        (
            state["height"].as_u64().context("Verified height")?,
            state["block_hash"]
                .as_str()
                .context("Verified hash")?
                .to_string(),
            state["state_root"]
                .as_str()
                .context("Verified root")?
                .to_string(),
            crate::courier_api::accepted_bundle(&data)?,
        )
    } else {
        let bundle = crate::courier_api::accepted_bundle(&data)?;
        let state = crate::verifier_session::verify(Path::new(arg(args, "artifacts")?), &bundle)?;
        (
            state.height(),
            state.block_hash().to_string(),
            state.state_root().to_string(),
            bundle,
        )
    };
    let header = crate::headers::read_header(&mut File::open(bundle.join("headers.bin"))?, height)?;
    ensure!(
        hex::encode(noid_chain::block_header::block_id(&header)) == hash
            && hex::encode(header.state_root) == root,
        "Wallet boundary mismatch"
    );
    Ok(Boundary {
        header,
        bundle,
        anchor: None,
    })
}
pub(crate) fn verify_proof(
    header: &BlockHeader,
    proof: &Proof,
) -> Result<BTreeMap<u32, SlotValue>> {
    ensure!(header.log_slots <= 32, "Unsupported State depth");
    ensure!(
        proof.height == header.height
            && proof.block_hash == hex::encode(noid_chain::block_header::block_id(header))
            && proof.state_root == hex::encode(header.state_root)
            && proof.log_slots == header.log_slots,
        "State advanced; synchronize and retry"
    );
    ensure!(
        !proof.slots.is_empty()
            && proof.slots.len() <= 128
            && proof
                .slots
                .windows(2)
                .all(|p| p[0].slot_index < p[1].slot_index),
        "Invalid slot proof order"
    );
    let mut values = BTreeMap::new();
    for slot in &proof.slots {
        ensure!(
            u64::from(slot.slot_index) < (1u64 << header.log_slots),
            "Slot outside State"
        );
        let value = if slot.empty {
            ensure!(
                slot.value == 0 && slot.creation_id == 0 && slot.owner.is_empty(),
                "Invalid empty slot"
            );
            SlotValue::EMPTY
        } else {
            let owner = Address::parse(&slot.owner)
                .map_err(|e| anyhow::anyhow!("Invalid slot owner: {e}"))?;
            ensure!(slot.creation_id > 0, "Invalid slot incarnation");
            SlotValue {
                value: Block128(u128::from(slot.value) | (u128::from(slot.creation_id) << 64)),
                owner_hi: Block128(u128::from_le_bytes(owner.0[..16].try_into().unwrap())),
                owner_lo: Block128(u128::from_le_bytes(owner.0[16..].try_into().unwrap())),
            }
        };
        values.insert(slot.slot_index, value);
    }
    let indices = values.keys().copied().collect::<Vec<_>>();
    let leaves = values
        .values()
        .copied()
        .map(noid_chain::exact_state_hash::slot_leaf_hash)
        .collect::<Vec<_>>();
    let siblings = proof
        .siblings
        .iter()
        .map(|s| paramo1d_courier::decode_array::<32>(s))
        .collect::<Result<Vec<_>>>()?;
    let root =
        noid_chain::sparse_merkle::reconstruct_root(&indices, &leaves, &siblings, header.log_slots)
            .map_err(|e| anyhow::anyhow!("Slot proof rejected: {e}"))?;
    ensure!(root == header.state_root, "Slot proof root mismatch");
    Ok(values)
}
fn owner(value: SlotValue) -> Address {
    let mut a = [0u8; 32];
    a[..16].copy_from_slice(&value.owner_hi.0.to_le_bytes());
    a[16..].copy_from_slice(&value.owner_lo.0.to_le_bytes());
    Address(a)
}
fn amount(value: u64) -> String {
    format!("{}.{:06}", value / 1_000_000, value % 1_000_000)
}
/// iOS "Wallets and accounts": which account derived from the master key this request uses (0 = first).
fn account(args: &Value) -> Result<u32> {
    match args.get("account_index") {
        None | Some(Value::Null) => Ok(0),
        Some(value) => {
            let index = value.as_u64().context("Invalid account index")?;
            ensure!(index <= 100_000, "Account index outside wallet range");
            Ok(index as u32)
        }
    }
}
fn seed(args: &Value) -> Result<Zeroizing<[u8; 32]>> {
    Ok(Zeroizing::new(paramo1d_courier::decode_array(arg(
        args, "seed_hex",
    )?)?))
}

fn fetch_view(args: &Value, address: Address) -> Result<Vec<Proof>> {
    let mut rpc = crate::rpc::Rpc::new(arg(args, "rpc")?)?;
    let slots: Vec<Slot> =
        serde_json::from_value(rpc.call("getSlotsByOwner", json!([address.to_bech32()]))?)?;
    ensure!(
        slots.len() <= 4096,
        "Wallet scan exceeds mobile input budget"
    );
    let hints: Vec<u32> = serde_json::from_value(rpc.call("getSlotHints", json!([8]))?)?;
    let mut indices = slots
        .iter()
        .map(|v| v.slot_index)
        .chain(hints)
        .collect::<Vec<_>>();
    indices.sort_unstable();
    indices.dedup();
    ensure!(!indices.is_empty(), "No State data available");
    let mut proofs = Vec::new();
    for chunk in indices.chunks(8) {
        let proof: Proof = serde_json::from_value(rpc.call("getSlotProof", json!([chunk]))?)?;
        ensure!(
            proof.slots.iter().map(|s| s.slot_index).collect::<Vec<_>>() == chunk,
            "Node returned different slot indices"
        );
        if let Some(first) = proofs.first() {
            let first: &Proof = first;
            ensure!(
                first.height == proof.height
                    && first.block_hash == proof.block_hash
                    && first.state_root == proof.state_root,
                "Wallet data crossed a block boundary; retry"
            );
        }
        proofs.push(proof);
    }
    Ok(proofs)
}
fn save_view(
    args: &Value,
    proofs: &[Proof],
    boundary: &Boundary,
    proof_header: &BlockHeader,
) -> Result<()> {
    let checkpoint = crate::wallet_checkpoint::seal(
        args,
        &boundary.header,
        proof_header,
        &boundary.anchor.context("Verified epoch header")?,
    )?;
    let cache = json!({"proofs":proofs,"checkpoint":checkpoint,"source":if crate::peer_wallet::enabled(args){"direct-p2p"}else{"rpc"}});
    let path = directory(args)?.join("wallet-view.json");
    let mut temporary = tempfile::NamedTempFile::new_in(path.parent().unwrap())?;
    use std::io::Write;
    temporary.write_all(&serde_json::to_vec(&cache)?)?;
    temporary.as_file().sync_all()?;
    temporary.persist(path).map_err(|e| e.error)?;
    Ok(())
}
fn view(args: &Value, address: Address) -> Result<(Boundary, BTreeMap<u32, SlotValue>, u64)> {
    if crate::peer_wallet::enabled(args) && args["use_cached"].as_bool() != Some(true) {
        let wallet = crate::peer_wallet::fetch_wallet(args, address)?;
        let bundle = crate::courier_api::accepted_bundle(&directory(args)?)?;
        let epoch =
            noid_chain::consensus::tx_epoch_anchor_height_for_child(wallet.header.height + 1);
        let anchor =
            crate::headers::read_header(&mut File::open(bundle.join("headers.bin"))?, epoch)?;
        let checkpoint =
            crate::wallet_checkpoint::seal(args, &wallet.header, &wallet.header, &anchor)?;
        let cached =
            json!({"checkpoint":checkpoint,"projection":wallet.seal(args)?,"source":"direct-p2p"});
        let data = directory(args)?;
        let mut file = tempfile::NamedTempFile::new_in(&data)?;
        use std::io::Write;
        file.write_all(&serde_json::to_vec(&cached)?)?;
        file.as_file().sync_all()?;
        file.persist(data.join("wallet-view.json"))
            .map_err(|e| e.error)?;
        let boundary = Boundary {
            header: wallet.header,
            bundle,
            anchor: Some(anchor),
        };
        return Ok((boundary, wallet.slots, wallet.header.height));
    }
    let mut checkpoint = None;
    let proofs = if args["use_cached"].as_bool() == Some(true) {
        ensure!(
            directory(args)?.join("wallet-view.json").is_file(),
            "Connect once and select Check balance to prepare this phone for offline payments"
        );
        let bytes = crate::artifacts::read_bounded(
            &directory(args)?.join("wallet-view.json"),
            8 * 1024 * 1024,
        )?;
        let cache: Value = serde_json::from_slice(&bytes)?;
        if !cache["projection"].is_null() {
            let (tip, proof, anchor) = crate::wallet_checkpoint::open(args, &cache["checkpoint"])?;
            let wallet =
                crate::peer_wallet_view::WalletView::open(args, address, &cache["projection"])?;
            ensure!(
                wallet.header == proof,
                "Wallet projection checkpoint mismatch"
            );
            return Ok((
                Boundary {
                    header: tip,
                    bundle: PathBuf::new(),
                    anchor: Some(anchor),
                },
                wallet.slots,
                proof.height,
            ));
        }
        checkpoint = crate::wallet_checkpoint::open(args, &cache["checkpoint"]).ok();
        serde_json::from_value::<Vec<Proof>>(cache["proofs"].clone())?
    } else {
        // Obtain one exact snapshot before the longer independent chain check.
        fetch_view(args, address)?
    };
    ensure!(
        !proofs.is_empty(),
        "Connect once to prepare the wallet's verified offline view"
    );
    let sealed = checkpoint.is_some();
    let proof_height = proofs[0].height;
    let (boundary, header) = if let Some((tip, proof, anchor)) = checkpoint {
        (
            Boundary {
                header: tip,
                bundle: PathBuf::new(),
                anchor: Some(anchor),
            },
            proof,
        )
    } else {
        let fresh =
            args["use_cached"].as_bool() != Some(true) && !crate::peer_wallet::enabled(args);
        let mut boundary = boundary(args, fresh)?;
        let mut file = File::open(boundary.bundle.join("headers.bin"))?;
        let proof = crate::headers::read_header(&mut file, proof_height)?;
        let epoch =
            noid_chain::consensus::tx_epoch_anchor_height_for_child(boundary.header.height + 1);
        boundary.anchor = Some(crate::headers::read_header(&mut file, epoch)?);
        (boundary, proof)
    };
    ensure!(
        proof_height <= boundary.header.height,
        "Wallet data is ahead of the verified chain; synchronize and retry"
    );
    let mut slots = BTreeMap::new();
    for proof in &proofs {
        // The independently checked header prefix binds the original snapshot.
        slots.extend(verify_proof(&header, proof)?);
    }
    if !sealed {
        save_view(args, &proofs, &boundary, &header)?;
    }
    Ok((boundary, slots, proof_height))
}
fn owned_coin_set(
    slots: impl IntoIterator<Item = Slot>,
    address: Address,
) -> Result<BTreeMap<u32, (u64, u64)>> {
    let address = address.to_bech32();
    let mut coins = BTreeMap::new();
    for slot in slots {
        if !slot.empty
            && slot.owner == address
            && noid_tx::native_assets::native_sequence(slot.creation_id).is_err()
        {
            ensure!(
                coins
                    .insert(slot.slot_index, (slot.value, slot.creation_id))
                    .is_none(),
                "Duplicate wallet slot"
            );
            ensure!(
                coins.len() <= 4096,
                "Wallet scan exceeds mobile input budget"
            );
        }
    }
    Ok(coins)
}
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum WalletHint {
    Unchanged,
    CoinsChanged,
    EpochChanged,
}
fn wallet_change_hint(args: &Value, address: Address) -> Result<Option<WalletHint>> {
    if crate::peer_wallet::enabled(args) {
        return match crate::peer_wallet::watch(args, address) {
            Ok(hint) => Ok(hint),
            Err(error) => {
                crate::mobile_control::check()?;
                eprintln!("  P2P WATCH Verified refresh: {error:#}");
                Ok(None)
            }
        };
    }
    let path = directory(args)?.join("wallet-view.json");
    if !path.is_file() {
        return Ok(None);
    }
    let bytes = crate::artifacts::read_bounded(&path, 8 * 1024 * 1024)?;
    let cache: Value = serde_json::from_slice(&bytes)?;
    let (saved, epoch_slot) = if !cache["projection"].is_null() {
        let (_, proof, _) = crate::wallet_checkpoint::open(args, &cache["checkpoint"])?;
        let view = crate::peer_wallet_view::WalletView::open(args, address, &cache["projection"])?;
        ensure!(
            view.header == proof,
            "Wallet projection checkpoint mismatch"
        );
        (view.coin_set(), view.slots.keys().next().copied())
    } else {
        let proofs: Vec<Proof> = serde_json::from_value(cache["proofs"].clone())?;
        if proofs.is_empty() {
            return Ok(None);
        }
        let index = proofs
            .iter()
            .flat_map(|p| &p.slots)
            .next()
            .map(|s| s.slot_index);
        (
            owned_coin_set(proofs.into_iter().flat_map(|p| p.slots), address)?,
            index,
        )
    };
    let mut rpc = crate::rpc::Rpc::watch(arg(args, "rpc")?)?;
    let live: Vec<Slot> =
        serde_json::from_value(rpc.call("getSlotsByOwner", json!([address.to_bech32()]))?)?;
    ensure!(
        live.len() <= 4096,
        "Wallet scan exceeds mobile input budget"
    );
    // This comparison schedules verification. It never accepts a new balance,
    // State root, timestamp or spendable input from the unverified hint.
    if saved != owned_coin_set(live, address)? {
        return Ok(Some(WalletHint::CoinsChanged));
    }
    if args["follow"].as_bool() == Some(true) && !saved.is_empty() {
        let Some(index) = epoch_slot else {
            return Ok(None);
        };
        let Ok((tip, _, _)) = crate::wallet_checkpoint::open(args, &cache["checkpoint"]) else {
            return Ok(None);
        };
        let hint: Proof = serde_json::from_value(rpc.call("getSlotProof", json!([[index]]))?)?;
        ensure!(
            hint.slots.len() == 1 && hint.slots[0].slot_index == index,
            "Node returned a different epoch probe slot"
        );
        if require_current_payment_epoch(tip.height, hint.height).is_err() {
            return Ok(Some(WalletHint::EpochChanged));
        }
    }
    Ok(Some(WalletHint::Unchanged))
}
pub fn synchronize(args: &Value) -> Result<Value> {
    let secret = seed(args)?;
    // Watch-only (iOS "Watch an address"): the balance of a public address, verified the same way.
    // `seed_hex` is then a random local key that only seals this phone's cached view; it can't spend.
    let address = match args["owner_address"].as_str() {
        Some(text) => Address::parse(text).map_err(|e| anyhow::anyhow!("Invalid watch address: {e}"))?,
        None => noid_poseidon2b::primitives::derive_address(&crate::courier_api::spend_secret(&secret, account(args)?)),
    };
    let hint = wallet_change_hint(args, address)?;
    if hint == Some(WalletHint::Unchanged) {
        return Ok(json!({"deferred":true}));
    }
    let _renewal =
        (hint == Some(WalletHint::EpochChanged)).then(crate::mobile_control::renew_authorization);
    let (boundary, slots, proof_height) = view(args, address)?;
    let total = wallet_total(&slots, address)?;
    Ok(
        json!({"height":boundary.header.height,"balance_height":proof_height,"balance":amount(total),"balance_micronoid":total.to_string(),"block_hash":hex::encode(noid_chain::block_header::block_id(&boundary.header)),"transport":if crate::peer_wallet::enabled(args){"direct-p2p"}else{"rpc"}}),
    )
}
fn wallet_total(slots: &BTreeMap<u32, SlotValue>, address: Address) -> Result<u64> {
    slots
        .values()
        .filter(|v| {
            owner(**v) == address
                && noid_tx::native_assets::native_sequence(v.creation_id()).is_err()
        })
        .try_fold(0u64, |total, v| total.checked_add(v.amount()))
        .context("Balance overflow")
}
fn reservations(
    args: &Value,
    anchor: [u8; 32],
    proof_height: u64,
) -> Result<(BTreeSet<(u32, u64)>, BTreeSet<u32>)> {
    let mut inputs = BTreeSet::new();
    let mut outputs = BTreeSet::new();
    if let Some(packets) = args["reservations"].as_array() {
        ensure!(
            packets.len() <= 4096,
            "Too many pending authorizations. Synchronize to refresh the wallet"
        );
        let mut live_packets = 0;
        for packet in packets {
            if packet["epoch_anchor"].as_str() != Some(hex::encode(anchor).as_str())
                || packet["included_height"]
                    .as_u64()
                    .is_some_and(|height| height <= proof_height)
            {
                continue;
            }
            live_packets += 1;
            ensure!(
                live_packets <= 100,
                "Too many pending payments. Wait for confirmation or update your balance"
            );
            let reserved = packet["inputs"]
                .as_array()
                .context("Pending input reservations")?;
            ensure!(reserved.len() <= 504, "Pending input limit");
            for item in reserved {
                inputs.insert((
                    u32::try_from(item["slot_index"].as_u64().context("Reserved input slot")?)?,
                    item["creation_id"]
                        .as_u64()
                        .context("Reserved input identity")?,
                ));
            }
            let reserved = packet["outputs"]
                .as_array()
                .context("Pending output reservations")?;
            ensure!(reserved.len() <= 2, "Pending output limit");
            for item in reserved {
                outputs.insert(u32::try_from(
                    item.as_u64().context("Reserved output slot")?,
                )?);
            }
        }
    }
    Ok((inputs, outputs))
}

fn cached_headers(args: &Value) -> Result<(BlockHeader, BlockHeader, BlockHeader)> {
    let bytes = crate::artifacts::read_bounded(
        &directory(args)?.join("wallet-view.json"),
        8 * 1024 * 1024,
    )?;
    let cache: Value = serde_json::from_slice(&bytes)?;
    crate::wallet_checkpoint::open(args, &cache["checkpoint"])
}

fn require_current_payment_epoch(verified_height: u64, observed_height: u64) -> Result<()> {
    let verified_child = verified_height
        .checked_add(1)
        .context("Payment height overflow")?;
    let observed_child = observed_height
        .checked_add(1)
        .context("Observed height overflow")?;
    ensure!(
        observed_height <= verified_height
            || noid_chain::consensus::tx_epoch_anchor_height_for_child(verified_child)
                == noid_chain::consensus::tx_epoch_anchor_height_for_child(observed_child),
        "Payment epoch changed. Update your wallet and review the payment again."
    );
    Ok(())
}

pub fn prepare(args: &Value) -> Result<Value> {
    let mut cached = args["use_cached"].as_bool() == Some(true);
    let master = seed(args)?;
    let owner = noid_poseidon2b::primitives::derive_address(&crate::courier_api::spend_secret(
        &master,
        account(args)?,
    ));
    let recipient = Address::parse(arg(args, "address")?)
        .map_err(|e| anyhow::anyhow!("Invalid recipient: {e}"))?;
    let payment = paramo1d_courier::amount_micronoid(arg(args, "amount")?)?;
    ensure!(payment > 0, "Payment must be positive");
    let mut effective = args.clone();
    let observed = if args["online"].as_bool() == Some(true) {
        Some(crate::mobile::observed_height(Path::new(arg(
            args,
            "artifacts",
        )?))?)
    } else {
        None
    };
    if let Some(observed) = observed.filter(|_| cached) {
        let usable = cached_headers(args)
            .is_ok_and(|(tip, _, _)| require_current_payment_epoch(tip.height, observed).is_ok());
        if !usable {
            effective["use_cached"] = Value::Bool(false);
            cached = false;
        }
    }
    let (boundary, slots, proof_height) = view(&effective, owner)?;
    if let Some(observed) = observed {
        require_current_payment_epoch(boundary.header.height, observed)?;
    }
    let total_balance = wallet_total(&slots, owner)?;
    let epoch = noid_chain::consensus::tx_epoch_anchor_height_for_child(boundary.header.height + 1);
    let anchor = boundary.anchor.context("Verified epoch header")?;
    ensure!(anchor.height == epoch, "Payment epoch boundary");
    let (mut reserved_inputs, mut reserved_outputs) = reservations(
        args,
        noid_chain::block_header::block_id(&anchor),
        proof_height,
    )?;
    // Same as iOS 165 payment_plan: coins and output slots that peers' pending transactions
    // already claim (this wallet on another device, or anyone's new outputs) are never planned again.
    for item in args["mempool"]["inputs"].as_array().into_iter().flatten().take(200_000) {
        if let (Some(slot), Some(creation)) = (item[0].as_u64(), item[1].as_u64()) {
            if let Ok(slot) = u32::try_from(slot) { reserved_inputs.insert((slot, creation)); }
        }
    }
    for item in args["mempool"]["outputs"].as_array().into_iter().flatten().take(200_000) {
        if let Some(slot) = item.as_u64().and_then(|v| u32::try_from(v).ok()) { reserved_outputs.insert(slot); }
    }
    let mut available = slots
        .iter()
        .filter(|(index, v)| {
            !reserved_inputs.contains(&(**index, v.creation_id()))
                && **v != SlotValue::EMPTY
                && owner == self::owner(**v)
                && noid_tx::native_assets::native_sequence(v.creation_id()).is_err()
        })
        .map(|(i, v)| (*i, *v))
        .collect::<Vec<_>>();
    available.sort_by_key(|(index, v)| (std::cmp::Reverse(v.amount()), *index));
    let empty = slots
        .iter()
        .filter(|(index, v)| **v == SlotValue::EMPTY && !reserved_outputs.contains(index))
        .map(|(i, _)| *i)
        .take(2)
        .collect::<Vec<_>>();
    ensure!(
        empty.len() == 2,
        "Refresh State to obtain available output slots"
    );
    let mut selected = Vec::new();
    let mut total = 0u64;
    let mut fee = 0u64;
    // iOS 166: pay the peers' current relay floor when the network is busy (peers reject less),
    // never more than 0.1 NOID without a new review.
    let floor = args["mempool"]["floor"].as_u64().unwrap_or(0);
    for (index, value) in available.into_iter().take(504) {
        total = total
            .checked_add(value.amount())
            .context("Input amount overflow")?;
        selected.push((index, value));
        fee = noid_chain::consensus::fees::fee_breakdown(
            selected.len() as u64,
            2,
            boundary.header.active_slot_count,
            boundary.header.log_slots,
        )
        .required_total
        .max(floor);
        if total >= payment.checked_add(fee).context("Amount overflow")? {
            break;
        }
    }
    ensure!(
        total >= payment.checked_add(fee).context("Amount overflow")?,
        "Insufficient available NOID. Pending authorizations reserve their inputs until confirmed or expired"
    );
    ensure!(fee <= 100_000, "The network is very busy: peers currently need a fee above 0.1 NOID. Nothing was signed. Try again in a few minutes");
    let busy_fee = fee > noid_chain::consensus::fees::fee_breakdown(selected.len() as u64, 2, boundary.header.active_slot_count, boundary.header.log_slots).required_total;
    let change = total - payment - fee;
    let mut pages = Vec::new();
    for (index, chunk) in selected.chunks(8).enumerate() {
        let mut body = TxBody {
            epoch_anchor: noid_chain::block_header::block_id(&anchor),
            fee: if index == 0 { fee } else { 0 },
            input_owner: owner,
            inputs: [TxInput::dummy(); 8],
            outputs: [TxOutput::dummy(); 2],
            validity_bitmap: 0,
            is_coinbase: false,
        };
        for (offset, (slot, v)) in chunk.iter().enumerate() {
            body.inputs[offset] = TxInput {
                slot_index: *slot,
                amount: v.amount(),
                creation_id: v.creation_id(),
            };
            body.validity_bitmap |= 1 << offset;
        }
        if index == 0 {
            body.validity_bitmap |= PAGED_SPEND_START_BIT | output_bitmap_bit(0);
            body.outputs[0] = TxOutput {
                slot_index: empty[0],
                amount: payment,
                owner: recipient,
            };
            if change > 0 {
                body.validity_bitmap |= output_bitmap_bit(1);
                body.outputs[1] = TxOutput {
                    slot_index: empty[1],
                    amount: change,
                    owner,
                };
            }
        }
        if index + 1 == selected.len().div_ceil(8) {
            body.validity_bitmap |= PAGED_SPEND_END_BIT;
        }
        pages.push(TxPage::new(body).map_err(|e| anyhow::anyhow!("Payment layout: {e}"))?);
    }
    let unsigned = PagedSpendIntent::new(pages, vec![])
        .map_err(|e| anyhow::anyhow!("Payment layout: {e}"))?
        .to_bytes()
        .map_err(|e| anyhow::anyhow!("Payment encoding: {e}"))?;
    let review_id = hex::encode(Sha256::digest(&unsigned));
    Ok(
        json!({"review_id":review_id,"unsigned_intent":hex::encode(unsigned),"to":recipient.to_bech32(),"amount":amount(payment),"amount_micronoid":payment.to_string(),"fee":amount(fee),"busy_fee":busy_fee,"total":amount(payment+fee),"height":boundary.header.height,"balance_height":proof_height,"valid_through_height":epoch.checked_add(noid_chain::consensus::params::TX_EPOCH_BLOCKS).context("Payment epoch overflow")?,"using_saved_state":cached,"wallet":{"height":boundary.header.height,"balance_height":proof_height,"balance":amount(total_balance),"balance_micronoid":total_balance.to_string(),"block_hash":hex::encode(noid_chain::block_header::block_id(&boundary.header))}}),
    )
}
/// Every authorization states where the person approved it (audit 176; same rule as the iOS
/// Native core's `check_approval`). Fails closed, before the seed, key derivation or proving.
/// - "phone": approved on this phone's own review screen; must not carry watch fields.
/// - "watch": approved on the Wear OS watch; must carry exactly what that screen showed:
///   `confirmed_to`, `confirmed_amount_micronoid` and `confirmed_review_id`.
fn check_approval(args: &Value, intent: &PagedSpendIntent) -> Result<()> {
    const CONFIRMED: [&str; 3] = ["confirmed_to", "confirmed_amount_micronoid", "confirmed_review_id"];
    match args["approval_source"].as_str() {
        Some("watch") => {
            ensure!(
                CONFIRMED.iter().all(|k| args[*k].as_str().is_some_and(|v| !v.is_empty())),
                "The watch confirmation is incomplete. Nothing was signed."
            );
            let paid = intent.pages.first().map(|p| p.body.outputs[0]);
            let matches = paid.is_some_and(|out| {
                args["confirmed_to"].as_str() == Some(out.owner.to_bech32().as_str())
                    && args["confirmed_amount_micronoid"].as_str() == Some(out.amount.to_string().as_str())
            }) && args["confirmed_review_id"].as_str() == args["review_id"].as_str();
            ensure!(matches, "The confirmed address or amount differs from the payment. Nothing was signed.");
            Ok(())
        }
        Some("phone") => {
            ensure!(
                CONFIRMED.iter().all(|k| args[*k].is_null()),
                "A phone approval cannot carry a watch confirmation. Nothing was signed."
            );
            Ok(())
        }
        _ => anyhow::bail!("Approval source missing. Nothing was signed."),
    }
}

pub fn authorize(args: &Value) -> Result<Value> {
    let bytes = hex::decode(arg(args, "unsigned_intent")?)?;
    ensure!(
        bytes.len() <= noid_tx::MAX_PAGED_SPEND_INTENT_BYTES,
        "Payment exceeds wire limit"
    );
    ensure!(
        hex::encode(Sha256::digest(&bytes)) == arg(args, "review_id")?,
        "Payment differs from approved review"
    );
    let mut intent = PagedSpendIntent::from_bytes(&bytes)
        .map_err(|e| anyhow::anyhow!("Invalid reviewed payment: {e}"))?;
    ensure!(
        intent.authorization_bytes.is_empty(),
        "Review already contains an authorization"
    );
    check_approval(args, &intent)?;
    let master = seed(args)?;
    let secret = crate::courier_api::spend_secret(&master, account(args)?);
    let own = noid_poseidon2b::primitives::derive_address(&secret);
    ensure!(
        intent.pages.iter().all(|p| p.body.input_owner == own
            && p.body.validity_bitmap & noid_tx::PAGED_SPEND_V2_CONTRACT_MASK == 0),
        "This review is not an ordinary payment from your wallet"
    );
    if args["data_dir"].is_string() {
        let (tip, _, anchor) = cached_headers(args)?;
        ensure!(
            intent
                .pages
                .iter()
                .all(|p| p.body.epoch_anchor == noid_chain::block_header::block_id(&anchor)),
            "Payment epoch changed. Update your wallet and review the payment again."
        );
        if args["online"].as_bool() == Some(true) {
            let observed = crate::mobile::observed_height(Path::new(arg(args, "artifacts")?))?;
            require_current_payment_epoch(tip.height, observed)?;
        }
    }
    let auth = noid_gkr::wallet_authorization::prove_paged_spend_authorization(
        &intent.pages,
        noid_gkr::wallet_authorization::OwnerAuthWitness::new(secret),
    )
    .map_err(|e| anyhow::anyhow!("Authorization failed: {e}"))?;
    intent.authorization_bytes = auth
        .to_bytes()
        .map_err(|e| anyhow::anyhow!("Authorization encoding failed: {e}"))?;
    let mut inputs = Vec::new();
    let mut outputs = Vec::new();
    for page in &intent.pages {
        for (index, input) in page.body.inputs.iter().enumerate() {
            if page.body.validity_bitmap & (1 << index) != 0 {
                inputs.push(json!({"slot_index":input.slot_index,"creation_id":input.creation_id}));
            }
        }
        for (index, output) in page.body.outputs.iter().enumerate() {
            if page.body.validity_bitmap & output_bitmap_bit(index) != 0 {
                outputs.push(output.slot_index);
            }
        }
    }
    Ok(
        json!({"txid":hex::encode(intent.logical_txid().0),"intent_hex":hex::encode(intent.to_bytes().map_err(|e|anyhow::anyhow!("Payment encoding failed: {e}"))?),"reservation":{"epoch_anchor":hex::encode(intent.pages[0].body.epoch_anchor),"inputs":inputs,"outputs":outputs}}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn payment_epoch_guard_covers_boundary_and_stale_peers() {
        assert!(require_current_payment_epoch(142, 143).is_ok());
        assert!(require_current_payment_epoch(143, 144).is_err());
        assert!(require_current_payment_epoch(144, 145).is_ok());
        assert!(require_current_payment_epoch(287, 288).is_err());
        assert!(require_current_payment_epoch(288, 287).is_ok());
        assert!(require_current_payment_epoch(144, u64::MAX).is_err());
    }
    #[test]
    fn wallet_watch_tracks_coin_changes_and_ignores_empty_output_hints() {
        let address = noid_poseidon2b::primitives::derive_address(
            &noid_poseidon2b::primitives::SpendSecret::from_bytes([7; 32]),
        );
        let slot = Slot {
            slot_index: 5,
            value: 20,
            creation_id: 1,
            owner: address.to_bech32(),
            empty: false,
        };
        let original = owned_coin_set([slot.clone()], address).unwrap();
        let empty = Slot {
            slot_index: 6,
            value: 0,
            creation_id: 0,
            owner: String::new(),
            empty: true,
        };
        assert_eq!(
            original,
            owned_coin_set([empty, slot.clone()], address).unwrap()
        );
        let mut amount = slot.clone();
        amount.value += 1;
        let mut incarnation = slot.clone();
        incarnation.creation_id += 1;
        let mut index = slot.clone();
        index.slot_index += 1;
        for changed in [amount, incarnation, index] {
            assert_ne!(original, owned_coin_set([changed], address).unwrap());
        }
        assert_ne!(original, owned_coin_set([], address).unwrap());
        assert!(owned_coin_set([slot.clone(), slot], address).is_err());
    }
    #[test]
    fn unchanged_manual_and_automatic_refreshes_need_no_chain_download() {
        use std::io::{Read, Write};
        use std::net::TcpListener;
        use std::time::{Duration, Instant};

        let master = [7; 32];
        let address = noid_poseidon2b::primitives::derive_address(
            &crate::courier_api::spend_secret(&master, 0),
        );
        let slot = json!({"slot_index":5,"value":20,"creation_id":1,"owner":address.to_bech32(),"empty":false});
        let data = tempfile::tempdir().unwrap();
        let args = json!({"seed_hex":hex::encode(master)});
        let anchor = noid_chain::consensus::genesis::genesis_header();
        let mut tip = anchor;
        tip.height = 42;
        let checkpoint = crate::wallet_checkpoint::seal(&args, &tip, &tip, &anchor).unwrap();
        let proof = json!({"height":42,"block_hash":hex::encode(noid_chain::block_header::block_id(&tip)),"state_root":hex::encode(tip.state_root),"log_slots":4,"slots":[slot.clone()],"siblings":[]});
        std::fs::write(
            data.path().join("wallet-view.json"),
            serde_json::to_vec(&json!({"proofs":[proof.clone()],"checkpoint":checkpoint})).unwrap(),
        )
        .unwrap();
        let server = TcpListener::bind("127.0.0.1:0").unwrap();
        server.set_nonblocking(true).unwrap();
        let endpoint = format!("http://{}", server.local_addr().unwrap());
        let worker = std::thread::spawn(move || {
            for query in 0..5 {
                let deadline = Instant::now() + Duration::from_secs(5);
                let mut stream = loop {
                    match server.accept() {
                        Ok((stream, _)) => break stream,
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            assert!(Instant::now() < deadline, "Wallet hint request timed out");
                            std::thread::sleep(Duration::from_millis(5));
                        }
                        Err(error) => panic!("{error}"),
                    }
                };
                stream
                    .set_read_timeout(Some(Duration::from_secs(2)))
                    .unwrap();
                let mut header = Vec::new();
                while !header.ends_with(b"\r\n\r\n") {
                    assert!(header.len() < 16384);
                    header.push({
                        let mut byte = [0];
                        stream.read_exact(&mut byte).unwrap();
                        byte[0]
                    });
                }
                let header = String::from_utf8(header).unwrap();
                let length = header
                    .lines()
                    .find_map(|line| {
                        line.split_once(':')
                            .filter(|(name, _)| name.eq_ignore_ascii_case("content-length"))
                            .map(|(_, value)| value.trim().parse::<usize>().unwrap())
                    })
                    .unwrap();
                assert!(length < 16384);
                let mut body = vec![0; length];
                stream.read_exact(&mut body).unwrap();
                let request: Value = serde_json::from_slice(&body).unwrap();
                let result = if query == 2 || query == 4 {
                    assert_eq!(request["method"], "paranoid_getSlotProof");
                    assert_eq!(request["params"], json!([[5]]));
                    let mut hint = proof.clone();
                    hint["height"] = json!(if query == 4 { 144 } else { 43 });
                    hint
                } else {
                    assert_eq!(request["method"], "paranoid_getSlotsByOwner");
                    json!([slot.clone()])
                };
                let response = serde_json::to_vec(
                    &json!({"jsonrpc":"2.0","id":request["id"],"result":result}),
                )
                .unwrap();
                write!(stream, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", response.len()).unwrap();
                stream.write_all(&response).unwrap();
            }
        });
        for follow in [false, true] {
            let result = synchronize(&json!({"seed_hex":hex::encode(master),"data_dir":data.path(),"rpc":endpoint,"follow":follow})).unwrap();
            assert_eq!(result["deferred"], true);
            for field in ["balance", "height", "state_root", "observed_height"] {
                assert!(
                    result.get(field).is_none(),
                    "An unverified hint must not update {field}"
                );
            }
        }
        assert_eq!(wallet_change_hint(&json!({"seed_hex":hex::encode(master),"data_dir":data.path(),"rpc":endpoint,"follow":true}),address).unwrap(),Some(WalletHint::EpochChanged),"The automatic check must schedule verification when an epoch changes, even with the same coins");
        worker.join().unwrap();
    }
    #[test]
    fn completed_reservations_do_not_use_the_pending_payment_budget() {
        let anchor = [7; 32];
        let closed = json!({"epoch_anchor":hex::encode(anchor),"included_height":10,"inputs":[],"outputs":[]});
        let live = json!({"epoch_anchor":hex::encode(anchor),"inputs":[{"slot_index":4,"creation_id":9}],"outputs":[8]});
        let mut history = vec![closed; 200];
        history.push(live.clone());
        let (inputs, outputs) = reservations(&json!({"reservations":history}), anchor, 20).unwrap();
        assert_eq!(inputs, BTreeSet::from([(4, 9)]));
        assert_eq!(outputs, BTreeSet::from([8]));
        assert!(reservations(&json!({"reservations":vec![live;101]}), anchor, 20).is_err());
    }
    #[test]
    fn an_authorized_noid_packet_can_be_forwarded_but_not_retargeted() {
        let seed = [7; 32];
        let epoch_header = noid_chain::consensus::genesis::genesis_header();
        let epoch_anchor = noid_chain::block_header::block_id(&epoch_header);
        let owner = noid_poseidon2b::primitives::derive_address(&crate::courier_api::spend_secret(
            &seed, 0,
        ));
        let recipient = noid_poseidon2b::primitives::derive_address(
            &crate::courier_api::spend_secret(&[8; 32], 0),
        );
        let mut body = TxBody {
            epoch_anchor,
            fee: 10_000,
            input_owner: owner,
            inputs: [TxInput::dummy(); 8],
            outputs: [TxOutput::dummy(); 2],
            validity_bitmap: 1 | output_bitmap_bit(0) | PAGED_SPEND_START_BIT | PAGED_SPEND_END_BIT,
            is_coinbase: false,
        };
        body.inputs[0] = TxInput {
            slot_index: 5,
            amount: 1_000_000,
            creation_id: 1,
        };
        body.outputs[0] = TxOutput {
            slot_index: 7,
            amount: 990_000,
            owner: recipient,
        };
        let unsigned = PagedSpendIntent::new(vec![TxPage::new(body).unwrap()], vec![])
            .unwrap()
            .to_bytes()
            .unwrap();
        let saved = tempfile::tempdir().unwrap();
        let args = json!({"seed_hex":hex::encode(seed),"unsigned_intent":hex::encode(&unsigned),"review_id":hex::encode(Sha256::digest(&unsigned)),"data_dir":saved.path(),"online":false,"approval_source":"phone"});
        let mut tip = epoch_header;
        tip.height = 143;
        let checkpoint = crate::wallet_checkpoint::seal(&args, &tip, &tip, &epoch_header).unwrap();
        std::fs::write(
            saved.path().join("wallet-view.json"),
            serde_json::to_vec(&json!({"checkpoint":checkpoint})).unwrap(),
        )
        .unwrap();
        let authorized = authorize(&args).unwrap();
        // Audit 176: a Wear OS approval of the same payment (exactly what the watch showed) also signs.
        let mut watch = args.clone();
        watch["approval_source"] = json!("watch");
        watch["confirmed_to"] = json!(recipient.to_bech32());
        watch["confirmed_amount_micronoid"] = json!("990000");
        watch["confirmed_review_id"] = args["review_id"].clone();
        let watched = authorize(&watch).unwrap();
        assert_eq!(watched["txid"], authorized["txid"]);
        watch["confirmed_amount_micronoid"] = json!("990001");
        assert!(authorize(&watch).unwrap_err().to_string().contains("Nothing was signed"));

        // The same review must be rejected after an authenticated epoch change.
        tip.height = 144;
        let checkpoint = crate::wallet_checkpoint::seal(&args, &tip, &tip, &tip).unwrap();
        std::fs::write(
            saved.path().join("wallet-view.json"),
            serde_json::to_vec(&json!({"checkpoint":checkpoint})).unwrap(),
        )
        .unwrap();
        assert!(authorize(&args)
            .unwrap_err()
            .to_string()
            .starts_with("Payment epoch changed."));
        let reservation = &authorized["reservation"];
        let args = json!({"reservations":[reservation]});
        let (reserved_inputs, reserved_outputs) = reservations(&args, epoch_anchor, 10).unwrap();
        assert!(reserved_inputs.contains(&(5, 1)));
        assert!(!reserved_inputs.contains(&(5, 2)));
        assert!(reserved_outputs.contains(&7));
        assert!(reservations(&args, [4; 32], 10).unwrap().0.is_empty());
        let included = json!({"reservations":[{"epoch_anchor":hex::encode(epoch_anchor),"inputs":reservation["inputs"],"outputs":reservation["outputs"],"included_height":20}]});
        assert!(!reservations(&included, epoch_anchor, 19)
            .unwrap()
            .0
            .is_empty());
        assert!(reservations(&included, epoch_anchor, 20)
            .unwrap()
            .0
            .is_empty());
        let preview =
            crate::courier_api::inspect_intent(authorized["intent_hex"].as_str().unwrap()).unwrap();
        assert_eq!(preview["txid"], authorized["txid"]);
        assert_eq!(preview["outputs"][0]["address"], recipient.to_bech32());
        let mut altered = PagedSpendIntent::from_bytes(
            &hex::decode(authorized["intent_hex"].as_str().unwrap()).unwrap(),
        )
        .unwrap();
        altered.pages[0].body.outputs[0].owner = owner;
        assert!(
            crate::courier_api::inspect_intent(&hex::encode(altered.to_bytes().unwrap())).is_err()
        );
    }
    #[test]
    fn spendable_and_empty_slots_are_bound_to_the_same_verified_boundary() {
        use noid_chain::{
            exact_state_hash::slot_leaf_hash,
            sparse_merkle::{build_multiproof, SparseMerkleCache},
        };
        let address = noid_poseidon2b::primitives::derive_address(
            &crate::courier_api::spend_secret(&[7; 32], 0),
        );
        let value = SlotValue {
            value: Block128(123_000_000 | (1u128 << 64)),
            owner_hi: Block128(u128::from_le_bytes(address.0[..16].try_into().unwrap())),
            owner_lo: Block128(u128::from_le_bytes(address.0[16..].try_into().unwrap())),
        };
        let cache = SparseMerkleCache::from_leaves(4, &[(0, slot_leaf_hash(value))]).unwrap();
        let mut header = noid_chain::consensus::genesis::genesis_header();
        header.log_slots = 4;
        header.state_root = cache.root();
        let frontier = build_multiproof(&cache, &[0, 1], 4).unwrap();
        let proof = Proof {
            height: header.height,
            block_hash: hex::encode(noid_chain::block_header::block_id(&header)),
            state_root: hex::encode(header.state_root),
            log_slots: 4,
            slots: vec![
                Slot {
                    slot_index: 0,
                    value: 123_000_000,
                    creation_id: 1,
                    owner: address.to_bech32(),
                    empty: false,
                },
                Slot {
                    slot_index: 1,
                    value: 0,
                    creation_id: 0,
                    owner: String::new(),
                    empty: true,
                },
            ],
            siblings: frontier.siblings.iter().map(hex::encode).collect(),
        };
        let checked = verify_proof(&header, &proof).unwrap();
        assert_eq!(checked[&0], value);
        assert_eq!(checked[&1], SlotValue::EMPTY);
        for mutation in 0..5 {
            let mut altered = proof.clone();
            match mutation {
                0 => altered.slots[0].value += 1,
                1 => altered.slots[1].empty = false,
                2 => altered.block_hash = "00".repeat(32),
                3 => altered.slots.reverse(),
                _ => altered.siblings.push("00".repeat(32)),
            };
            assert!(verify_proof(&header, &altered).is_err());
        }
    }
    #[test]
    fn changed_payment_review_is_rejected_before_authorization() {
        let result = authorize(
            &json!({"unsigned_intent":"00","review_id":"00".repeat(32),"seed_hex":"07".repeat(32),"approval_source":"phone"}),
        );
        assert!(result
            .unwrap_err()
            .to_string()
            .contains("differs from approved review"));
    }
    // Audit 176 (same cases as the iOS Native tests). No proving: every refusal happens first.
    fn approval_fixture(source: Option<&str>) -> (Value, String, String) {
        let seed = [7; 32];
        let owner = noid_poseidon2b::primitives::derive_address(&crate::courier_api::spend_secret(&seed, 0));
        let recipient = noid_poseidon2b::primitives::derive_address(&crate::courier_api::spend_secret(&[8; 32], 0));
        let mut body = TxBody {
            epoch_anchor: [3; 32],
            fee: 10_000,
            input_owner: owner,
            inputs: [TxInput::dummy(); 8],
            outputs: [TxOutput::dummy(); 2],
            validity_bitmap: 1 | output_bitmap_bit(0) | PAGED_SPEND_START_BIT | PAGED_SPEND_END_BIT,
            is_coinbase: false,
        };
        body.inputs[0] = TxInput { slot_index: 5, amount: 1_000_000, creation_id: 1 };
        body.outputs[0] = TxOutput { slot_index: 7, amount: 990_000, owner: recipient };
        let unsigned = PagedSpendIntent::new(vec![TxPage::new(body).unwrap()], vec![]).unwrap().to_bytes().unwrap();
        // A seed that is not valid hex: a request that passes the approval check fails at the seed,
        // so these tests prove the order (approval first) without any proving or storage.
        let mut args = json!({"seed_hex":"not-a-seed","unsigned_intent":hex::encode(&unsigned),"review_id":hex::encode(Sha256::digest(&unsigned))});
        if let Some(source) = source { args["approval_source"] = json!(source); }
        (args, recipient.to_bech32(), "990000".into())
    }
    fn watch_fields(args: &mut Value, to: &str, amount: &str) {
        args["confirmed_to"] = json!(to);
        args["confirmed_amount_micronoid"] = json!(amount);
        args["confirmed_review_id"] = args["review_id"].clone();
    }
    fn refused(args: &Value) -> String { authorize(args).unwrap_err().to_string() }
    #[test]
    fn approval_watch_without_confirmation_signs_nothing() {
        let (args, _, _) = approval_fixture(Some("watch"));
        assert!(refused(&args).contains("Nothing was signed"));
    }
    #[test]
    fn approval_watch_with_any_field_missing_empty_or_wrong_is_refused() {
        for key in ["confirmed_to", "confirmed_amount_micronoid", "confirmed_review_id"] {
            for value in [Value::Null, json!(""), json!(1), json!("o1wrong")] {
                let (mut args, to, amount) = approval_fixture(Some("watch"));
                watch_fields(&mut args, &to, &amount);
                args[key] = value.clone();
                assert!(refused(&args).contains("Nothing was signed"), "{key}={value}");
            }
        }
    }
    #[test]
    fn approval_missing_or_unknown_source_is_refused_first() {
        for source in [None, Some(""), Some("Phone"), Some("wear"), Some("watch ")] {
            let (args, _, _) = approval_fixture(source);
            assert!(refused(&args).contains("Approval source missing"), "{source:?}");
        }
        let (mut args, _, _) = approval_fixture(None);
        args["approval_source"] = json!(true);
        assert!(refused(&args).contains("Approval source missing"));
    }
    #[test]
    fn approval_phone_cannot_carry_watch_fields() {
        for key in ["confirmed_to", "confirmed_amount_micronoid", "confirmed_review_id"] {
            let (mut args, _, _) = approval_fixture(Some("phone"));
            args[key] = json!("x");
            assert!(refused(&args).contains("Nothing was signed"), "{key}");
        }
    }
    #[test]
    fn approval_correct_phone_and_watch_pass_the_check() {
        // Both reach the next step (the seed), so the approval check accepted them.
        let (phone, to, amount) = approval_fixture(Some("phone"));
        assert!(!refused(&phone).contains("Nothing was signed"));
        let (mut watch, _, _) = approval_fixture(Some("watch"));
        watch_fields(&mut watch, &to, &amount);
        assert!(!refused(&watch).contains("Nothing was signed"));
    }
}
