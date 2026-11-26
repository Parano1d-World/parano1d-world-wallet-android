//! Strict ordinary-NOID review and local authorization. No networking.
use serde_json::{json,Value};
use sha2::{Digest,Sha256};
use noid_tx::PagedSpendIntent;
use noid_poseidon2b::primitives::{Address,derive_address};
use zeroize::Zeroizing;
use std::collections::BTreeSet;
fn text<'a>(v:&'a Value,k:&str)->Result<&'a str,String>{v[k].as_str().ok_or_else(||format!("Missing {k}"))}
fn integer(v:&Value,k:&str)->Result<u64,String>{let s=text(v,k)?;let n=s.parse::<u64>().map_err(|_|"Invalid exact amount")?;if n.to_string()!=s{return Err("Noncanonical amount".into())}Ok(n)}
/// Mirrors pinned Paramo1d inspect_intent: owner authorization, not settlement.
pub fn inspect_signed(encoded:&str)->Result<Value,String>{
    use noid_gkr::wallet_authorization::{WalletAuthorizationBundle,verify_paged_spend_authorization};
    if encoded.len()>2*noid_tx::MAX_PAGED_SPEND_INTENT_BYTES{return Err("Payment exceeds wire limit".into());}
    let intent=PagedSpendIntent::from_bytes(&hex::decode(encoded).map_err(|_|"Invalid payment encoding")?).map_err(|e|e.to_string())?;
    if intent.pages.is_empty() || intent.pages.len()>63 || intent.pages.iter().any(|p|p.body.validity_bitmap & (noid_tx::PAGED_SPEND_V2_CONTRACT_MASK|noid_tx::native_assets::wire::NATIVE_HEADER_BIT|noid_tx::native_assets::wire::NATIVE_PAGE_BIT)!=0){return Err("Only ordinary NOID payments are supported".into());}
    let authorization=WalletAuthorizationBundle::from_bytes(&intent.authorization_bytes).map_err(|e|e.to_string())?;
    verify_paged_spend_authorization(&intent.pages,&authorization).map_err(|e|e.to_string())?;
    let fee=intent.pages.iter().try_fold(0u64,|sum,p|sum.checked_add(p.body.fee)).ok_or("Fee overflow")?;
    let outputs=intent.pages.iter().flat_map(|p|p.body.live_outputs().map(|(_,o)|json!({"address":o.owner.to_bech32(),"amount_micronoid":o.amount.to_string()}))).collect::<Vec<_>>();
    Ok(json!({"owner":intent.pages[0].body.input_owner.to_bech32(),"authorized":true,"settled":false,"txid":hex::encode(intent.logical_txid().0),"fee_micronoid":fee.to_string(),"outputs":outputs,"epoch_anchor":hex::encode(intent.pages[0].body.epoch_anchor)}))
}
fn reviewed(request:&Value)->Result<(PagedSpendIntent,Value),String> {
    let encoded=text(request,"unsigned_intent")?;
    if encoded.len()>2*noid_tx::MAX_PAGED_SPEND_INTENT_BYTES{return Err("Payment too large".into());}
    let bytes=hex::decode(encoded).map_err(|_|"Invalid payment encoding")?;
    let digest=hex::encode(Sha256::digest(&bytes));
    if text(request,"review_id")?!=digest{return Err("Payment differs from approved review".into());}
    let intent=PagedSpendIntent::from_bytes(&bytes).map_err(|e|e.to_string())?;
    if !intent.authorization_bytes.is_empty(){return Err("Payment is already signed".into());}
    let owner=Address::parse(text(request,"owner")?).map_err(|_|"Invalid owner")?;
    let to=Address::parse(text(request,"to")?).map_err(|_|"Invalid recipient")?;
    let amount=integer(request,"amount_micronoid")?;let expected_fee=integer(request,"fee_micronoid")?;
    if amount==0 || expected_fee>integer(request,"maximum_fee_micronoid")?{return Err("Amount or fee is outside approval".into());}
    let mut input_sum=0u64;let mut output_sum=0u64;let mut fee=0u64;let mut paid=0u64;let mut inputs=BTreeSet::new();let mut outputs=BTreeSet::new();
    for (page_index,page) in intent.pages.iter().enumerate() {
        let body=&page.body;
        if body.input_owner!=owner || body.validity_bitmap & (noid_tx::PAGED_SPEND_V2_CONTRACT_MASK|noid_tx::native_assets::wire::NATIVE_HEADER_BIT|noid_tx::native_assets::wire::NATIVE_PAGE_BIT)!=0 {return Err("Not an ordinary payment from this wallet".into());}
        fee=fee.checked_add(body.fee).ok_or("Fee overflow")?;
        for (_,input) in body.live_inputs() {
            if !inputs.insert(input.slot_index){return Err("Repeated input slot".into());}
            input_sum=input_sum.checked_add(input.amount).ok_or("Input overflow")?;
        }
        for (index,output) in body.live_outputs() {
            if page_index!=0 || !outputs.insert(output.slot_index){return Err("Unexpected payment outputs".into());}
            if index==0 {
                if output.owner!=to || output.amount!=amount{return Err("Payment recipient or amount changed".into());}
                paid=output.amount;
            }else if output.owner!=owner{return Err("Change is not returned to this wallet".into());}
            output_sum=output_sum.checked_add(output.amount).ok_or("Output overflow")?;
        }
    }
    if paid!=amount || fee!=expected_fee || output_sum.checked_add(fee)!=Some(input_sum) || outputs.iter().any(|o|inputs.contains(o)){return Err("Payment accounting differs from approval".into());}
    let total=amount.checked_add(fee).ok_or("Payment total overflow")?;
    let review=json!({"txid":hex::encode(intent.logical_txid().0),"owner":owner.to_bech32(),"to":to.to_bech32(),"amount_micronoid":amount.to_string(),"fee_micronoid":fee.to_string(),"total_micronoid":total.to_string(),"review_id":digest});
    Ok((intent,review))
}
pub fn run(request:&Value,secret:Option<&[u8]>)->Result<Value,String>{
    let (mut intent,review)=reviewed(request)?;
    if request["op"]=="payment_review" {return Ok(review);}
    // An approval made on another screen (Apple Watch) carries what that person saw and
    // confirmed. The core refuses to sign unless it is exactly this payment.
    if !request["confirmed_to"].is_null() || !request["confirmed_amount_micronoid"].is_null() || !request["confirmed_review_id"].is_null() {
        if request["confirmed_to"]!=review["to"] || request["confirmed_amount_micronoid"]!=review["amount_micronoid"]
            || request["confirmed_review_id"]!=review["review_id"] {
            return Err("The confirmed address or amount differs from the payment. Nothing was signed.".into());
        }
    }
    let seed=Zeroizing::new(<[u8;32]>::try_from(secret.ok_or("Missing secret")?).map_err(|_|"Invalid secret length")?);
    let index=request["account_index"].as_u64().filter(|n|*n<=100000).ok_or("Invalid account index")? as u32;
    let key=crate::derive_secret(&seed,index);
    if derive_address(&key).to_bech32()!=text(&review,"owner")?{return Err("Wrong wallet account".into());}
    let txid=text(&review,"txid")?;
    let mut journal=crate::payment_journal::Journal::open(std::path::Path::new(text(request,"journal_directory")?))?;
    // Reserve exactly the coins and output slots this payment uses (read from its bytes).
    let claims=crate::payment_journal::noid_claims(&hex::decode(text(request,"unsigned_intent")?).map_err(|_|"Invalid payment encoding")?)?;
    let saved=journal.begin_claims(text(&review,"review_id")?,txid,text(request,"chain_id")?,text(&review,"owner")?,claims)?;
    journal.note_noid_transfer(txid,text(&review,"to")?,text(&review,"amount_micronoid")?,text(&review,"fee_micronoid")?)?;
    if let Some(saved)=saved{return Ok(json!({"txid":txid,"intent_hex":saved,"resumed":true}));}
    use noid_gkr::wallet_authorization::{prove_paged_spend_authorization,verify_paged_spend_authorization,OwnerAuthWitness};
    let auth=prove_paged_spend_authorization(&intent.pages,OwnerAuthWitness::new(key)).map_err(|e|e.to_string())?;
    verify_paged_spend_authorization(&intent.pages,&auth).map_err(|e|e.to_string())?;
    intent.authorization_bytes=auth.to_bytes().map_err(|e|e.to_string())?;
    let bytes=intent.to_bytes().map_err(|e|e.to_string())?;
    if request["offline_delivery"]==true {journal.authorized_offline(&bytes)?;} else {journal.authorized(&bytes)?;}
    Ok(json!({"txid":txid,"intent_hex":hex::encode(bytes),"resumed":false}))
}

#[cfg(test)] mod tests {
 use super::*;
 fn fixture()->Value {
    let own=derive_address(&crate::derive_secret(&[1;32],3));let to=derive_address(&crate::derive_secret(&[2;32],0));
    let mut body=noid_tx::TxBody{epoch_anchor:[1;32],fee:10,input_owner:own,inputs:[noid_tx::TxInput::dummy();8],outputs:[noid_tx::TxOutput::dummy();2],validity_bitmap:1|noid_tx::output_bitmap_bit(0)|noid_tx::output_bitmap_bit(1)|noid_tx::PAGED_SPEND_START_BIT|noid_tx::PAGED_SPEND_END_BIT,is_coinbase:false};
    body.inputs[0]=noid_tx::TxInput{slot_index:1,amount:1_000_000,creation_id:1};
    body.outputs[0]=noid_tx::TxOutput{slot_index:2,amount:600_000,owner:to};
    body.outputs[1]=noid_tx::TxOutput{slot_index:3,amount:399_990,owner:own};
    let intent=PagedSpendIntent::new(vec![noid_tx::TxPage::new(body).unwrap()],vec![]).unwrap();let bytes=intent.to_bytes().unwrap();
    json!({"op":"payment_review","unsigned_intent":hex::encode(&bytes),"review_id":hex::encode(Sha256::digest(&bytes)),"owner":own.to_bech32(),"to":to.to_bech32(),"amount_micronoid":"600000","fee_micronoid":"10","maximum_fee_micronoid":"1000","account_index":3})
 }
 #[test] fn exact_payment_review_has_correct_recipient_amount_fee_and_identity(){let req=fixture();let result=run(&req,None).unwrap();assert_eq!(result["total_micronoid"],"600010");assert_eq!(result["to"],req["to"]);assert_eq!(result["owner"],req["owner"]);assert_eq!(result["txid"].as_str().unwrap().len(),64);}
 #[test] fn watch_confirmation_must_match_exactly(){
        let mut req=fixture();req["op"]=json!("payment_authorize");req["chain_id"]=json!("00".repeat(32));
        req["journal_directory"]=json!(std::env::temp_dir().join(format!("watch-confirm-{}",std::process::id())).to_string_lossy());
        let (_,review)=reviewed(&req).unwrap();
        for (to,amount,id) in [(json!(derive_address(&crate::derive_secret(&[9;32],0)).to_bech32()),review["amount_micronoid"].clone(),review["review_id"].clone()),
                               (review["to"].clone(),json!("600001"),review["review_id"].clone()),
                               (review["to"].clone(),review["amount_micronoid"].clone(),json!("00".repeat(32)))] {
            let mut bad=req.clone();bad["confirmed_to"]=to;bad["confirmed_amount_micronoid"]=amount;bad["confirmed_review_id"]=id;
            assert!(run(&bad,Some(&[1;32])).unwrap_err().contains("Nothing was signed"));
        }
    }
 #[test] fn rejects_recipient_amount_fee_and_review_substitution(){for (key,value) in [("to",json!(derive_address(&crate::derive_secret(&[9;32],0)).to_bech32())),("amount_micronoid",json!("1")),("fee_micronoid",json!("11")),("maximum_fee_micronoid",json!("9")),("review_id",json!("00".repeat(32)))] {let mut req=fixture();req[key]=value;assert!(run(&req,None).is_err(),"{key}");}}
 #[test] fn rejects_wrong_account_before_proving_or_journal_creation(){let mut req=fixture();req["op"]=json!("payment_authorize");req["account_index"]=json!(0);assert!(run(&req,Some(&[1;32])).unwrap_err().contains("Wrong wallet account"));}
 #[test] fn offline_authorization_is_not_settlement_and_tampering_is_rejected(){
    use noid_gkr::wallet_authorization::{prove_paged_spend_authorization,OwnerAuthWitness};
    let req=fixture();let encoded=req["unsigned_intent"].as_str().unwrap();
    assert!(inspect_signed(encoded).is_err());
    let mut intent=PagedSpendIntent::from_bytes(&hex::decode(encoded).unwrap()).unwrap();
    let auth=prove_paged_spend_authorization(&intent.pages,OwnerAuthWitness::new(crate::derive_secret(&[1;32],3))).unwrap();
    intent.authorization_bytes=auth.to_bytes().unwrap();
    let checked=inspect_signed(&hex::encode(intent.to_bytes().unwrap())).unwrap();
    assert_eq!(checked["authorized"],true);assert_eq!(checked["settled"],false);
    assert_eq!(checked["txid"],hex::encode(intent.logical_txid().0));
    // Exercise upstream encrypted Transaction -> iOS recipient adapter, with
    // no RPC, submission or blockchain state supplied to the recipient.
    use paramo1d_courier::{Identity,Destination,Message};
    let sender=Identity::from_seed(&[1;32]);let recipient=Identity::from_seed(&[2;32]);
    let sender_card=sender.contact("Sender".into(),String::new()).unwrap();
    let recipient_card=recipient.contact("Recipient".into(),String::new()).unwrap();
    let envelope=sender.seal(sender_card.clone(),Destination::Direct{contact:recipient_card},Message::Transaction{intent_hex:hex::encode(intent.to_bytes().unwrap())},1_800_000_000_000).unwrap();
    assert!(serde_json::to_vec(&envelope).unwrap().len()<=paramo1d_courier::MAX_ENVELOPE);
    let opened=crate::messaging::run(&json!({"op":"chat_open","envelope":envelope,"contact":sender_card,"now_ms":1_800_000_000_000u64}),Some(&[2;32])).unwrap();
    assert_eq!(opened["payment"]["txid"],checked["txid"]);
    assert_eq!(opened["payment"]["settled"],false);
    intent.pages[0].body.outputs[0].owner=derive_address(&crate::derive_secret(&[9;32],0));
    assert!(inspect_signed(&hex::encode(intent.to_bytes().unwrap())).is_err());
 }
 #[test] fn changed_outputs_cannot_hide_under_recomputed_review_hash(){let mut req=fixture();let mut intent=PagedSpendIntent::from_bytes(&hex::decode(req["unsigned_intent"].as_str().unwrap()).unwrap()).unwrap();intent.pages[0].body.outputs[1].owner=derive_address(&crate::derive_secret(&[9;32],0));let bytes=intent.to_bytes().unwrap();req["unsigned_intent"]=json!(hex::encode(&bytes));req["review_id"]=json!(hex::encode(Sha256::digest(&bytes)));assert!(run(&req,None).unwrap_err().contains("Change"));}
}
