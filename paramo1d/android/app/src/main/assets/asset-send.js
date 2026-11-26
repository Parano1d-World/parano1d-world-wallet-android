'use strict';
// Token and NFT sends (iOS 165 planner + wallet core, see AssetSend.java). Same steps as iPhone:
// recipient → verified plan and canonical review → confirm the full address and amount → approve
// within 60 seconds → signed → submitted. Pending transfers stay reserved until verified settlement.
const AssetSendUI = (() => {
  let pending = { entries: [], loaded: 0 }, reconcileAt = 0;
  const lockedNft = (collection, serial) => pending.entries.some(e => e.state === 'authorized' || e.state === 'preparing'
    ? e.request?.action === 'transfer_nft' && e.request?.collection_id === collection && String(e.request?.serial) === String(serial) : false);
  const pendingToken = asset => pending.entries.some(e => (e.state === 'authorized' || e.state === 'preparing') && e.request?.asset_id === asset);
  async function refreshPending(force) {
    if (!state?.profile || (!force && Date.now() - pending.loaded < 15000)) return;
    pending.loaded = Date.now();
    try { pending = { ...(await native('asset_pending')), loaded: Date.now() }; scheduleAssetRender(); } catch { }
    // Release reservations only on verified evidence (finalized receipt or verified expiry), at most every 2 minutes.
    if (pending.entries.some(e => e.state === 'authorized') && state?.connection?.online && Date.now() > reconcileAt) {
      reconcileAt = Date.now() + 120000;
      native('asset_reconcile').then(r => { if (r?.pending) { pending = { ...r.pending, loaded: Date.now() }; scheduleAssetRender(); } }).catch(() => {});
    }
  }
  function toUnits(text) {
    const value = String(text).trim().replace(',', '.');
    if (!/^\d+(?:\.\d{1,6})?$/.test(value) || !/[1-9]/.test(value)) throw new Error('Enter an amount greater than zero, with up to six decimal places.');
    const [whole, frac = ''] = value.split('.');
    return (BigInt(whole) * 1000000n + BigInt(frac.padEnd(6, '0'))).toString();
  }

  /** item: {type:'nft', collection_id, serial} or {type:'token', asset_id, units}; media from AssetIndex. */
  function show(item, media) {
    if (!state?.backup_saved) { modal('Save a backup first', [...blockedNotices()], 'SEND'); return; }
    const nft = item.type === 'nft';
    if (nft && lockedNft(item.collection_id, item.serial)) { toast('This NFT has a pending transfer and is locked until it settles.'); return; }
    const address = field('Recipient NOID address', { placeholder: 'o1…' });
    const amount = nft ? null : field(`Amount · ${media?.symbol || media?.name || 'token'} (you have ${micro(item.units)})`, { placeholder: '0.00' });
    if (amount) amount.input.inputMode = 'decimal';
    const go = element('button', { class: 'primary-button', text: 'Review transfer ↗', onclick: () => review(item, media, address.input.value.trim(), amount?.input.value, go) });
    // iOS 166: bring token/NFT data up to the verified block straight away, instead of at review time.
    const ready = element('p', { class: 'card-note', text: 'Getting tokens and NFTs ready…' });
    if (state?.connection?.online) native('asset_sync').then(() => { ready.textContent = 'Tokens and NFTs are up to date with the verified block.'; }).catch(() => { ready.textContent = 'Couldn’t update tokens and NFTs yet. Open Settings → Network check.'; ready.onclick = showNetworkCheck; });
    else ready.textContent = 'Offline: transfers can be reviewed once you reconnect.';
    const art = media?.thumbnail ? element('img', { class: 'send-asset-art', src: media.thumbnail, alt: '' }) : element('span');
    modal(nft ? 'Send NFT' : `Send ${media?.symbol || media?.name || 'token'}`, [...blockedNotices().slice(0, 2),
      element('div', { class: 'send-asset-head' }, art, element('span', {}, element('strong', { text: media?.name || (nft ? `NFT #${item.serial}` : 'Token') }), element('small', { text: nft ? `No. ${item.serial}` : 'Token transfer' }))),
      scannedField('address', address), amount ? amount.label : element('span'), ready,
      element('p', { class: 'card-note', text: 'Your phone plans this transfer from its own verified State, checks peers’ pending transactions, and shows the exact review before anything is signed.' }), go], 'SEND');
  }

  let reviewButton = null;
  window.parano1dReviewStep = text => { if (reviewButton && typeof text === 'string') reviewButton.textContent = text; };
  async function review(item, media, to, amountText, button) {
    const twin = to && lookalikeAddress(to);
    if (twin && !confirmedLookalikes.has(to)) { showLookalikeCheck(to, twin, () => review(item, media, to, amountText, null)); return; }
    await run(button, async () => {
      if (!/^o1[0-9a-z]{20,126}$/.test(to)) throw new Error('Enter the recipient’s full NOID address.');
      if (to === state.profile.address) throw new Error('That is this wallet’s own address.');
      const request = item.type === 'nft' ? { action: 'transfer_nft', collection_id: item.collection_id, serial: String(item.serial), recipient: to }
        : { action: 'transfer_token', asset_id: item.asset_id, units: toUnits(amountText), recipient: to };
      if (request.units && BigInt(request.units) > BigInt(item.units || '0')) throw new Error('That is more than this wallet holds.');
      // iOS 178: the button says which step the review is on; each review still checks the chain before signing.
      reviewButton = button;
      if (button && state.chain?.running) button.textContent = 'Waiting for the node’s current chain check to finish…';
      try { const reviewed = await native('asset_send_prepare', request); confirmStep(item, media, request, reviewed); }
      finally { reviewButton = null; }
    }, state.chain?.running ? 'Waiting for the node’s current chain check to finish…' : 'Checking the latest block…');
  }

  function confirmStep(item, media, request, reviewed) {
    const deadline = Date.now() + (reviewed.expires_in_ms || 60000);
    const fee = reviewed.review?.fee_micronoid;
    const what = request.action === 'transfer_nft' ? `${media?.name || 'NFT'} · No. ${request.serial}` : `${micro(request.units)} ${media?.symbol || media?.name || 'tokens'}`;
    const check = element('input', { type: 'checkbox', id: 'asset-confirm' });
    const timer = element('small', { class: 'card-note' });
    const approve = element('button', { class: 'primary-button', text: 'Approve and send', onclick: () => run(approve, async () => {
      if (!check.checked) throw new Error('Confirm the address and amount first.');
      if (Date.now() > deadline) throw new Error('Review expired. Review again before approving.');
      const sent = await native('asset_send_approve', { review_hash: reviewed.review_hash, confirmed_to: request.recipient, display_amount: what, display_fee: fee ? `${micro(fee)} NOID` : '' });
      $('modal').close();
      if (sent.submitted) toast(request.action === 'transfer_nft' ? 'NFT sent. It stays locked until the transfer is verified.' : 'Tokens sent. Settlement is not verified yet.');
      else modal('Signed and saved', [element('p', { text: sent.error || 'The transfer is signed and reserved. It will be resubmitted unchanged.' })], 'SEND');
      refreshPending(true);
    }, 'Signing…') });
    approve.disabled = true;
    check.addEventListener('change', () => { approve.disabled = !check.checked; });
    const tick = () => { const left = Math.max(0, Math.ceil((deadline - Date.now()) / 1000)); timer.textContent = left ? `Review valid for ${left} s` : 'Review expired. Close and review again.'; if (!left) approve.disabled = true; else if ($('modal').open) setTimeout(tick, 1000); };
    modal('Check before you send', [
      element('div', { class: 'review-row' }, element('span', { text: 'Sending' }), element('span', { text: what })),
      element('div', { class: 'review-row column' }, element('span', { text: 'To' }), element('span', { class: 'addr' }, highlightedAddress(request.recipient))),
      element('div', { class: 'review-row' }, element('span', { text: 'Network fee' }), element('span', { text: fee ? `${micro(fee)} NOID${usdValue(micro(fee)) ? ' · ≈ ' + usdValue(micro(fee)) : ''}` : '—' })),
      element('div', { class: 'review-row' }, element('span', { text: 'Transaction' }), element('span', { class: 'code', text: `${String(reviewed.review?.txid || '').slice(0, 12)}…` })),
      element('label', { class: 'confirm-row', for: 'asset-confirm' }, check, element('span', { text: 'The full address and amount are correct.' })),
      timer, approve,
      element('p', { class: 'card-note', text: 'Checked against your phone’s verified chain. Once signed, it stays reserved until the network’s verified receipt or the transfer’s verified expiry.' })], 'REVIEW');
    tick();
  }
  return { show, refreshPending, lockedNft, pendingToken };
})();
