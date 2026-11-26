'use strict';

const $ = (id) => document.getElementById(id);
const pending = new Map();
let requestNumber = 0;
let state = null;
let tab = 'wallet'; // Parano1d: Home first, like iOS
let lastChatTab = 'groups';
let chat = 'g:parano1d';
let polling = false;
let pollTimer;
let walletBusy = false;
const readingChats = new Set();
let reportedChat;
let scanNumber = 0, scanContext = null;
const shownVpnHelp = new Set();

function native(op, args = {}) {
  return new Promise((resolve, reject) => {
    if (!window.Paramo1d?.request) {
      reject(new Error('Open Parano1d on Android to connect your local identity.'));
      return;
    }
    const id = String(++requestNumber);
    const timeout = setTimeout(() => {
      pending.delete(id);
      reject(new Error('This operation is taking longer than expected.'));
    // Operations that run the chain or ask for a fresh approval (fingerprint, phone screen lock or app code) get longer.
    }, ['sync', 'payment_preview', 'payment_send', 'receipt_verify', 'asset_send_approve', 'asset_send_prepare', 'app_code_set', 'app_code_erase', 'wallet_key_reveal',
      'accounts_select', 'accounts_add', 'accounts_default', 'accounts_remove', 'accounts_remove_root', 'accounts_scan', 'wallet_import', 'wallet_create_finish', 'mining_pair', 'mining_command'].includes(op) ? 300000 : 45000);
    pending.set(id, { resolve, reject, timeout });
    window.Paramo1d.request(id, JSON.stringify({ op, ...args }));
  });
}

window.paramo1dReply = (id, response) => {
  const call = pending.get(id);
  if (!call) return;
  clearTimeout(call.timeout);
  pending.delete(id);
  if (response.ok) call.resolve(response.result);
  else call.reject(new Error(response.error || 'The operation could not be completed.'));
};
window.paramo1dUpdate = (updated) => { state = updated; render(); };
window.paramo1dKeyboard = (visible) => {
  document.body.classList.toggle('keyboard-open', !!visible);
  ChatUI.layoutChanged();
};

function element(tag, attributes = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attributes)) {
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value);
  }
  for (const child of children.flat()) if (child != null) node.append(typeof child === 'string' ? document.createTextNode(child) : child);
  if (tag === 'button' && attributes.class && typeof attributes.text === 'string' && /(?:primary|outline|text)-button/.test(attributes.class)) {
    const match = attributes.text.match(/\s*([↗↓↻↑])$/);
    if (match) { node.textContent = attributes.text.slice(0, match.index); node.append(icon({ '↗': 'outgoing', '↓': 'receive', '↻': 'refresh', '↑': 'send' }[match[1]])); }
  }
  return node;
}

function icon(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.classList.add('icon'); svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', `icons.svg#${name}`); svg.append(use); return svg;
}

function toast(message) {
  $('toast').textContent = message;
  $('toast').hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { $('toast').hidden = true; }, 5500);
}

async function run(button, work, busyLabel) {
  if (button?.disabled) return;
  const original = button?.innerHTML;
  if (button) { button.disabled = true; if (busyLabel) button.textContent = busyLabel; }
  try { return await work(); } catch (error) { if (ChatStorageUI.isFullError(error.message)) ChatStorageUI.reportFull(); else toast(explainError(error.message)); }
  finally { if (button) { button.disabled = false; if (busyLabel) button.innerHTML = original; } }
}

function explainError(message = 'Please try again.') {
  if (needsNewApproval(message)) return 'This payment expired. Its sender needs to connect, update the wallet and approve a new payment.';
  if (message.startsWith('Insufficient available NOID')) return 'Not enough available NOID. Check pending payments or update your balance.';
  if(message.startsWith('Peers cannot supply') || message.includes('no mainnet candidate')) return 'Current wallet data is not available yet. The app will retry automatically.';
  if (message.includes('Connect once') || message.includes('offline view')) return 'Connect to the internet and tap Update balance before making an offline payment.';
  if (message.includes('State advanced') || message.includes('crossed a block boundary') || message.includes('Wallet data is ahead')) return 'The network changed during the check. Update your balance and try again.';
  if (message.startsWith('Invalid recipient')) return 'Check the recipient’s NOID address and try again.';
  return message.replaceAll('Check balance', 'Update balance');
}

function needsNewApproval(message = '') { return message.includes('BadEpochAnchor') || message.startsWith('Payment epoch changed.'); }

/** Your own avatar is a wallet icon in a soft accent circle (Android has no avatar picker), never "?". */
function walletAvatar(node = element('span', { class: 'avatar' })) {
  if (!node.classList.contains('default-avatar')) { node.classList.remove('warrior-avatar'); node.classList.add('avatar', 'default-avatar'); node.replaceChildren(icon('wallet')); }
  node.setAttribute('aria-label', 'Wallet avatar'); return node;
}
/** Your own avatar where there is one (your identity's avatar); the wallet icon only when there's none. */
/** Your own avatar (iOS PersonalAvatar): what you picked, else the wallet icon. See avatar-ui.js. */
function ownAvatar(node = element('span', { class: 'avatar' })) { return Avatars.own(node); }
// No "?" anywhere: with no name, initials are empty and callers show a person icon.
function initials(name) { return String(name || '').trim().split(/\s+/).filter(Boolean).slice(0, 2).map((v) => [...v][0]).join('').toUpperCase(); }
function personAvatar(name) { const text = initials(name); return text ? element('span', { class: 'avatar', text }) : element('span', { class: 'avatar person-avatar', 'aria-label': 'No name' }, icon('person')); }
function short(id) { return id ? `${id.slice(0, 8)} · ${id.slice(-8)}` : ''; }
function time(ms) { return new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); }
function contact(id) { return state?.contacts?.find((c) => c.id === id); }
function chatMessages(key) { return ChatSafety.visible((state?.messages || []).filter((m) => m.chat === key)); }
function preview(message) {
  if (!message) return 'Start the conversation';
  return ChatUI.preview(message);
}

function applyTheme() {
  const mode = state?.settings?.theme || 'system';
  // "Parano1d World" (optional, never the default): the retro lime-on-black look, built on the dark theme.
  // Cyber and Parano1d World share the pixel layout (iOS DisplayStyle.mono); private-only chat turns the accent orange.
  const mono = mode === 'world' || mode === 'cyber';
  document.documentElement.dataset.look = mono ? mode : '';
  document.documentElement.dataset.mono = String(mono);
  // iOS 173: the orange look follows Parano1d mode (the Peers wallet route), in every appearance.
  // Private-only chat is a separate switch and no longer changes colours.
  document.documentElement.dataset.orange = String(!!state?.paranoid_mode);
  if (applyTheme.paranoid !== undefined && applyTheme.paranoid !== !!state?.paranoid_mode) setTimeout(() => refreshUsd(true), 0);
  applyTheme.paranoid = !!state?.paranoid_mode;
  document.documentElement.dataset.theme = mode === 'system' ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : mono ? 'dark' : mode;
  const mark = document.querySelector('.brand-bar .wordmark');
  if (mark) {
    // Cyber: pixel "Parano1d / Wallet". World and the standard looks: the official PARANO1D WORLD wordmark.
    const wanted = mode === 'cyber' ? '<span>Parano1d</span><small>Wallet</small>' : '<span>PARANO<b>1</b>D</span><small>WORLD</small>';
    if (mark.innerHTML !== wanted) mark.innerHTML = wanted;
  }
  const paranoid = !!state?.paranoid_mode;
  document.documentElement.dataset.paranoid = String(paranoid);
  $('paranoid-switch').setAttribute('aria-checked', String(paranoid));
  // iOS 180: off = a plain shield; on = a filled shield with a lock, outlined in the accent colour.
  $('paranoid-switch').setAttribute('aria-label', paranoid ? 'Parano1d mode, On, mainnet peers only' : 'Parano1d mode, Off');
  $('paranoid-switch').title = paranoid ? 'Parano1d mode on · mainnet peers only' : 'Parano1d mode off';
  const protection = state?.protection || {};
  const vpnOn = !!(protection.enabled || protection.checking || protection.connected);
  const vpnStatus = !vpnOn ? 'off' : protection.checking ? 'connecting' : protection.connected ? 'connected' : 'unavailable';
  const vpnButton = $('vpn-switch');
  vpnButton.setAttribute('aria-checked', String(vpnOn));
  vpnButton.dataset.status = vpnStatus;
  vpnButton.disabled = !!vpnButton.switching || !!state?.chain?.running && !state?.chain?.automatic;
  const vpnDescription = {off:'Off. Uses your normal phone connection.',connecting:'On. Connecting the protected channel.',connected:'On. Protected channel connected. Only Parano1d uses it.',unavailable:'On. Protected channel is unavailable. Open Connections for details.'}[vpnStatus];
  vpnButton.title = `App VPN · ${vpnDescription}`;
  $('vpn-description').textContent = vpnDescription;
  document.querySelector('meta[name="theme-color"]').content = paranoid ? '#ff9b36' : '#00ff80';
}
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);
function render() {
  applyTheme();
  try { Avatars.observe(state?.messages); } catch { }
  // Wallet setup (iOS WalletSetupView): Create / Import / Watch. Stays open on "Your wallet is ready." until Open wallet.
  if (!state?.profile || WalletSetup.active()) { WalletSetup.show(); return; }
  // iOS watch-only wallet: Home shows the watched address; chat and sending are off.
  document.documentElement.dataset.watch = String(!!state.watch_only);
  if (state.watch_only && !['wallet', 'nfts', 'ecosystem', 'mining'].includes(tab)) tab = 'wallet';
  $('onboarding').hidden = true;
  try { AppCodeSetup.enforce(); } catch { }
  $('my-name').textContent = state.profile.name;
  ownAvatar($('my-avatar'));
  $('my-fingerprint').textContent = short(state.profile.id);
  if(state.community_paused && (tab === 'groups' || chat?.startsWith('g:'))) { ChatUI.beforeSwitch(); tab='direct'; chat=null; }
  document.querySelectorAll('[data-tab="groups"]').forEach(button => button.hidden=!!state.community_paused);
  $('welcome-group').hidden=!!state.community_paused;
  $('welcome-description').textContent=state.community_paused?'Add a contact or scan their contact QR code.':'Add a contact or meet someone in the community chat.';
  renderConnection();
  ChatStorageUI.renderNotice();
  const chatTab = tab === 'groups' || tab === 'direct';
  document.querySelectorAll('[data-tab]').forEach((button) => button.classList.toggle('active', button.dataset.tab === tab || button.dataset.tab === 'chat' && chatTab));
  document.querySelectorAll('[data-chat]').forEach((button) => { button.classList.toggle('active', button.dataset.chat === tab); button.setAttribute('aria-selected', String(button.dataset.chat === tab)); });
  document.querySelectorAll('[data-chat="groups"]').forEach(button => button.hidden = !!state.community_paused);
  const unreadDirect = Object.entries(state.unread || {}).filter(([key]) => key.startsWith('d:')).reduce((sum, [, count]) => sum + Number(count), 0);
  document.querySelectorAll('[data-tab="chat"],[data-chat="direct"]').forEach(button => {
    let badge = button.querySelector('.tab-unread');
    if (!unreadDirect) { badge?.remove(); return; }
    if (!badge) { badge = element('span', { class: 'tab-unread' }); button.append(badge); }
    badge.textContent = unreadDirect > 99 ? '99+' : String(unreadDirect);
  });
  $('list-heading').textContent = tab === 'direct' ? 'Private' : '#parano1d';
  $('new-button').hidden = tab !== 'direct';
  $('new-button').setAttribute('aria-label', 'Add a contact');
  $('list-footnote').textContent = tab === 'direct' ? 'Private messages are encrypted on your device.' : 'One community. Direct connections.';
  renderConversations();
  renderChat();
  renderWallet();
  renderNearbyPanel();
  renderBackgroundSetting();
  renderSecuritySetting();
  renderCaptureSetting();
  renderNodeSetting();
  const page = !chatTab;
  $('wallet-panel').hidden = tab !== 'wallet';
  $('nfts-panel').hidden = tab !== 'nfts';
  if (tab === 'nfts') renderAssets();
  $('ecosystem-panel').hidden = tab !== 'ecosystem';
  if (tab === 'ecosystem') renderEcosystem();
  // iOS 185: Mining (optional tab; also opens on its own for a pairing link).
  $('mining-panel').hidden = tab !== 'mining';
  if (typeof MiningUI !== 'undefined') { if (tab === 'mining') MiningUI.render(); else MiningUI.hidden(); }
  $('chat-panel').hidden = page || !chat;
  $('welcome-panel').hidden = page || !!chat;
  document.body.classList.toggle('chat-open', !!chat && !page);
  document.body.classList.toggle('direct-open', !!chat?.startsWith('d:') && !page);
  document.body.classList.toggle('wallet-open', page);
  reportVisibleChat();
  maybeSuggestVpn();
}

function maybeSuggestVpn() {
  const help=state?.network_help;
  if(!state?.profile || !help?.recommend_vpn || !help.id || shownVpnHelp.has(help.id) || state.protection?.enabled || state.protection?.checking || state.protection?.connected || (state.nearby?.peers||[]).length || document.hidden || $('modal').open || document.activeElement?.matches('input,textarea')) return;
  shownVpnHelp.add(help.id);
  native('vpn_help_seen',{id:help.id}).catch(() => {});
  const connect=element('button',{class:'primary-button',text:'Turn on App VPN',onclick:()=>run(connect,async()=>{
    state=await native('protection_enable');$('modal').close();render();toast(state.protection?.connected?'App VPN connected.':'App VPN on. Connecting…');
  })});
  const later=element('button',{class:'outline-button',text:'Not now',onclick:()=>$('modal').close()});
  const text=help.issue==='wallet'
    ? 'Your phone has internet, but your wallet is waiting too long for a network response. Try App VPN. It may help on restricted connections and only applies to Parano1d. Your wallet check will resume through it.'
    : 'Your phone has internet, but Parano1d cannot connect. App VPN may help on restricted connections. Only Parano1d uses it.';
  modal('Having trouble connecting?',[element('p',{text}),connect,later],'CONNECTION HELP');
}

function renderConnection() {
  const online = !!state?.connection?.online;
  const nearby = (state?.nearby?.peers || []).length > 0;
  const status = state?.protection?.checking || !online && state?.connection?.checking ? 'connecting' : online ? 'online' : nearby ? 'nearby' : 'offline';
  const label = { online: 'Online', nearby: 'Bluetooth only', offline: 'Offline', connecting: 'Connecting…' }[status];
  const button = $('connection');
  button.dataset.status = status;
  button.classList.toggle('online', status === 'online');
  button.classList.toggle('nearby', status === 'nearby');
  button.setAttribute('aria-label', `Connection options: ${label.toLowerCase()}`);
  $('connection-status').textContent = label;
}

function renderConversations() {
  const list = $('conversation-list');
  list.replaceChildren();
  const query = $('search').value.trim().toLowerCase();
  const conversations = tab === 'direct'
    ? (state.contacts || []).map((c) => ({ key: `d:${c.id}`, title: c.name, avatar: initials(c.name), description: short(c.id), contact: c }))
    : [{ key: 'g:parano1d', title: '#parano1d', avatar: '#', description: 'Community chat' }];
  if (!list.dataset.reorder) { list.dataset.reorder = '1'; ChatSafety.draggable(list); }
  if (tab === 'direct' && !query) { const requests = ChatRequests.row(); if (requests) list.append(requests); }
  for (const item of ChatSafety.ordered(conversations).filter((c) => `${c.title} ${c.description}`.toLowerCase().includes(query))) {
    if (item.contact && ChatSafety.isBlocked(item.contact.id)) item.description = 'Blocked on this device';
    const messages = chatMessages(item.key);
    const last = messages.at(-1);
    const row = element('button', { class: `conversation-row${chat === item.key ? ' selected' : ''}`, onclick: () => openChat(item.key) },
      item.contact ? WarriorAvatar.node(item.contact.id, item.contact.name) : element('span', { class: 'avatar' }, icon('community')),
      element('span', { class: 'row-text' }, element('strong', { text: item.title }), element('p', { text: last && !ChatSafety.isBlocked(item.contact?.id) ? preview(last.message) : item.description })),
      last ? element('time', { text: time(last.created_ms) }) : null);
    row.dataset.chatKey = item.key;
    if (item.contact) list.append(element('div', { class: 'conversation-item', 'data-chat-key': item.key }, row,
      element('button', { class: 'icon-button conversation-options', 'aria-label': `Options for ${item.title}`, onclick: () => showChatOptions(item.key) }, icon('more'))));
    else { row.dataset.chatKey = item.key; list.append(row); }
    row.addEventListener('contextmenu', event => { event.preventDefault(); showChatOptions(item.key); });
    const count = Number(state.unread?.[item.key] || 0);
    if (count) row.append(element('span', { class: 'unread', 'aria-label': `${count} unread messages`, text: count > 99 ? '99+' : String(count) }));
  }
  if (!list.querySelector('.conversation-item, .conversation-row:not(.requests-row)')) list.append(query ? empty('No contacts found', 'Try another name.') : empty('No private chats yet', state.community_paused?'Tap Add contact or scan a contact QR code.':'Tap Add contact, or tap someone’s name in the community chat.'));
}

function empty(title, description) {
  return element('div', { class: 'empty-state' }, element('span', { class: 'empty-symbol' }, icon('chat')), element('strong', { text: title }), element('p', { text: description }));
}

async function openChat(key) {
  if(state?.community_paused && key.startsWith('g:')) return;
  if (key.startsWith('g:')) ChatSafety.refreshModeration();
  ChatUI.beforeSwitch();
  chat = key;
  tab = key.startsWith('d:') ? 'direct' : 'groups';
  render();
  await markCurrentRead(true);
  await poll();
  $('message-scroll').scrollTop = $('message-scroll').scrollHeight;
}

function renderChat() {
  ChatUI.render();
}

async function markCurrentRead(force = false) {
  const key = chat;
  if (!key || tab === 'wallet' || $('modal').open || !state?.profile || document.hidden || readingChats.has(key) || !force && !Number(state.unread?.[key])) return;
  readingChats.add(key);
  try { state = await native('mark_read', { chat: key, message_id: chatMessages(key).at(-1)?.id || '' }); render(); }
  catch { }
  finally { readingChats.delete(key); }
}

async function reportVisibleChat() {
  if (!state?.profile) return;
  const key = document.hidden || $('modal').open || tab === 'wallet' ? '' : chat || '';
  const paymentFlow=$('modal').open && ['NOID / SEND','AUTHORIZE'].includes($('modal').dataset.kind);
  const walletVisible=!document.hidden && tab==='wallet' && !$('modal').open;
  const marker=key+String(paymentFlow)+String(walletVisible);
  if (reportedChat === marker) return;
  reportedChat = marker;
  try { await native('view_chat', { chat: key, payment_flow:paymentFlow, wallet_visible:walletVisible }); }
  catch { if (reportedChat === marker) reportedChat = undefined; }
}

function paymentCard(item, mine) {
  const message = item.message;
  if (message.type === 'transaction') {
    return transactionCard(item, mine);
  }
  if (message.type === 'invoice') {
    const checked = (state.receipts || []).find(r => r.verified && r.invoice_id === message.invoice_id);
    const received = chatMessages(item.chat).some(m => m.message.type === 'receipt' && m.message.invoice_id === message.invoice_id);
    const card = element('div', { class: 'payment-card', 'data-invoice-id': message.invoice_id }, element('header', { text: checked ? 'PAYMENT CHECKED' : 'PAYMENT REQUEST' }));
    const content = element('div', {}, element('div', { class: 'amount' }, message.amount, ' ', element('span', { text: 'NOID' })), element('p', { text: message.memo || 'A direct payment in NOID.' }), element('code', { text: short(message.address) }));
    if (checked) content.append(element('p', { text: 'Receipt checked on this phone.' }), element('button', { class: 'primary-button', text: 'View receipt ↗', onclick: () => showReceipt(checked) }));
    else if (!mine) content.append(element('button', { class: 'primary-button', text: 'Review payment ↗', onclick: (event) => reviewPayment(message.address, message.amount, message.invoice_id, event.currentTarget) }));
    else if (received) content.append(element('p', { text: 'Receipt received. Check it below.' }));
    else content.append(element('p', { text: 'Waiting for a payment receipt.' }));
    card.append(content); return card;
  }
  const verified = (state.receipts || []).find((r) => r.message_id === item.id);
  const card = element('div', { class: 'payment-card' }, element('header', { text: verified ? 'VERIFIED PAYMENT' : 'PAYMENT RECEIPT' }));
  const content = element('div', {}, element('p', { text: verified ? `Included at block ${verified.height.toLocaleString()}.` : 'Check this receipt against your verified chain.' }));
  content.append(element('button', { class: 'primary-button', text: verified ? 'View receipt ↗' : 'Check payment ↗', onclick: () => verified ? showReceipt(verified) : verifyMessageReceipt(item) }));
  card.append(content); return card;
}

function transactionCard(item, mine) {
  const txid = item.payment?.txid;
  const reports = (state.observations || []).filter((o) => o.message.txid === txid && o.chat === item.chat);
  const latest = reports.slice().sort((a, b) => b.created_ms - a.created_ms)[0];
  const evidence = reports.find((o) => o.message.stage === 'included' && o.message.receipt_hex);
  const verified = (state.receipts || []).find((r) => r.txid === txid && r.verified);
  const ownPacket = (state.prepared_payments || []).find((p) => p.txid === txid);
  const outdated = !verified && !evidence && needsNewApproval(item.broadcast_error || ownPacket?.delivery_error || '');
  const card = element('div', { class: `payment-card transaction-card${verified ? ' verified' : ''}`, 'data-txid': txid || '' });
  const title = verified ? (verified.finalized ? 'FINALIZED PAYMENT' : 'PAYMENT CHECKED') : 'PAYMENT';
  card.append(element('header', { text: title }));
  const content = element('div');
  const statuses = { submitted: 'Sent · waiting for confirmation', in_mempool: 'Sent · waiting for confirmation', included: 'Payment found · ready to check', rejected: 'A helper reported rejection' };
  content.append(element('strong', { class: 'payment-stage', text: verified ? `Block ${Number(verified.height).toLocaleString()}` : outdated ? 'Payment expired' : statuses[latest?.message.stage] || 'Waiting to reach the network' }));
  if (txid) content.append(element('code', { text: short(txid) }));
  if (verified) content.append(element('p', { text: 'This phone checked that the payment is included in the blockchain.' }));
  else if (outdated) content.append(element('p', { class: 'danger', text: mine ? 'Connect, update your wallet and approve a new payment.' : 'Ask the sender to connect, update their wallet and send a new payment.' }));
  else if (latest) content.append(element('p', { class: 'report-meta', text: `${latest.sender.name} · ${time(latest.created_ms)} · network observation` }));
  else content.append(element('p', { text: 'This payment is already approved by its sender. Help it reach the network.' }));
  const helpers = new Set(reports.map((o) => o.sender.id)).size;
  if (helpers) content.append(element('small', { class: 'helper-count', text: `${helpers} ${helpers === 1 ? 'helper' : 'helpers'} · status updates appear here` }));
  if (evidence && !verified) {
    const check = element('button', { class: 'primary-button', text: 'Check payment ↗', onclick: () => run(check, async () => {
      const result = await native('receipt_verify', { receipt_hex: evidence.message.receipt_hex, expected_txid: txid, message_id: item.id });
      state = result.state; render(); showReceipt(result.receipt);
    }) });
    content.append(check);
  }
  if (!verified) {
    if (outdated && mine && ownPacket?.to && !ownPacket.replaced_by) content.append(element('button', { class: 'primary-button', text: 'Review fresh payment ↗', onclick: event => reviewPayment(ownPacket.to, ownPacket.amount, ownPacket.invoice_id || '', event.currentTarget, false, txid) }));
    const broadcast = element('button', { class: evidence || outdated ? 'outline-button' : 'primary-button', text: outdated ? 'Retry network submission ↗' : 'Help send this payment ↗', onclick: () => inspectBroadcast(item) });
    content.append(broadcast);
  } else content.append(element('button', { class: 'outline-button', text: 'View verified receipt ↗', onclick: () => showReceipt(verified) }));
  if (!state.community_paused && mine && !outdated && item.chat.startsWith('d:')) content.append(element('button', { class: 'text-button', text: 'Ask the community to help', onclick: () => sharePrepared(txid) }));
  card.append(content); return card;
}

async function inspectBroadcast(item) {
  await run(null, async () => {
    const review = await native('broadcast_preview', { message_id: item.id });
    const outputs = review.outputs.map((o) => element('div', { class: 'detail', text: `${formatMicronoid(o.amount_micronoid)} NOID → ${o.address}` }));
    const forward = element('button', { class: 'primary-button', text: 'Send to the network ↗', onclick: () => run(forward, async () => {
      await native('broadcast_send', { message_id: item.id, txid: review.txid }); $('modal').close(); await poll(); toast('Forwarded. This card will collect status updates.');
    }) });
    modal('Help it reach the network.', [element('p', { text: 'Send the approved payment to the network. Its sender pays the fee. This uses no NOID from your wallet.' }), ...outputs, element('div', { class: 'review-row' }, element('span', { text: 'Network fee' }), element('span', { text: `${review.fee} NOID` })), element('div', { class: 'detail', text: review.txid }), forward], 'BROADCAST');
  });
}

function formatMicronoid(value) { const amount = BigInt(value); return `${amount / 1000000n}.${String(amount % 1000000n).padStart(6, '0')}`; }
function paymentAmount(value) {
  const amount = String(value).trim().replace(',', '.');
  if (!/^\d+(?:\.\d{1,6})?$/.test(amount) || !/[1-9]/.test(amount)) throw new Error('Enter an amount greater than zero, with up to six decimal places.');
  return amount;
}
async function sharePrepared(txid) { if(state?.community_paused) return; await run(null, async () => { state = await native('share_prepared', { txid }); $('modal').close(); await openChat('g:parano1d'); toast('Help request shared. Updates will appear on its card.'); }); }

// iOS 188 item 52: while there's no verified or saved verified balance, show the public explorer's balance,
// labelled "ESTIMATE · VERIFYING". Display only: kept in memory for at most 10 minutes, refetched at most once a
// minute, off in Parano1d mode, with App VPN and offline. Sending, tokens and NFTs still wait for the verified State.
let explorerEstimate = null, explorerEstimateAt = 0, explorerEstimateLoading = false;
function estimateAllowed() { return !!state?.profile?.address && !state.chain?.balance && !state.paranoid_mode && !state.protection?.enabled && state.connection?.online !== false; }
function currentEstimate() {
  if (!estimateAllowed() || !explorerEstimate || explorerEstimate.address !== state.profile.address || Date.now() - explorerEstimate.at > 600000) return null;
  return explorerEstimate;
}
function loadEstimate() {
  if (!estimateAllowed() || explorerEstimateLoading || Date.now() - explorerEstimateAt < 60000) return;
  explorerEstimateLoading = true; explorerEstimateAt = Date.now();
  const address = state.profile.address;
  native('explorer_balance').then((result) => {
    if (result?.address === address && state?.profile?.address === address) { explorerEstimate = { address, balance: result.balance, at: Date.now() }; renderWallet(); }
  }).catch(() => {}).finally(() => { explorerEstimateLoading = false; });
}

// ---- Transaction history ----
// Newest first. The full page starts with 5; each "Show more" adds 20; it resets when the wallet changes.
const historyView = { open: false, shown: 5, wallet: '' };
function sortedReceipts() { return [...(state?.receipts || [])].map((r, i) => [r, i]).sort((a, b) => (Number(b[0].height) || 0) - (Number(a[0].height) || 0) || b[1] - a[1]).map(([r]) => r); }
function receiptRow(r) {
  // Like iOS VerifiedReceiptCache: a saved verified receipt shows at once; one above the phone's current
  // verified height (e.g. after the node data was reset) is not shown as verified until the chain catches up.
  const tip = Number(state.chain?.balance_height || state.chain?.height || 0);
  const ahead = tip && Number(r.height) > tip;
  return element('button', { class: 'receipt-row', onclick: () => showReceipt(r) }, element('span', {}, element('strong', { text: r.amount ? `${r.amount} NOID` : 'Verified payment' }), element('small', { text: `Block ${r.height} · ${short(r.txid)}` })), element('span', { text: ahead ? 'CHECK AGAIN ↗' : r.finalized ? 'FINALIZED ↗' : 'VERIFIED ↗' }));
}
function showHistory() {
  historyView.open = true; historyView.shown = 5; historyView.wallet = state?.profile?.address || '';
  modal('History', [element('p', { class: 'card-note', text: 'Saved payments, each verified by this phone.' }), element('div', { id: 'history-list', class: 'receipts' })], 'WALLET');
  $('modal').addEventListener('close', () => { historyView.open = false; }, { once: true });
  renderHistory();
}
function renderHistory() {
  const list = $('history-list'); if (!list) { historyView.open = false; return; }
  const wallet = state?.profile?.address || '';
  if (wallet !== historyView.wallet) { historyView.wallet = wallet; historyView.shown = 5; }
  const all = sortedReceipts();
  list.replaceChildren(...all.slice(0, historyView.shown).map(receiptRow));
  const older = all.length - historyView.shown;
  if (older > 0) list.append(element('button', { class: 'show-more-button', id: 'history-more', onclick: () => { historyView.shown += 20; renderHistory(); } }, `Show more · ${older} older`));
  if (!all.length) list.append(empty('Keep the proof.', 'Verified payment receipts are saved here.'));
}
function renderWallet() {
  if (!state) return;
  $('wallet-name').textContent = state.watch_only ? 'Watch-only' : state.wallet_name || 'Main wallet';
  if (!state.chain?.balance) loadEstimate(); else explorerEstimate = null;
  const estimate = currentEstimate();
  $('wallet-address').replaceChildren(highlightedAddress(state.profile?.address || ''));
  const balanceText = state.chain?.balance || estimate?.balance || '—';
  $('balance').textContent = hideBalances ? '••••••' : balanceText;
  $('asset-noid').textContent = hideBalances ? '••••' : balanceText;
  $('hide-balance').replaceChildren(element('span', {}));
  $('hide-balance').innerHTML = `<svg class="icon" aria-hidden="true"><use href="icons.svg#${hideBalances ? 'eye-off' : 'eye'}"></use></svg>`;
  $('hide-balance').setAttribute('aria-label', hideBalances ? 'Show balances' : 'Hide balances');
  const verifiedHeight = state.chain?.balance_height || state.chain?.height;
  if (assetState.data) scheduleAssetRender(); else if (state?.profile && !assetState.loading && !assetState.error) loadAssets();
  // iOS 174: "≈ $… USD" under the NOID amount in every look; "USD value unavailable" without a fresh quote;
  // "USD estimate off" when live prices are off. Hide balances hides it.
  const livePricesOn = state?.prefs?.live_prices !== false && state?.connection?.online !== false;
  const usd = state.chain?.balance ? usdValue(state.chain.balance) : null;
  $('balance-usd').hidden = hideBalances;
  $('balance-usd').textContent = !livePricesOn ? 'USD estimate off' : !state.chain?.balance ? 'USD after the balance check' : usd ? `≈ ${usd} USD` : 'USD value unavailable';
  // iOS 174: the NOID price chip sits directly above the balance box, in every look, while live prices are on
  // and the app is online (orange in Parano1d mode through the accent colour).
  const fresh = priceFresh() && state?.connection?.online !== false;
  $('noid-price').hidden = !fresh; $('noid-price').textContent = fresh ? `NOID $${String(usdQuote.usd).slice(0, 7)}` : '';
  if (!usdQuote && state?.prefs?.live_prices !== false && !usdTimer) refreshUsd();
  $('verified-block').classList.toggle('estimate', !!estimate);
  $('balance-eyebrow').textContent = state.watch_only ? 'WATCH-ONLY' : estimate ? 'ESTIMATED NOID' : 'VERIFIED NOID';
  $('verified-block').textContent = estimate ? 'ESTIMATE · VERIFYING' : verifiedHeight ? `Verified · block ${Number(verifiedHeight).toLocaleString()}` + (state.connection?.online ? '' : ' · saved') : 'Waiting for verification';
  renderReserved();
  const renewing = !!state.chain?.automatic && !!state.node?.renewing_authorization;
  const verifying = !!state.chain?.automatic && state.node?.phase === 'verifying' && !renewing;
  const peerScan = !!state.paranoid_mode && state.node?.phase === 'finding_coins' && !!state.chain?.running;
  const reconnecting = !!state.protection?.checking;
  const busy = reconnecting || !!state.chain?.running && !state.chain?.automatic || walletBusy || verifying || peerScan;
  const nodeText = reconnecting ? 'Connecting App VPN…' : peerScan ? 'Finding your coins…' : busy ? verifying ? state.paranoid_mode ? 'Checking your wallet…' : 'Checking a balance change…' : 'Checking for payments…' : state.chain?.retrying ? 'Wallet will retry' : state.node?.phase === 'starting' ? 'Connecting…' : state.chain?.height ? state.connection?.online ? 'Wallet ready' : 'Saved balance' : 'Preparing your wallet…';
  // Only the chip's icon turns while the phone is synchronizing; it stops and turns
  // into a green check once Synced. Offline and "needs attention" never spin. No page-level loader for the sync.
  const synced = !busy && !reconnecting && !state.chain?.retrying && !!state.chain?.height && !!state.connection?.online && state.node?.phase !== 'starting';
  const chipState = synced ? 'synced' : state.chain?.retrying || state.node?.phase === 'error' ? 'attention' : state.chain?.height && !state.connection?.online && !busy ? 'offline' : 'syncing';
  $('node-status-text').textContent = state.offline ? 'Offline mode' : synced ? 'Synced' : nodeText;
  $('node-status').dataset.state = state.offline ? 'offline' : chipState;
  $('node-status').setAttribute('aria-label', `On-device node: ${synced ? 'Synced' : nodeText}`);
  $('sync-button').disabled = busy || !!state.offline;
  $('sync-button').textContent = reconnecting ? 'Connecting…' : peerScan ? 'Finding coins…' : busy ? verifying ? 'Updating…' : 'Checking…' : 'Update balance';
  $('balance-description').textContent = reconnecting ? 'Your wallet check will resume when App VPN connects.' : peerScan ? state.chain?.height ? 'Refreshing your saved wallet. You can keep chatting.' : 'First direct P2P check. You can keep chatting.' : busy ? verifying ? 'Verifying a change to your balance. You can keep chatting.' : 'Checking for new payments. You can keep chatting.' : !state.chain?.height ? 'Open your wallet online before making your first payment.' : !state.connection?.online ? 'Saved balance. Reconnect to check for changes.' : renewing ? 'Keeping payments ready in the background.' : state.chain?.retrying ? 'Showing your saved balance. Wallet updates will retry.' : 'Balance updates automatically.';
  if (state.offline) $('balance-description').textContent = 'Offline mode is on. This is the last balance this phone verified. Turn Offline mode off in Settings › Network to check again.';
  else if (estimate) $('balance-description').textContent = 'From the public explorer while this device verifies the chain. Sending waits for the verified balance.';
  const setup = $('wallet-setup'); setup.replaceChildren();
  if (state.watch_only) setup.append(element('section', { class: 'wallet-reminder watch-panel' },
    element('strong', { text: 'Watch-only address' }),
    element('p', { text: 'This address is not a local wallet. Add a master key to create a spend-capable wallet on this device.' }),
    element('button', { class: 'primary-button', text: 'Import a wallet', onclick: () => WalletSetup.open('import') }),
    element('button', { class: 'text-button', text: 'Create a new wallet', onclick: () => WalletSetup.open('create') }),
    element('button', { class: 'text-button', text: 'Stop watching', onclick: (event) => run(event.currentTarget, async () => { state = await native('watch_stop'); render(); }) })));
  if (!state.backup_saved && !state.watch_only) setup.append(element('section', { class: 'wallet-reminder' }, element('strong', { text: 'Keep access if you lose this phone' }), element('p', { text: 'Save an encrypted backup of your wallet and chats.' }), element('button', { class: 'outline-button', text: 'Save a backup', onclick: showBackup })));
  if (state.security && !state.security.available) setup.append(element('section', { class: 'wallet-reminder' }, element('strong', { text: 'Protect access to this wallet' }), element('p', { text: 'Set a phone PIN or fingerprint to lock Parano1d when you leave it.' }), element('button', { class: 'outline-button', text: 'Set up phone screen lock', onclick: () => run(null, () => native('phone_security')) })));
  // Home shows the 5 most recent receipts, then "Show more · N older" opens the full history.
  const receipts = $('receipts'); receipts.replaceChildren();
  const all = sortedReceipts();
  for (const r of all.slice(0, 5)) receipts.append(receiptRow(r));
  if (all.length > 5) receipts.append(element('button', { class: 'show-more-button', id: 'receipts-more', onclick: showHistory }, `Show more · ${all.length - 5} older`));
  if (!receipts.children.length) receipts.append(empty('Keep the proof.', 'Verified payment receipts are saved here.'));
  if (historyView.open && $('history-list')) renderHistory();
  const pendingPayments = $('pending-payments'); pendingPayments.replaceChildren();
  for (const packet of (state.prepared_payments || []).filter((p) => !p.replaced_by).slice(-20).reverse()) {
    if ((state.receipts || []).some((r) => r.txid === packet.txid && r.verified)) continue;
    const outdated = needsNewApproval(packet.delivery_error);
    const row = element('div', { class: 'pending-payment' }, element('strong', { text: `${packet.amount || '—'} NOID` }), element('p', { text: packet.submitted ? 'Sent. Waiting for confirmation.' : outdated ? 'Payment expired.' : 'Saved on this phone. Waiting to send.' }), element('code', { text: short(packet.txid) }));
    if (packet.delivery_error) row.append(element('p', { class: 'danger', text: explainError(packet.delivery_error) }), element('button', { class: 'outline-button', text: outdated ? 'Review fresh payment ↗' : 'Try sending again', onclick: (event) => outdated ? run(null, async () => { if (packet.chat?.startsWith('d:')) await openChat(packet.chat); await reviewPayment(packet.to, packet.amount, packet.invoice_id || '', event.currentTarget, false, packet.txid); }) : run(event.currentTarget, async () => { state = await native('retry_payment', { txid: packet.txid }); render(); toast('Trying to send the saved payment.'); }) }));
    if (!state.community_paused && !outdated) row.append(element('button', { class: 'outline-button', text: 'Ask the community to help ↗', onclick: () => sharePrepared(packet.txid) }));
    const evidence = (state.observations || []).find((o) => o.message.txid === packet.txid && o.message.stage === 'included' && o.message.receipt_hex);
    if (evidence) row.append(element('button', { class: 'primary-button', text: 'Check payment ↗', onclick: () => run(null, async () => { const result = await native('receipt_verify', { receipt_hex: evidence.message.receipt_hex, expected_txid: packet.txid }); state = result.state; render(); showReceipt(result.receipt); }) }));
    pendingPayments.append(row);
  }
}

function modal(title, children, eyebrow = 'PARAMO1D') {
  $('modal').dataset.kind=eyebrow;
  $('modal-title').textContent = title;
  $('modal-eyebrow').textContent = eyebrow;
  $('modal-content').replaceChildren(...children);
  if (!$('modal').open) $('modal').showModal();
  reportVisibleChat();
}

function field(label, options = {}) {
  const input = element(options.multiline ? 'textarea' : 'input', { type: options.type || 'text', placeholder: options.placeholder || '', ...(options.value != null ? { value: options.value } : {}) });
  if (options.multiline) input.value = options.value || '';
  if (options.autocomplete) input.autocomplete = options.autocomplete;
  return { label: element('label', {}, label, input), input };
}

function scannedField(kind, value, preview) {
  const button = element('button', { class: 'scan-trigger', type: 'button', 'aria-label': kind === 'contact' ? 'Scan a contact QR code' : 'Scan a NOID address', onclick: async () => {
    if (scanContext) return;
    const scan = { id: String(++scanNumber), kind, input: value.input, button, preview };
    scanContext = scan; button.disabled = true;
    try { await native('scan_qr', { kind, scan_id: scan.id }); }
    catch (error) { if (scanContext === scan) scanContext = null; button.disabled = false; toast(explainError(error.message)); }
  } }, icon('scan'), 'Scan');
  return element('div', { class: 'scan-field' }, value.label, button);
}

window.paramo1dScanResult = result => {
  const scan = scanContext;
  if (!scan || result.scan_id !== scan.id) return;
  scanContext = null; scan.button.disabled = false;
  if (result.cancelled || !scan.input.isConnected || !$('modal').open) return;
  if (result.error) { toast(explainError(result.error)); return; }
  if (result.kind !== scan.kind || typeof result.text !== 'string') { toast('Please scan the QR code again.'); return; }
  scan.input.value = result.text; scan.input.dispatchEvent(new Event('input', { bubbles: true }));
  if (scan.preview && result.contact) {
    scan.preview.replaceChildren(WarriorAvatar.node(result.contact.id, result.contact.name), element('span', {}, element('strong', { text: result.contact.name }), element('small', { text: short(result.contact.id) })));
    scan.preview.hidden = false;
  }
  toast(scan.kind === 'contact' ? 'Contact scanned. Tap Add contact to save it.' : 'Address scanned. Check the recipient before sending.');
};

function showContact(peer) {
  if (peer.id === state.profile.id) { showInvite(); return; }
  const button = element('button', { class: 'primary-button', text: 'Message ↗', onclick: () => run(button, async () => {
    state = await native('add_contact', { contact: peer }); $('modal').close(); await openChat(`d:${peer.id}`);
  }) });
  modal(peer.name, [element('div', { class: 'contact-head' }, WarriorAvatar.node(peer.id, peer.name), element('strong', { text: peer.name }), Favorites.button(peer, () => showContact(peer))),
    button,
    peer.address ? element('button', { class: 'outline-button', id: 'contact-save-address', text: 'Name address and choose icon', onclick: () => AddressBook.save(peer.address, peer.id, () => showContact(peer)) }) : '',
    element('details', { class: 'advanced-settings' }, element('summary', { text: 'Contact details' }), element('p', { text: 'Compare this code with the person you want to message.' }), element('div', { class: 'detail', text: peer.id }), element('p', { text: 'NOID address' }), element('div', { class: 'detail', text: peer.address }))], 'CONTACT');
}

function showInvite() {
  if (!state?.profile) return;
  const children = [element('p', { text: 'Share this contact to start an encrypted conversation.' })];
  if (window.qrcode && state.invite) {
    const box = element('div', { class: 'qr' });
    const code = qrcode(0, 'M'); code.addData(state.invite); code.make();
    // QR generator creates SVG from encoded modules, without user markup.
    box.innerHTML = code.createSvgTag({ cellSize: 3, margin: 3, scalable: true });
    children.push(box);
  }
  children.push(element('div', { class: 'detail', text: short(state.profile.id) }), element('button', { class: 'primary-button', text: 'Share contact ↗', onclick: () => run(null, () => native('share', { text: state.invite })) }), element('button', { class: 'text-button', text: 'Copy contact link', onclick: () => copy(state.invite) }));
  modal(state.profile.name, children, 'YOUR CONTACT');
}

function showAddContact() {
  const uri = field('Contact link', { multiline: true, placeholder: 'paramo1d://contact/…' });
  const preview = element('div', { class: 'scanned-contact contact-card-person', hidden: '' });
  uri.input.addEventListener('input', () => { preview.hidden = true; });
  const add = element('button', { class: 'primary-button', text: 'Add contact ↗', onclick: () => run(add, async () => {
    state = await native('import_contact', { uri: uri.input.value.trim() }); $('modal').close(); render();
  }) });
  modal('Add a contact', [element('p', { text: 'Scan their contact QR code or paste their Parano1d contact link.' }), scannedField('contact', uri, preview), preview, add], 'MESSAGES');
}

/** A settings card's title with its row icon (every row has an icon). */
function cardTitle(name, text) { return element('span', { class: 'card-title' }, typeof settingsIcon === 'function' ? settingsIcon(name) : '', element('strong', { text })); }
function renderNodeSetting() {
  const panel=$('node-setting'); if(!panel)return;
  panel.replaceChildren(element('section',{class:'connection-card'},cardTitle('refresh','Automatic wallet updates'),element('p',{text:'Checks for wallet changes while the app is open. Your phone verifies new balances. Stops when you leave or lock the phone. Use Update balance to check now.'})));
}

function renderSecuritySetting() {
  const panel = $('security-setting'); if (!panel) return;
  const security = state.security || {}, code = state.app_code?.set;
  // With an app PIN or password (iOS), this switch only adds the phone's own unlock (fingerprint or screen lock).
  const title = code ? 'Unlock with fingerprint or phone screen lock' : 'App lock';
  const toggle = connectionToggle(title, !!security.enabled, async () => { state = await native('app_lock', { enabled: !security.enabled }); render(); });
  toggle.disabled = !security.available;
  const away = typeof autoLockLabel === 'function' ? autoLockLabel(state.auto_lock_seconds ?? 60).toLowerCase() : '';
  const text = !security.available ? (code ? 'Your app code unlocks the wallet. Set a phone PIN or fingerprint to also unlock with it.' : 'Set a phone PIN or fingerprint to protect access to your messages and wallet.')
    : code ? 'Your app code always unlocks the wallet. Turn this on to also unlock with your fingerprint or the phone’s screen lock. The phone still protects the wallet key.'
    : `Unlock on entry with your fingerprint or phone PIN. Auto-lock: ${away}.`;
  const card = element('section', { class: 'connection-card' }, element('div', { class: 'connection-card-heading' }, cardTitle(code ? 'key' : 'lock', title), toggle), element('p', { text }));
  if (!security.available) card.append(element('button', { class: 'outline-button', text: 'Set up phone screen lock', onclick: () => run(null, () => native('phone_security')) }));
  panel.replaceChildren(card);
}

// Wear OS companion: wallet PIN, watch sends, limits and confirm-on-watch.
async function renderWatchSetting() {
  const panel = $('watch-setting'); if (!panel) return;
  let w;
  try { w = await native('watch_settings'); } catch (error) { panel.replaceChildren(); return; }
  const save = async (change) => { w = await native('watch_settings_set', change); renderWatchSetting(); };
  const sends = connectionToggle('Send from watch', !!w.sends_enabled, () => save({ sends_enabled: !w.sends_enabled }));
  const confirm = connectionToggle('Confirm phone sends on watch', !!w.confirm_phone_sends, () => save({ confirm_phone_sends: !w.confirm_phone_sends }));
  const hide = connectionToggle('Hide balances on watch', !!w.hide_balances, () => save({ hide_balances: !w.hide_balances }));
  const pin = element('button', { class: 'outline-button', text: w.pin?.set ? 'Change wallet PIN' : 'Set wallet PIN', onclick: showWalletPin });
  const row = (title, toggle, text, name) => element('div', {}, element('div', { class: 'connection-card-heading' }, cardTitle(name, title), toggle), element('p', { text }));
  panel.replaceChildren(element('section', { class: 'connection-card' },
    cardTitle('watch', 'Wear OS watch'),
    element('p', { text: 'The watch holds no keys. It asks this phone to sign, and you confirm with your wallet PIN. Your phone must be unlocked and nearby, with Parano1d open in the background.' }),
    pin,
    row('Send from watch', sends, w.pin?.set ? 'Send any amount to your saved contacts. Each send shows the full address and amount, then asks for your wallet PIN.' : 'Set a wallet PIN first.', 'send'),
    row('Confirm phone sends on watch', confirm, 'After you approve a send here, approve or decline it on the watch within 60 seconds before it is signed.', 'check'),
    row('Hide balances on watch', hide, 'Shows “Hidden” on the watch and its complication.', 'eye-off'),
  ));
  sends.disabled = !w.pin?.set && !w.sends_enabled;
}
function showWalletPin() {
  const current = field('Current PIN (leave empty the first time)', { type: 'password' }); current.input.inputMode = 'numeric';
  const next = field('New PIN · 4 to 8 digits', { type: 'password' }); next.input.inputMode = 'numeric';
  const again = field('Repeat new PIN', { type: 'password' }); again.input.inputMode = 'numeric';
  const save = element('button', { class: 'primary-button', text: 'Save wallet PIN', onclick: () => run(save, async () => {
    if (next.input.value !== again.input.value) throw new Error('The new PINs do not match.');
    await native('wallet_pin_set', { current: current.input.value, pin: next.input.value }); $('modal').close(); toast('Wallet PIN saved.');
  }) });
  modal('Wallet PIN', [element('p', { text: 'Confirms sends, payment requests and messages on your Wear OS watch. Different from your phone screen lock. Five wrong tries lock it for a minute; ten lock it for 15 minutes.' }), current.label, next.label, again.label, save], 'WATCH');
}
function renderPublicChatSetting() {
  const panel = $('public-chat-setting'); if (!panel) return;
  const on = !state.community_paused, privateOnly = !on;
  // Same name and meaning as iOS (Chat → Private-only chat): on = the public room is off.
  const toggle = connectionToggle('Private-only chat', privateOnly, async () => { state = await native('public_chat', { enabled: privateOnly }); render(); });
  panel.replaceChildren(element('section', { class: 'connection-card' }, element('div', { class: 'connection-card-heading' }, cardTitle('community', 'Private-only chat'), toggle), element('p', { text: privateOnly ? 'Private-only chat is on, so the public room is off. Private chats still work.' : 'Off: the public #parano1d room is on. It has its own setting and stays on in Paranoid mode.' })));
}
// ---- iOS 165 parity: address-poisoning protection ----
// A recipient that starts and ends like an address you already use, but differs, is stopped before review.
const confirmedLookalikes = new Set(); // this app session only
function knownAddresses() {
  const known = new Set();
  if (state?.profile?.address) known.add(state.profile.address);
  for (const c of state?.contacts || []) if (c.address) known.add(c.address);
  for (const p of state?.prepared_payments || []) if (p.to) known.add(p.to);
  return [...known];
}
function lookalikeAddress(address) {
  const a = String(address).trim().toLowerCase();
  if (a.length < 16) return null;
  return knownAddresses().find(k => { const b = k.toLowerCase(); return b !== a && b.slice(0, 7) === a.slice(0, 7) && b.slice(-5) === a.slice(-5); })
      || knownAddresses().find(k => { const b = k.toLowerCase(); return b !== a && b.slice(0, 5) === a.slice(0, 5) && b.slice(-4) === a.slice(-4); }) || null;
}
function highlightedAddress(address) {
  const text = String(address || '');
  if (text.length <= 12) return element('span', { class: 'addr', text });
  return element('span', { class: 'addr' }, element('mark', { class: 'addr-mark', text: text.slice(0, 7) }), text.slice(7, -5), element('mark', { class: 'addr-mark', text: text.slice(-5) }));
}
function showLookalikeCheck(address, known, proceed) {
  const who = (state?.contacts || []).find(c => c.address === known)?.name || (known === state?.profile?.address ? 'your own wallet' : 'an address you used before');
  const retype = field('Type the last 5 characters of the new address', { placeholder: '•••••' });
  const go = element('button', { class: 'primary-button', text: 'I checked with the recipient ↗', onclick: () => {
    if (retype.input.value.trim().toLowerCase() !== address.slice(-5).toLowerCase()) { toast('The last 5 characters don’t match the new address.'); return; }
    confirmedLookalikes.add(address); $('modal').close(); proceed();
  } });
  modal('Check this address', [
    element('p', { class: 'danger', text: `This address starts and ends like ${who}, but it is a different address. Scammers create look-alike addresses to catch copy-and-paste payments.` }),
    element('div', { class: 'review-row' }, element('span', { text: 'New' }), highlightedAddress(address)),
    element('div', { class: 'review-row' }, element('span', { text: 'Known' }), highlightedAddress(known)),
    element('p', { text: 'Only continue if the recipient confirmed this exact address another way. The payment review still follows.' }),
    retype.label, go, element('button', { class: 'text-button', text: 'Cancel', onclick: () => $('modal').close() })], 'SAFETY');
}

// ---- iOS 165 parity: several payments pending at once ----
// The node already reserves each pending payment's coins and output slots; show what is reserved and what is available.
function microNoid(value) { const [w, f = ''] = String(value || '0').split('.'); return BigInt(w || '0') * 1000000n + BigInt((f + '000000').slice(0, 6)); }
function renderReserved() {
  let line = $('reserved-line');
  if (!line) { line = element('p', { id: 'reserved-line', class: 'reserved-line' }); $('balance-description')?.after(line); }
  const pending = (state?.prepared_payments || []).filter(p => !p.replaced_by && !(state.receipts || []).some(r => r.verified && r.txid === p.txid) && !(typeof needsNewApproval === 'function' && needsNewApproval(p.delivery_error)));
  if (!pending.length || !state.chain?.balance) { line.replaceChildren(); line.hidden = true; return; }
  const reserved = pending.reduce((sum, p) => sum + microNoid(p.amount) + microNoid(p.fee), 0n);
  const balance = microNoid(state.chain.balance);
  const available = balance > reserved ? balance - reserved : 0n;
  line.hidden = false;
  line.replaceChildren(`Reserved in ${pending.length} pending payment${pending.length === 1 ? '' : 's'}: ${formatMicronoid(reserved)} NOID · Available to send: ${formatMicronoid(available)} NOID`);
}

// ---- NOID/USD price (iOS 179–182 PriceFeed): one price for every screen, used while at most 30 minutes old ----
let usdQuote = null, usdTimer = 0;
const MAX_QUOTE_AGE = 30 * 60 * 1000;
function priceFresh() {
  return !!usdQuote && state?.prefs?.live_prices !== false && Number(usdQuote.usd) > 0
    && Date.now() - usdQuote.updated_ms <= MAX_QUOTE_AGE && usdQuote.updated_ms - Date.now() <= 60000;
}
function usdValue(noid) {
  if (!priceFresh()) return null;
  const value = Number(noid) * Number(usdQuote.usd);
  if (!Number.isFinite(value)) return null;
  if (value > 0 && value < 0.01) return '< $0.01';
  return value.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 });
}
async function refreshUsd(force) {
  clearTimeout(usdTimer); usdTimer = setTimeout(() => refreshUsd(), 60000);
  if (!state?.profile || state?.prefs?.live_prices === false || document.hidden) return;
  if (!force && usdQuote && Date.now() - usdQuote.fetched < 55000) return;
  try { usdQuote = { ...(await native('usd_quote')), fetched: Date.now() }; renderWallet(); } catch { /* the estimate simply stays hidden */ }
}
async function showCrashReports() {
  const reports = (await native('crash_reports')).reports || [];
  const rows = reports.map(r => element('details', { class: 'crash-report' }, element('summary', {}, element('strong', { text: new Date(r.created_ms).toLocaleString('en') }), element('small', { text: r.headline })),
    element('pre', { class: 'report-preview', text: r.text }),
    element('div', { class: 'modal-actions' }, element('button', { class: 'outline-button', text: 'Email to support', onclick: () => run(null, () => native('crash_email', { id: r.id })) }),
      element('button', { class: 'outline-button', text: 'Copy', onclick: () => copy(r.text) }),
      element('button', { class: 'outline-button danger', text: 'Delete', onclick: () => run(null, async () => { await native('crash_delete', { id: r.id }); showCrashReports(); }) }))));
  modal('Crash reports', [element('p', { text: 'If Parano1d crashes, details are saved on this phone. Nothing is sent unless you choose to email a report. Reports contain the app version, phone model and the error trace, never your keys, messages or addresses.' }),
    ...(rows.length ? rows : [element('p', { class: 'card-note', text: 'No crash reports on this phone.' })]),
    rows.length ? element('button', { class: 'outline-button danger', text: 'Delete all crash reports', onclick: () => run(null, async () => { await native('crash_delete', { id: 'all' }); showCrashReports(); }) }) : element('span')], 'SETTINGS');
}

// ---- Privacy (matches iOS PrivacyView; every line must stay true for this Android build) ----
function showPrivacy() {
  const panel = (title, text) => element('section', { class: 'connection-card' }, element('strong', { text: title }), element('p', { text }));
  modal('Privacy', [
    panel('Your keys stay on this phone', 'Your master key is created on this phone and saved encrypted with an Android Keystore key, behind your wallet PIN or fingerprint. It is never sent to us. We cannot recover it, so keep your backup safe. The watch holds no keys.'),
    panel('No accounts, no analytics', 'There is no sign-up and no tracking or advertising. The app does not collect analytics. Crash details are saved on this phone and reach us only if you choose to email one.'),
    panel('Public blockchain', 'NOID transactions are public. Amounts and addresses are visible on the chain to anyone; this app cannot hide on-chain activity.'),
    panel('Network requests', `In Paranoid mode the on-device node talks directly to Parano1d peers. Otherwise balance and payment lookups use a Parano1d node service, which sees your public address and IP address. If “Update in background” is on, the node also updates while charging on Wi-Fi. ${STORE_EDITION ? 'If you turn on Show artwork and names, token' : 'Token'} and NFT artwork and prices come from tradedata.parano1d.org, and the USD price from public price services (in Parano1d mode, only the Parano1d World feed); they see what is requested and your IP address. App VPN sends app traffic through the Parano1d relay instead of your normal connection.`),
    panel('Private messages', `Messages are end-to-end encrypted between connected contacts. The chat relay (chat.parano1d.org) holds encrypted packets until delivery and sees sender and recipient chat identities and IP addresses, not message text. Alerts are created on this phone and never include message text. Posts in the public #parano1d room are not encrypted and anyone can read them. Bluetooth messaging is off unless you turn it on.`),
    panel('Permissions', 'Camera: scanning QR codes. Bluetooth: Bluetooth messaging, only when you turn it on. Notifications: payment, receipt and message alerts. Wallpaper: only when you choose Set as wallpaper.'),
    element('button', { class: 'primary-button', text: 'Read the full privacy policy', onclick: () => run(null, () => native('open_link', { url: 'https://parano1dworld.com/privacy' })) }),
  ], 'SETTINGS');
}

// ---- Network check (iOS 166): a plain-language checklist with what to do for each item ----
function networkChecks() {
  const ago = ms => { if (!ms) return 'not yet'; const m = Math.round((Date.now() - ms) / 60000); return m < 1 ? 'just now' : m < 60 ? `${m} min ago` : `${Math.round(m / 60)} h ago`; };
  const height = Number(state?.chain?.balance_height || state?.chain?.height || 0);
  const vpn = state?.protection || {}, assets = assetState.data, mod = state?.moderation || {};
  const assetHeight = Number(assets?.holdings?.height || 0);
  return [
    ['Internet', state?.connection?.online ? ['green', 'Online.'] : ['red', 'Offline. Payments can be prepared from your saved balance and are sent when you reconnect.'], state?.connection?.online ? null : ['Connections', showConnections]],
    ['App VPN', !vpn.enabled && !state?.protected_route ? ['green', 'Off. Wallet and chat use your normal connection.'] : vpn.connected ? ['green', 'Connected. App traffic goes through the tunnel.'] : ['orange', 'Connecting. Traffic waits for the tunnel instead of leaking outside it.'], vpn.connected || !vpn.enabled ? null : ['Connections', showConnections]],
    ['On-device node', height ? ['green', `Verified · block ${height.toLocaleString()} · checked ${ago(state?.chain?.checked_at_ms)}.`] : state?.chain?.running ? ['orange', 'Verifying the chain now. The first check takes a few minutes.'] : ['red', 'Not verified yet.'], height ? null : ['Verify now', () => run(null, async () => { state = await native('sync'); render(); showNetworkCheck(); })]],
    ['Peers and chain', state?.paranoid_mode ? (height ? ['green', 'Direct P2P: your wallet talks to Parano1d peers.'] : ['orange', 'Direct P2P: waiting for peers.']) : ['green', 'Fast data from your endpoint; your phone still verifies every balance.'], null],
    ['Tokens and NFTs', assets?.installed ? (height && assetHeight + 2 < height ? ['orange', `Verified at block ${assetHeight.toLocaleString()}, behind the chain.`] : ['green', `Verified at block ${assetHeight.toLocaleString()}.`]) : ['orange', 'Not set up on this phone yet.'], assets?.installed && !(height && assetHeight + 2 < height) ? null : ['Update tokens and NFTs', () => run(null, async () => { await native('asset_sync'); assetState.data = null; await loadAssets(); showNetworkCheck(); })]],
    ['Public-chat safety list', !mod.enabled ? ['orange', 'Not configured in this build.'] : state?.community_paused ? ['green', 'Private-only chat is on; the public room is off.'] : mod.sequence ? (mod.stale ? ['orange', `List #${mod.sequence} is older than 7 days; its decisions still apply until they expire.`] : ['green', `Signed list #${mod.sequence} verified.`]) : ['orange', 'Not downloaded yet. It loads while #parano1d is open.'], mod.enabled && !state?.community_paused && !mod.sequence ? ['Open #parano1d', () => { $('modal').close(); selectTab('groups'); }] : null],
  ];
}
function showNetworkCheck() {
  const rows = networkChecks().map(([title, [color, text], fix]) => element('div', { class: 'check-row' },
    element('span', { class: `check-dot ${color}` }), element('span', {}, element('strong', { text: title }), element('small', { text })),
    fix ? element('button', { class: 'outline-button', text: fix[0], onclick: fix[1] }) : element('span')));
  const again = element('button', { class: 'primary-button', text: 'Run check now', onclick: () => run(again, async () => {
    if (state?.connection?.online) { try { state = await native('sync'); } catch { } ChatSafety.refreshModeration(); assetState.data = null; await loadAssets(); }
    render(); showNetworkCheck();
  }, 'Checking…') });
  modal('Network check', [...rows, again], 'SETTINGS');
}

// ---- Tokens and NFTs from this phone's verified Native State (same library as iOS) ----
const assetState = { loading: false, syncing: false, data: null, error: '', media: new Map(), pending: new Set(), queue: [], active: 0, collection: null };
// Floors read like iOS: 1,999 NOID / 31 NOID / 9.5 NOID (no trailing zeros).
function shortNoid(micro) { const [w, f = ''] = formatMicronoid(BigInt(micro)).split('.'); const frac = f.replace(/0+$/, ''); return Number(w).toLocaleString('en-US') + (frac ? '.' + frac : ''); }
function micro(units) { try { return formatMicronoid(BigInt(units)); } catch { return String(units || '—'); } }
async function loadAssets() {
  if (assetState.loading || !state?.profile) return;
  assetState.loading = true; renderAssets();
  try { assetState.data = await native('assets'); assetState.error = ''; }
  catch (error) { assetState.error = explainError(error.message); }
  finally { assetState.loading = false; renderAssets(); }
}
async function syncAssets(button) {
  if (assetState.syncing) return;
  assetState.syncing = true; renderAssets();
  try { await native('asset_sync'); toast('Tokens and NFTs verified on this phone.'); assetState.data = null; await loadAssets(); }
  catch (error) { toast(explainError(error.message)); }
  finally { assetState.syncing = false; renderAssets(); }
}
// Store edition (like the App Store edition): names and artwork load from tradedata.parano1d.org only after
// the person taps "Show artwork". Holdings themselves are always verified on the phone.
// Artwork is on by default in every edition. The only switch is
// Settings → NFTs; a saved "off" stays off and unset means on. Being offline always blocks it.
function externalMedia() { return state?.prefs?.external_media !== false && state?.connection?.online !== false; }
async function setArtwork(on) { state = await native('prefs_set', { external_media: !!on }); assetState.media.clear?.(); assetState.pending.clear?.(); render(); renderAssets(); }
function showArtworkButton() { return element('p', { class: 'card-note artwork-off-note', text: state?.connection?.online === false ? 'Artwork doesn’t load while offline.' : 'Artwork is off. You can turn it on in Settings → NFTs.' }); }
function mediaFor(hash) {
  if (!hash || !externalMedia()) return null;
  if (assetState.media.has(hash)) return assetState.media.get(hash);
  if (!assetState.pending.has(hash)) { assetState.pending.add(hash); assetState.queue.push(hash); pumpMedia(); }
  return null;
}
function pumpMedia() {
  while (assetState.active < 4 && assetState.queue.length) { // four artwork loads at a time, like iOS 163
    const hash = assetState.queue.shift(); assetState.active++;
    native('asset_media', { hash }).then(m => assetState.media.set(hash, m)).catch(e => assetState.media.set(hash, { error: e.message }))
      .finally(() => { assetState.active--; assetState.pending.delete(hash); scheduleAssetRender(); pumpMedia(); });
  }
}
let assetRenderTimer = 0;
function scheduleAssetRender() { clearTimeout(assetRenderTimer); assetRenderTimer = setTimeout(renderAssets, 120); }
function assetView() {
  const d = assetState.data;
  if (!d?.installed) return null;
  const items = d.holdings?.items || [];
  const nfts = items.filter(i => i.type === 'nft'), tokens = items.filter(i => i.type === 'token');
  const collections = new Map();
  for (const c of d.holdings?.collections || []) collections.set(c.collection_id, { ...c, items: [] });
  // This wallet's own listed NFTs sit below the wallet's NFTs, each group by number.
  const me = state?.profile?.address || '';
  const held = new Set(nfts.map(n => `${n.collection_id}#${n.serial}`));
  const listed = me ? (d.listings?.items || []).filter(l => l.seller === me && l.collection_id && !held.has(`${l.collection_id}#${l.serial}`)).map(l => ({ ...l, type: 'nft', owner: me, listed: true })) : [];
  for (const n of [...nfts, ...listed]) { if (!collections.has(n.collection_id)) collections.set(n.collection_id, { collection_id: n.collection_id, items: [] }); collections.get(n.collection_id).items.push(n); }
  const bySerial = (a, b) => { const x = BigInt(a.serial || '0'), y = BigInt(b.serial || '0'); return x < y ? -1 : x > y ? 1 : 0; };
  for (const c of collections.values()) c.items.sort((a, b) => (!!a.listed - !!b.listed) || bySerial(a, b));
  const floors = new Map();
  for (const l of d.listings?.items || []) { const ask = BigInt(l.ask_micronoid || '0'); const now = floors.get(l.collection_id); if (now == null || ask < now) floors.set(l.collection_id, ask); }
  const prices = new Map();
  if (!STORE_EDITION) for (const m of d.markets?.items || []) if (m.asset_id && m.price_denominator && m.price_denominator !== '0') prices.set(m.asset_id, m);
  const tokenTotals = new Map();
  for (const t of tokens) { const prev = tokenTotals.get(t.asset_id); tokenTotals.set(t.asset_id, { ...t, units: String(BigInt(prev?.units || '0') + BigInt(t.units || '0')) }); }
  return { nfts, listed, tokens: [...tokenTotals.values()], collections: [...collections.values()], floors, prices, height: d.holdings?.height };
}
function tokenValueNoid(token, market) {
  if (!market) return null;
  const units = BigInt(token.units || '0'), num = BigInt(market.price_numerator), den = BigInt(market.price_denominator);
  return formatMicronoid(units * num / den);
}
function assetTile(item, onclick) {
  const media = mediaFor(item.metadata_hash);
  // iOS 169: the art keeps its own shape and sits centred in the square, with its own rounded corners.
  const art = media?.thumbnail ? element('span', { class: 'nft-art framed' }, element('img', { class: 'nft-pic', src: media.thumbnail, alt: media.name || 'NFT' })) : element('span', { class: 'nft-art placeholder', text: media?.error ? '!' : '' });
  const locked = item.type === 'nft' && AssetSendUI.lockedNft(item.collection_id, item.serial);
  const caption = locked ? 'Pending transfer · locked' : item.serial ? `No. ${item.serial}` : '';
  return element('button', { class: `nft-tile${locked ? ' locked' : ''}${item.listed ? ' listed' : ''}`, onclick }, art, element('strong', { text: media?.name || `#${item.serial || ''}` }),
    item.listed ? element('span', { class: 'nft-tile-line' }, element('small', { text: caption }), listedBadge()) : element('small', { text: caption }));
}
/** "LISTED": this wallet listed the NFT (the listing holds it, so it can't be sent while listed). */
function listedBadge() { return element('span', { class: 'listed-badge', 'aria-label': 'Listed', text: 'LISTED' }); }
function listedLine(item) {
  if (STORE_EDITION || !item.listed) return '';
  try { return `Listed for sale · ${shortNoid(item.ask_micronoid)} NOID`; } catch { return 'Listed for sale · price unavailable'; }
}
function renderAssets() {
  AssetSendUI.refreshPending();
  const view = assetView();
  // Home: tokens under NOID, collections with their floor
  const rows = $('token-rows');
  if (rows) {
    rows.replaceChildren();
    for (const t of view?.tokens || []) {
      const m = mediaFor(t.metadata_hash); const market = view.prices.get(t.asset_id);
      const icon = m?.thumbnail ? element('img', { src: m.thumbnail, alt: '' }) : ((m?.symbol || m?.name) ? element('span', { class: 'round small', text: (m.symbol || m.name).slice(0, 1) }) : element('span', { class: 'round small' }, icon('cube')));
      const price = market ? `1 = ${formatMicronoid(BigInt(market.price_numerator) * 1000000n / BigInt(market.price_denominator))} NOID` : STORE_EDITION ? 'Token' : 'No NOID pool';
      const value = tokenValueNoid(t, market);
      rows.append(element('button', { class: 'asset-row token-row', onclick: () => AssetSendUI.show({ type: 'token', asset_id: t.asset_id, units: t.units }, m) }, icon, element('span', {}, element('strong', { text: m?.symbol || m?.name || 'Token' }), element('small', { text: price })),
        element('span', { class: 'asset-amount' }, hideBalances ? '••••' : micro(t.units), element('small', { text: value && !hideBalances ? `≈ ${value} NOID${usdValue(value) ? ' · ' + usdValue(value) : ''}` : '' }))));
    }
  }
  const home = $('collections-card'), list = $('collection-rows');
  if (home && list) {
    list.replaceChildren();
    for (const c of view?.collections || []) {
      const m = mediaFor(c.metadata_hash); const floor = view.floors.get(c.collection_id);
      list.append(element('button', { class: 'asset-row collection-row', onclick: () => { assetState.collection = c.collection_id; selectTab('nfts'); } },
        m?.thumbnail ? element('img', { src: m.thumbnail, alt: '' }) : element('span', { class: 'round small', text: (m?.name || 'C').slice(0, 1) }),
        element('span', {}, element('strong', { text: m?.name || 'Collection' }), element('small', { text: ((n, l) => l ? `${n} owned · ${l} listed` : `${n} owned`)(c.items.filter(i => !i.listed).length, c.items.filter(i => i.listed).length) })),
        element('span', { class: 'asset-amount' }, element('small', { class: 'eyebrow', text: 'FLOOR' }), floor != null ? `From ${shortNoid(floor)} NOID` : 'No listings')));
    }
    home.hidden = !(view?.collections || []).length;
  }
  // NFTs tab
  const panel = $('nft-view'); if (!panel || $('nfts-panel').hidden) return;
  const nodes = [];
  if (!assetState.data && !assetState.loading && !assetState.error) { loadAssets(); }
  if (assetState.loading && !assetState.data) nodes.push(element('div', { class: 'home-card empty-page' }, element('strong', { text: 'Reading your verified holdings…' })));
  else if (assetState.error) nodes.push(element('div', { class: 'home-card empty-page' }, element('strong', { text: 'NFTs are unavailable right now' }), element('p', { text: assetState.error }), element('button', { class: 'outline-button', text: 'Try again', onclick: loadAssets })));
  else if (assetState.data && !assetState.data.installed) {
    const go = element('button', { class: 'primary-button', text: assetState.syncing ? 'Verifying…' : 'Verify tokens and NFTs ↗', onclick: () => syncAssets(go) }); go.disabled = assetState.syncing;
    nodes.push(element('div', { class: 'home-card empty-page' }, element('span', { class: 'round big' }), element('strong', { text: 'Set up tokens and NFTs on this phone' }),
      element('p', { text: 'Your phone downloads and verifies the asset State from mainnet peers, the same way it checks NOID. This takes a few minutes the first time. Nothing is trusted from a server.' }), go));
  } else if (view) {
    const top = element('div', { class: 'nft-top' }, element('small', { text: `Verified · block ${Number(view.height || 0).toLocaleString()}` }),
      element('button', { class: 'text-button', text: assetState.syncing ? 'Updating…' : 'Update', onclick: () => syncAssets() }));
    nodes.push(top);
    // Internal edition only (internal-extras.js, left out of the Play edition): its link-out card.
    const extraCard = window.InternalExtras?.linkCard?.(); if (extraCard) nodes.push(extraCard);
    if (!view.nfts.length && !view.listed.length) nodes.push(element('div', { class: 'home-card empty-page' }, element('strong', { text: 'No NFTs in this wallet yet' }), element('p', { text: 'NFTs you receive appear here after your phone verifies them.' })));
    const shown = assetState.collection ? view.collections.filter(c => c.collection_id === assetState.collection) : view.collections;
    if (assetState.collection) nodes.push(element('button', { class: 'text-button', text: '‹ All collections', onclick: () => { assetState.collection = null; renderAssets(); } }));
    for (const c of shown) {
      if (!c.items.length) continue;
      const m = mediaFor(c.metadata_hash); const floor = view.floors.get(c.collection_id);
      const grid = element('div', { class: 'nft-grid' }, ...c.items.map(n => assetTile(n, () => showNft(n, m?.name))));
      nodes.push(element('section', { class: 'home-card' }, element('div', { class: 'section-heading' }, element('h2', { text: m?.name || 'Collection' }),
        element('span', { class: 'floor-chip', text: floor != null ? `From ${shortNoid(floor)} NOID${usdValue(shortNoid(floor).replace(/,/g, '')) ? ' ≈ ' + usdValue(shortNoid(floor).replace(/,/g, '')) : ''}` : 'No listings' })), grid));
    }
  }
  if (view) nodes.push(element('p', { class: 'card-note market-note', text: STORE_EDITION ? 'Prices from the Parano1d network.' : `Prices from the Parano1d network. Market data is information only, not advice. ${APP_NAME} doesn’t operate an exchange.` }));
  panel.replaceChildren(...nodes);
}
/** Internal edition only (internal-extras.js). The Play edition has no such link. */
function internalLink() { return window.InternalExtras?.linkButton?.() || element('span'); }
function showNft(item, collectionName) {
  // iOS 169 layout: art, Full screen, one line with collection and serial, the actions, then one card with the IDs.
  const media = mediaFor(item.metadata_hash) || {};
  const show = externalMedia();
  const owned = !item.owner || item.owner === state.profile?.address;
  const listed = !!item.listed;
  const image = element('img', { class: 'nft-pic', src: media.thumbnail || '', alt: media.name || 'NFT' });
  const full = { src: media.thumbnail || '' };
  if (show) native('asset_image', { hash: item.metadata_hash }).then(r => { image.src = r.data_url; full.src = r.data_url; }).catch(() => {});
  const action = (label, op, done) => { const b = element('button', { class: 'outline-button', text: label, onclick: () => run(b, async () => { await native(op, { hash: item.metadata_hash, name: media.name || 'NFT' }); toast(done); }) }); return b; };
  const open = () => showNftFullScreen(item, media, full.src || image.src, owned);
  const art = element('button', { class: 'nft-art framed nft-hero', 'aria-label': 'Open the artwork full screen', onclick: open }, image);
  const idRow = (title, value) => value ? element('div', { class: 'id-row' }, element('small', { text: title }),
    element('button', { class: 'id-value', title: value, text: middle(value), onclick: () => { navigator.clipboard?.writeText(value).then(() => toast(`${title} copied.`)).catch(() => {}); } })) : element('span');
  const serial = item.serial ? ` · Serial #${item.serial}` : '';
  modal(media.name || `NFT #${item.serial}`, [
    show ? art : showArtworkButton(),
    show ? element('button', { class: 'text-button full-link', text: owned ? '⤢ Full screen · save to Photos or wallpaper' : '⤢ View full screen', onclick: open }) : element('span'),
    element('p', { class: 'nft-subline', text: `${collectionName || 'Collection'}${serial}` }, listed ? listedBadge() : ''),
    listed ? element('p', { class: 'card-note listed-note', text: 'You listed this NFT. It can’t be sent while it’s listed.' }) : element('span'),
    listed && !STORE_EDITION ? element('p', { class: 'nft-subline', text: listedLine(item) }) : element('span'),
    internalLink(),
    media.description ? element('p', { class: 'card-note', text: media.description }) : element('span'),
    AssetSendUI.lockedNft(item.collection_id, item.serial) ? element('p', { class: 'card-note', text: 'Pending transfer · locked until the network’s verified receipt or the transfer’s verified expiry.' })
      : owned && !listed ? element('button', { class: 'primary-button', text: 'Send this NFT ↗', onclick: () => AssetSendUI.show({ type: 'nft', collection_id: item.collection_id, serial: item.serial }, media) }) : element('span'),
    show && owned ? element('div', { class: 'modal-actions' }, action('Save to Photos', 'asset_save', 'Saved to Photos › Parano1d.'), action('Set as wallpaper', 'asset_wallpaper', 'Wallpaper set.'), action('Share', 'asset_share', 'Choose where to share it.')) : element('span'),
    element('div', { class: 'home-card id-card' }, idRow('Collection ID', item.collection_id), idRow('Owner', item.owner || state.profile?.address || ''), idRow('Metadata hash', item.metadata_hash)),
    element('p', { class: 'card-note', text: 'Artwork is checked against the hash recorded on-chain. Tap an ID to copy it.' })], 'NFT');
}
/** One line each end, shortened in the middle (iOS truncationMode(.middle)). */
function middle(value, keep = 12) { value = String(value || ''); return value.length <= keep * 2 + 1 ? value : `${value.slice(0, keep)}…${value.slice(-keep)}`; }
/** Full-screen artwork: whole picture, double-tap to zoom, and the owner's actions (iOS NFTFullScreenView). */
function showNftFullScreen(item, media, src, owned) {
  const img = element('img', { class: 'fullscreen-art', src, alt: media.name || 'NFT' });
  let zoomed = false;
  img.addEventListener('dblclick', () => { zoomed = !zoomed; img.style.transform = zoomed ? 'scale(2.5)' : ''; });
  const close = element('button', { class: 'fullscreen-close', 'aria-label': 'Close', text: '✕' });
  const layer = element('div', { class: 'fullscreen-layer', role: 'dialog', 'aria-label': media.name || 'NFT' }, img, close);
  if (owned && externalMedia()) {
    const act = (label, op, done) => element('button', { class: 'outline-button', text: label, onclick: () => run(null, async () => { await native(op, { hash: item.metadata_hash, name: media.name || 'NFT' }); toast(done); }) });
    layer.append(element('div', { class: 'fullscreen-actions' }, act('Save to Photos', 'asset_save', 'Saved to Photos › Parano1d.'), act('Set as wallpaper', 'asset_wallpaper', 'Wallpaper set.'), act('Share', 'asset_share', 'Choose where to share it.')));
  }
  close.onclick = () => layer.remove();
  document.body.append(layer);
}

// ---- Parano1d Home and Ecosystem (match iOS) ----
let hideBalances = false;
const STORE_EDITION = window.PARANO1D_EDITION === 'store';
// iOS 183: official logos stand in for the name where the logo carries it; each group says who runs its sites.
// look: ['logo', file, height] full logo · ['mark', icon, wordmark] small icon plus wordmark · undefined = letter badge.
// Play edition's name: "Parano1d World Wallet"; the internal build keeps its current wording.
const APP_NAME = STORE_EDITION ? 'Parano1d World Wallet' : 'Parano1d World';
const SITE = {
  world: { title: 'Parano1d World', detail: 'Explorer and NOID price', url: 'https://parano1dworld.com', look: ['logo', 'logos/world-full-light.png', STORE_EDITION ? 92 : 84, 'logos/world-full-dark.png'] },
  mining: { title: 'Parano1d World Mining', detail: 'Mine NOID and check mining status', url: 'https://parano1dworld.com/#Mining', look: ['logo', 'logos/mining-full.png', STORE_EDITION ? 92 : 84] },
  parano1d: { title: 'Parano1d', detail: STORE_EDITION ? 'The Parano1d team’s site' : 'Project home', url: 'https://parano1d.org', team: true },
  docs: { title: 'Documentation', detail: STORE_EDITION ? 'How the Parano1d chain works' : 'How the chain and wallet work', url: 'https://docs.parano1d.org', team: true },
  lab: { title: 'Lab', detail: STORE_EDITION ? 'The Parano1d team’s research and tools' : 'Research and tools', url: 'https://lab.parano1d.org', team: true },
  noOne: { title: 'NO ONE', detail: 'Social deduction game', url: 'https://noone.parano1d.org', look: ['logo', 'logos/no-one.png', 46], independent: true },
};
// Store edition (iOS public edition): Parano1d World, the Parano1d project, then Independent services; every link shows a leaving notice.
// Internal edition (iOS TestFlight): Parano1d World and Parano1d, the directory, then games.
const ECOSYSTEM_GROUPS = STORE_EDITION ? [
  [null, null, [SITE.world, SITE.mining]],
  ['Parano1d project', `Run by the Parano1d team. ${APP_NAME} is a separate wallet and isn’t run by them.`, [SITE.parano1d, SITE.docs, SITE.lab]],
  ['Independent services', `Third-party sites and games. They aren’t run by or affiliated with ${APP_NAME}.`, [SITE.noOne]],
] : (window.InternalExtras?.ecosystemGroups?.(SITE) || [
  [null, null, [SITE.world, SITE.mining, SITE.parano1d, SITE.docs, SITE.lab, SITE.noOne]],
]);
const ECOSYSTEM = ECOSYSTEM_GROUPS.flatMap(([, , links]) => links);
function hostOf(url) { try { return new URL(url).host; } catch { return ''; } }
function openSite(site) {
  const open = () => run(null, () => native('open_link', { url: site.url }));
  if (!STORE_EDITION) { open(); return; }
  const host = hostOf(site.url);
  const message = site.independent
    ? `${site.title} is an independent service, not affiliated with ${APP_NAME}. The wallet doesn’t run it, hold your funds or see your keys. It opens in your browser.`
    : site.team ? `${host} is run by the Parano1d team, not by ${APP_NAME}. It opens in your browser; your wallet isn’t connected or shared.`
    : `${host} opens in your browser. Your wallet isn’t connected or shared.`;
  modal(`You’re leaving ${APP_NAME}`, [element('p', { text: message }),
    element('button', { class: 'primary-button', text: `Open ${host}`, onclick: () => { $('modal').close(); open(); } }),
    element('button', { class: 'outline-button', text: 'Stay', onclick: () => $('modal').close() })], 'LEAVING');
}
function siteRow(site) {
  const host = hostOf(site.url);
  const [kind, a, b, dark] = site.look || [];
  const label = [site.title, site.detail, host].filter(Boolean).join(', ');
  const hint = site.independent ? 'Independent service. Opens in your browser' : site.team ? 'Parano1d team site. Opens in your browser' : 'Opens in your browser';
  let body;
  // iOS 184: a logo with light and dark versions shows the one for the current theme.
  if (kind === 'logo') body = [element('span', { class: 'site-logo' }, element('img', { class: dark ? 'logo-light' : '', src: a, alt: '', style: `height:${b}px` }),
    dark ? element('img', { class: 'logo-dark', src: dark, alt: '', style: `height:${b}px` }) : element('span'), element('small', { text: host }))];
  else if (kind === 'mark') body = [element('img', { class: 'site-mark', src: a, alt: '' }),
    element('span', {}, element('img', { class: 'wordmark-img', src: b, alt: site.title }), element('small', { text: site.detail }), element('small', { class: 'site-host', text: host }))];
  else body = [element('span', { class: 'round small', text: site.title.slice(0, 1) }), element('span', {}, element('strong', { text: site.title }), site.detail ? element('small', { text: site.detail }) : element('span'), element('small', { class: 'site-host', text: host }))];
  return element('button', { class: `ecosystem-row${kind ? ' has-' + kind : ''}`, 'aria-label': label, title: hint, onclick: () => openSite(site) }, ...body, element('span', { class: 'chevron', text: '↗' }));
}
function renderEcosystem() {
  const list = $('ecosystem-list'); if (!list || list.dataset.built === '1') return;
  list.dataset.built = '1';
  const nodes = [];
  for (const [title, note, links] of ECOSYSTEM_GROUPS) {
    if (title) nodes.push(element('div', { class: 'ecosystem-group' }, element('h2', { text: title }), note ? element('small', { text: note }) : element('span')));
    nodes.push(element('div', { class: 'ecosystem-list' }, ...links.map(siteRow)));
  }
  list.replaceChildren(...nodes);
}
function connectionToggle(label, on, action) {
  const button = element('button', { class: 'connection-toggle', role: 'switch', 'aria-label': label, 'aria-checked': String(on), onclick: () => run(button, action) }, element('span', { text: on ? 'On' : 'Off' }), element('span', { class: 'toggle-track', 'aria-hidden': 'true' }));
  return button;
}
function renderCaptureSetting() {
  const panel = $('capture-setting'); if (!panel) return;
  const enabled = !!state.allow_screenshots;
  const toggle = connectionToggle('Allow screenshots', enabled, async () => { state = await native('allow_screenshots', { enabled: !enabled }); render(); });
  panel.replaceChildren(element('section', { class: 'connection-card' }, element('div', { class: 'connection-card-heading' }, cardTitle('photo', 'Allow screenshots'), toggle), element('p', { text: 'Save screenshots or record the app screen on this phone. Off by default.' })));
}
function renderBackgroundSetting() {
  const panel = $('background-setting'); if (!panel) return;
  const on = !!state.background_delivery;
  const toggle = connectionToggle('Receive with screen off', on, async () => { state = await native(on ? 'background_disable' : 'background_enable'); render(); });
  toggle.disabled = !!state.protection?.enabled;
  const card = element('section', { class: 'connection-card' }, element('div', { class: 'connection-card-heading' }, cardTitle('bell', 'Background messages'), toggle), element('p', { text: state.protection?.enabled ? 'Kept on while App VPN is enabled.' : 'Keep receiving with the screen off. Shows a notification while on.' }));
  if (on && state.notifications_enabled === false) card.append(element('button', { class: 'outline-button', text: 'Allow notifications', onclick: () => run(null, () => native('notification_settings')) }));
  panel.replaceChildren(card);
}
function showConnections() {
  modal('Connections', [element('div', { id: 'nearby-panel' })], 'PARAMO1D'); renderNearbyPanel();
}
function renderNearbyPanel() {
  const panel = $('nearby-panel'); if (!panel) return;
  const scroll = $('modal').scrollTop;
  const nearby = state?.nearby || {}, peers = nearby.peers || [];
  const online = !!state.connection?.online, protection = state.protection || {};
  const trying = !!protection.checking || !!state.connection?.checking;
  const title = online ? 'You are online' : peers.length ? 'Connected by Bluetooth' : trying ? 'Connecting to the internet…' : 'You are offline';
  const summary = online ? 'Messages send now.' : peers.length ? 'Messages travel through nearby phones.' : 'Messages stay saved until you connect.';
  const overview = element('div', { class: 'connection-overview', 'data-online': String(online) }, element('strong', { text: title }), element('p', { text: summary }));
  const internet = element('section', { class: 'connection-card' }, element('div', { class: 'connection-card-heading' }, element('strong', { text: 'Internet' }), element('span', { class: 'connection-badge', 'data-active': String(online), text: online ? 'Connected' : trying ? 'Checking…' : 'Not connected' })), element('p', { text: online ? protection.connected ? 'Connected through App VPN.' : 'Using your normal phone connection.' : 'If your phone has internet but this app cannot connect, turn on App VPN below.' }));
  if (!online) {
    const retry = element('button', { class: 'outline-button', text: trying ? 'Checking connection…' : 'Try again', onclick: () => run(retry, async () => {
      if (!(state.settings?.relays || []).length) state = await native('settings', { relays: ['https://chat.parano1d.org'], rpc: state.settings?.rpc || '' });
      await poll();
    }) });
    retry.disabled = trying; internet.append(retry);
  }
  const vpnOn = !!(protection.enabled || protection.checking || protection.connected);
  const vpnToggle = connectionToggle('App VPN', vpnOn, async () => { state = await native(vpnOn ? 'protection_disable' : 'protection_enable'); render(); });
  vpnToggle.disabled = !!state.chain?.running && !state.chain?.automatic;
  const vpn = element('section', { class: 'connection-card' }, element('div', { class: 'connection-card-heading' }, element('strong', { text: 'App VPN' }), vpnToggle), element('p', { class: 'connection-detail', text: protection.checking ? 'On · connecting…' : vpnOn ? online && protection.connected ? 'On · connected' : 'On · waiting for a connection' : 'Off · using your normal connection' }), element('p', { text: 'For restricted networks. Only Parano1d uses this VPN.' }));
  if (protection.problem) vpn.append(element('p', { class: 'danger', text: protection.problem }));
  if (state.chain?.running && !state.chain?.automatic) vpn.append(element('p', { text: 'Available after the current wallet check finishes.' }));
  const bluetooth = element('section', { class: 'connection-card' }, element('div', { class: 'connection-card-heading' }, element('strong', { text: 'Bluetooth' }), connectionToggle('Bluetooth messaging', !!nearby.enabled, async () => { state = await native(nearby.enabled ? 'nearby_stop' : 'nearby_start'); render(); })), element('p', { class: 'connection-detail', text: nearby.enabled ? peers.length ? `On · ${peers.length} phone${peers.length === 1 ? '' : 's'} connected` : 'On · no phone connected yet' : 'Off' }), element('p', { text: 'No internet needed. Enable on both phones.' }));
  if (nearby.enabled) {
    for (const peer of peers) bluetooth.append(element('button', { class: 'nearby-device connected', onclick: async () => { $('modal').close(); await openChat(`d:${peer.id}`); } }, personAvatar(peer.name), element('span', {}, element('strong', { text: peer.name }), element('small', { text: 'Connected · open chat' })), icon('direct')));
    const controls = peers.length ? element('details', {}, element('summary', { text: 'Connect another phone' })) : bluetooth;
    const search = element('button', { class: 'outline-button', text: nearby.scanning ? 'Looking for phones…' : 'Find a phone', onclick: () => run(search, async () => { state = await native('nearby_scan'); render(); }) });
    search.disabled = !!nearby.scanning;
    controls.append(search, element('button', { class: 'outline-button', text: 'Make this phone visible', onclick: () => run(null, () => native('nearby_visible')) }));
    for (const device of nearby.devices || []) {
      const connect = element('button', { class: 'nearby-device', onclick: () => run(connect, async () => { await native('nearby_connect', { address: device.address }); toast('Connecting to the nearby phone…'); }) }, icon('nearby'), element('span', {}, element('strong', { text: device.name }), element('small', { text: 'Tap to connect' })));
      controls.append(connect);
    }
    if (peers.length) bluetooth.append(controls);
    if (nearby.problem) bluetooth.append(element('p', { class: 'danger', text: nearby.problem }));
  }
  const wallet = element('section', { class: 'connection-card' }, element('div', { class: 'connection-card-heading' }, element('strong', { text: 'Wallet' }), element('span', { class: 'connection-badge', 'data-active': String(!!state.paranoid_mode), text: state.paranoid_mode ? 'Direct P2P' : 'Fast data' })), element('p', { text: state.paranoid_mode ? 'Paranoid mode is on. Your wallet gets data and sends payments directly through Parano1d peers. Chat keeps its usual connections; the public room has its own setting (Private-only chat).' : 'Your phone verifies the blockchain. A configurable data service helps find coins and deliver payments. Turn on Paranoid mode in the header for direct P2P.' }));
  panel.replaceChildren(overview, internet, wallet, vpn, bluetooth);
  $('modal').scrollTop = scroll;
}

function showBackup() {
  const password = field('Backup password · at least 12 characters', { type: 'password', autocomplete: 'new-password' });
  const confirm = field('Repeat password', { type: 'password', autocomplete: 'new-password' });
  const save = element('button', { class: 'primary-button', text: 'Save encrypted backup ↗', onclick: () => run(save, async () => {
    if (password.input.value.length < 12 || password.input.value !== confirm.input.value) throw new Error('Use matching passwords with at least 12 characters.');
    await native('backup_export', { password: password.input.value }); password.input.value = ''; confirm.input.value = ''; $('modal').close();
  }) });
  modal('Save a backup', [element('p', { text: 'Restore your wallet and chats if you lose this phone. Choose a password for this backup file and keep it separately.' }), password.label, confirm.label, save], 'BACKUP');
}

function showRestore() {
  const backup = field('Encrypted backup', { multiline: true, placeholder: 'Paste your encrypted backup JSON' });
  const password = field('Backup password', { type: 'password', autocomplete: 'current-password' });
  const restore = element('button', { class: 'primary-button', text: 'Restore identity ↗', onclick: () => run(restore, async () => {
    state = await native('backup_restore', { backup: backup.input.value, password: password.input.value }); password.input.value = ''; backup.input.value = ''; $('modal').close(); render(); await poll();
  }) });
  const file = element('button', { class: 'outline-button', text: 'Choose a backup file ↗', onclick: () => run(file, async () => { await native('backup_restore_file', { password: password.input.value }); password.input.value = ''; }) });
  modal('Restore from a backup', [element('p', { text: 'Enter the password you chose for your backup, then choose the saved file.' }), password.label, file, element('details', { class: 'advanced-settings' }, element('summary', { text: 'Paste backup text instead' }), backup.label, restore)], 'RESTORE');
}

function showInvoice() {
  if (!chat?.startsWith('d:')) return;
  const amount = field('Amount · NOID', { placeholder: '0.00' }); amount.input.inputMode = 'decimal';
  const memo = field('What is it for?', { placeholder: 'Optional note' });
  const send = element('button', { class: 'primary-button', text: 'Send payment request ↗', onclick: () => run(send, async () => {
    state = await native('invoice', { contact_id: chat.slice(2), amount: paymentAmount(amount.input.value), memo: memo.input.value }); $('modal').close(); render(); await poll();
  }) });
  modal('Request a payment.', [amount.label, memo.label, send], 'NOID / REQUEST');
}

function showDeleteConversation(scope) {
  const peer = scope.startsWith('d:') ? contact(scope.slice(2)) : null;
  if (!peer) return;
  const remove = element('button', { class: 'primary-button destructive-button', text: 'Delete chat', onclick: () => run(remove, async () => {
    state = await native('delete_conversation', { chat: scope, confirmed: true });
    ChatUI.conversationDeleted(scope);
    if (chat === scope) { chat = ''; tab = 'direct'; }
    $('modal').close(); render(); toast('Chat and contact deleted. New messages can still arrive.');
  }, 'Deleting…') });
  const content = [element('p', { text: `Delete your conversation with ${peer.name} and remove this contact from your list?` }),
    element('p', { text: 'This deletes the message history on this phone. A new message from this person will start a new chat.' })];
  if (chatMessages(scope).some(message => message.status === 'queued')) content.push(element('p', { text: 'Already queued messages will still be delivered.' }));
  content.push(element('button', { class: 'outline-button', text: 'Cancel', onclick: () => $('modal').close() }), remove);
  modal('Delete chat and contact?', content, 'DELETE CHAT');
}

function showActions() {
  if (!chat?.startsWith('d:')) {
    modal('Chat actions', [element('div', { class: 'modal-actions' }, element('button', { class: 'outline-button', text: 'Help send a saved payment', onclick: showBroadcastHelp }), element('button', { class: 'outline-button', text: 'Share my contact', onclick: showInvite }), element('button', { class: 'outline-button', text: 'Add a contact', onclick: showAddContact }))], 'COMMUNITY'); return;
  }
  modal('Payments', [element('div', { class: 'modal-actions' }, element('button', { class: 'outline-button', text: 'Send NOID', onclick: showSend }), element('button', { class: 'outline-button', text: 'Request NOID', onclick: showInvoice }), element('button', { class: 'outline-button', text: 'Share a payment receipt', onclick: showAttachReceipt }))], 'MESSAGES');
}

function showBroadcastHelp() {
  if(state?.community_paused) return;
  const payments = (state.prepared_payments || []).filter(p => !p.replaced_by && !needsNewApproval(p.delivery_error) && !(state.receipts || []).some(r => r.verified && r.txid === p.txid));
  const items = [element('p', { text: 'Choose a saved payment. Someone with internet can help send it to the network.' })];
  for (const payment of payments.slice(-20).reverse()) items.push(element('button', { class: 'saved-payment-choice', onclick: () => sharePrepared(payment.txid) }, element('strong', { text: `${payment.amount} NOID` }), element('small', { text: `To ${short(payment.to)} · fee ${payment.fee} NOID` }), element('span', { text: 'Ask the community to help' })));
  if (!payments.length) items.push(element('p', { text: 'No payments waiting to send.' }), element('button', { class: 'primary-button', text: 'Open wallet', onclick: () => { $('modal').close(); selectTab('wallet'); } }));
  const packet = field('Payment data', { multiline: true, placeholder: 'Paste exported payment data' });
  const send = element('button', { class: 'outline-button', text: 'Share imported payment', onclick: () => run(send, async () => { state = await native('send', { chat: 'g:parano1d', message: { type: 'transaction', intent_hex: packet.input.value.trim() } }); $('modal').close(); render(); }) });
  items.push(element('details', { class: 'advanced-settings' }, element('summary', { text: 'Import payment data' }), packet.label, send));
  modal('Ask for help sending a payment', items, 'COMMUNITY');
}

// "Why is this unavailable?" notices with a fix button (like iOS): never a silently disabled control.
function whyBlocked() {
  const notes = [], reconnecting = !!state?.protection?.checking;
  if (reconnecting) notes.push(['Waiting for App VPN', 'Wallet traffic waits for the tunnel instead of leaking outside it.', 'Connections', showConnections]);
  else if (!state?.connection?.online) notes.push(['You are offline', 'You can prepare a payment from the balance last verified on this phone; it is delivered when you reconnect or by a nearby phone.', 'Connections', showConnections]);
  if (state?.chain?.running && !state?.chain?.automatic) notes.push(['Wallet check in progress', 'Sending opens when the current check finishes. This usually takes under a minute.', '', null]);
  else if (!state?.chain?.height && !state?.chain?.balance_height) notes.push(['Balance not verified yet', 'Your phone must verify your balance once before it can sign a payment.', 'Update balance', () => { $('modal').close(); run(null, async () => { state = await native('sync'); render(); }); }]);
  if (!state?.backup_saved) notes.push(['No backup yet', 'Save an encrypted backup so you keep access to these funds if you lose this phone.', 'Save backup', showBackup]);
  return notes;
}
function blockedNotices() {
  return whyBlocked().map(([title, detail, label, fix]) => element('section', { class: 'why-blocked' }, element('strong', { text: title }), element('p', { text: detail }),
    fix ? element('button', { class: 'outline-button', text: label, onclick: fix }) : element('span')));
}

function showSend(prefill) {
  if (state?.watch_only) { toast('This is a watch-only address, so it can’t send. Add its master key to use it fully.'); return; }
  let layout = 'standard'; try { layout = localStorage.getItem('send.layout.v1') === 'quick' ? 'quick' : 'standard'; } catch { }
  if (layout === 'quick') { showQuickSend(prefill); return; }
  const peer = chat?.startsWith('d:') ? contact(chat.slice(2)) : null;
  const link = prefill && typeof prefill === 'object' && prefill.paymentLink ? prefill : null;
  const address = field('Recipient NOID address', { value: link?.to || peer?.address || '', placeholder: 'o1…' });
  const preset = link ? link.amount : prefill;
  const amount = field('Amount · NOID', { placeholder: '0.00', ...(typeof preset === 'string' && preset ? { value: preset } : {}) }); amount.input.inputMode = 'decimal';
  const button = element('button', { class: 'primary-button', text: 'Review payment ↗', onclick: () => reviewPayment(address.input.value.trim(), amount.input.value, '', button) });
  const book = element('button', { class: 'text-button', id: 'send-address-book', text: 'Address book', onclick: () => AddressBook.show((to) => showSend({ paymentLink: true, to, amount: amount.input.value })) });
  modal('Send NOID.', [...blockedNotices(), scannedField('address', address), book, amount.label, element('p', { text: state.connection?.online ? 'Exact fee shown before you approve.' : 'Use the balance last checked on this phone. A nearby person can carry your payment to the network.' }), button], 'NOID / SEND');
}

/** iOS Quick Send: choose a person, then an amount. Same review and confirmation as Standard Send (reviewPayment). */
async function showQuickSend(prefill) {
  await AddressBook.ensure();
  const book = AddressBook.cached();
  const peer = chat?.startsWith('d:') ? contact(chat.slice(2)) : null;
  const link = prefill && typeof prefill === 'object' && prefill.paymentLink ? prefill : null;
  const preset = link ? link.amount : typeof prefill === 'string' ? prefill : '';
  const own = state.profile?.address;
  const valid = (a) => /^o1[0-9a-z]{20,126}$/.test(a || '') && a !== own;
  const people = new Map();
  for (const c of state.contacts || []) if (valid(c.address)) people.set(c.address, { name: c.name, id: c.id });
  const recent = [];
  for (const p of [...(state.prepared_payments || [])].reverse()) if (valid(p.to) && !recent.includes(p.to)) recent.push(p.to);
  const saved = new Map((book.entries || []).filter(e => valid(e.address)).map(e => [e.address, e]));
  const nameOf = (a) => people.get(a)?.name || saved.get(a)?.name || `${a.slice(0, 9)}…${a.slice(-6)}`;
  const avatarOf = (a) => { const c = people.get(a); if (c) return WarriorAvatar.node(c.id, c.name); const e = saved.get(a); return e ? contactIconView(e.name, e.icon, 46) : personAvatar(''); };
  const address = field('Enter a receiving address', { value: link?.to || peer?.address || '', placeholder: 'o1…' });
  address.input.autocapitalize = 'off'; address.input.spellcheck = false; address.input.classList.add('mono-input');
  const chosen = element('div', { class: 'quick-chosen', id: 'quick-chosen' });
  const strips = [];
  const strip = (list) => { const row = element('div', { class: 'quick-people', role: 'list' }, ...list.slice(0, 20).map((a) => element('button', { class: 'quick-person', role: 'listitem', 'data-address': a, 'aria-label': `Send to ${nameOf(a)}, ${a}`, onclick: () => { address.input.value = a; update(); } }, avatarOf(a), element('small', { text: nameOf(a) })))); strips.push(row); return row; };
  function update() {
    const a = address.input.value.trim();
    for (const row of strips) for (const b of row.children) b.classList.toggle('active', b.dataset.address === a);
    if (valid(a)) chosen.replaceChildren(avatarOf(a), element('span', { class: 'row-text' }, element('strong', { text: nameOf(a) }), highlightedAddress(a)), element('span', { class: 'quick-check', 'aria-hidden': 'true' }, icon('check')),
      element('button', { class: 'text-button', id: 'quick-save', text: 'Save', onclick: () => AddressBook.save(a, people.get(a)?.id || '', () => showQuickSend({ paymentLink: true, to: a, amount: amount.input.value })) }));
    else chosen.replaceChildren(a ? element('small', { class: 'orange', text: 'Enter a valid o1… address.' }) : element('small', { text: 'Choose a person below, scan a QR code or paste an address.' }));
  }
  address.input.addEventListener('input', update);
  const scanField = scannedField('address', address), scan = scanField.querySelector('.scan-trigger');
  scan.classList.add('outline-button', 'quick-scan'); scan.setAttribute('aria-label', 'Scan a NOID address');
  const paste = element('button', { class: 'outline-button quick-paste', type: 'button', onclick: () => run(paste, async () => { address.input.value = (await native('paste_address')).address; update(); }) }, icon('copy'), 'Paste');
  const amount = field('Amount · NOID', { placeholder: '0', ...(preset ? { value: preset } : {}) }); amount.input.inputMode = 'decimal'; amount.input.classList.add('quick-amount');
  const balance = state.chain?.height ? state.chain.balance : '';
  const percent = (p) => { const b = element('button', { class: 'outline-button', text: `${p}%`, onclick: () => {
    const [whole, fraction = ''] = String(balance).replace(/,/g, '').split('.');
    try { amount.input.value = formatMicronoid(BigInt(whole + fraction.padEnd(6, '0').slice(0, 6)) * BigInt(p) / 100n).replace(/,/g, ''); } catch { }
  } }); b.disabled = !balance; return b; };
  const button = element('button', { class: 'primary-button', text: 'Review payment ↗', onclick: () => reviewPayment(address.input.value.trim(), amount.input.value, '', button) });
  const nodes = [...blockedNotices(), element('p', { class: 'card-note', text: 'Choose a person, then an amount.' }),
    element('div', { class: 'quick-entry' }, scan, paste)];
  if (recent.length) nodes.push(element('h3', { class: 'quick-title', text: 'Recent recipients' }), strip(recent));
  // iOS Quick Send: favourites first, then categories (when grouped), then saved addresses.
  const grouped = AddressBook.groupInSend(), categoryNames = new Set((book.categories || []).map(c => c.name));
  const inCategory = (a) => grouped && categoryNames.has(saved.get(a)?.category || '');
  const pick = (address) => { $('modal').close(); showQuickSend({ paymentLink: true, to: address, amount: amount.input.value }); };
  nodes.push(element('div', { class: 'quick-head' }, element('h3', { class: 'quick-title', text: 'Connected contacts' }),
    element('button', { class: 'text-button', id: 'quick-all-contacts', text: 'All contacts', onclick: () => showConnectedContacts((c) => pick(c.address)) })));
  const contactList = Favorites.sort((state.contacts || []).filter(c => valid(c.address) && !inCategory(c.address)));
  nodes.push(contactList.length ? strip(contactList.map(c => c.address)) : element('p', { class: 'card-note', text: 'Your connected chat contacts appear here.' }));
  if (grouped) for (const c of book.categories || []) {
    const list = [...saved.values()].filter(e => e.category === c.name).map(e => e.address);
    if (list.length) nodes.push(element('div', { class: 'quick-head' }, contactIconView(c.name, c.icon, 27), element('h3', { class: 'quick-title', text: c.name })), strip(list));
  }
  if (saved.size) {
    nodes.push(element('div', { class: 'quick-head' }, element('h3', { class: 'quick-title', text: 'Saved addresses' }),
      element('button', { class: 'text-button', id: 'quick-categories', text: grouped ? 'Categories on' : 'Categories off', onclick: () => { try { localStorage.setItem('send.contactCategoriesVisible', grouped ? '0' : '1'); } catch { } $('modal').close(); showQuickSend({ paymentLink: true, to: address.input.value.trim(), amount: amount.input.value }); } }),
      element('button', { class: 'text-button', id: 'quick-address-book', text: 'Address book', onclick: () => AddressBook.show(pick) })));
    nodes.push(strip([...saved.values()].filter(e => !inCategory(e.address)).map(e => e.address)));
  }
  nodes.push(element('section', { class: 'quick-panel' }, element('strong', { text: 'To' }), chosen, address.label),
    element('section', { class: 'quick-panel' }, element('div', { class: 'quick-asset' }, element('span', { class: 'round' }, icon('outgoing')), element('strong', { text: 'NOID' })), amount.label,
      element('div', { class: 'quick-percent' }, percent(25), percent(50), percent(75)),
      element('small', { class: 'card-note', text: balance ? `${balance} NOID verified on this phone` : 'Balance awaiting verification' })),
    element('p', { class: 'card-note', text: 'Exact fee shown before you approve. To send tokens or NFTs, open them in the NFTs tab.' }), button);
  modal('Quick Send', nodes, 'SEND');
  update();
}

async function reviewPayment(address, amount, invoiceId, button, useCached = !!state?.chain?.height || !state?.connection?.online, replacesTxid = '') {
  const twin = address && lookalikeAddress(address);
  if (twin && !confirmedLookalikes.has(address)) { showLookalikeCheck(address, twin, () => reviewPayment(address, amount, invoiceId, null, useCached, replacesTxid)); return; }
  await run(button, async () => {
    amount = paymentAmount(amount);
    if (!address) throw new Error('Enter the recipient’s NOID address.');
    // A verified balance of 0 can't pay: say so at once instead of preparing (which can take minutes).
    if (state?.chain?.height && !state.chain.running && /^0(\.0+)?$/.test(String(state.chain.balance ?? ''))) throw new Error('Your verified balance is 0 NOID. After you receive NOID, tap Update balance and try again.');
    toast(useCached ? 'Preparing from your saved balance…' : 'Updating your wallet and preparing the payment…');
    const review = await native('payment_preview', { address, amount, invoice_id: invoiceId, chat: chat?.startsWith('d:') ? chat : '', use_cached: useCached, replaces_txid: replacesTxid });
    // iOS 166: the fee is read from the exact transaction being signed, with ≈ USD and a note when the network is busy.
    const withUsd = v => { const usd = usdValue(v); return usd ? `${v} NOID · ≈ ${usd}` : `${v} NOID`; };
    const rows = [['To', highlightedAddress(review.to)], ['Amount', withUsd(review.amount)], ['Network fee', withUsd(review.fee)], ['Total', withUsd(review.total)]]
      .map(([key, value]) => element('div', { class: 'review-row' }, element('span', { text: key }), typeof value === 'string' ? element('span', { text: value }) : value));
    // Final confirmation repeats amount, fee and total, like iOS: the first tap asks, the second sends.
    const approve = element('button', { class: 'primary-button', text: 'Send payment ↗', onclick: () => {
      if (!approve.dataset.confirm) { approve.dataset.confirm = '1'; approve.textContent = `Confirm: send ${review.amount} + fee ${review.fee} = ${review.total} NOID`; return; }
      return run(approve, async () => {
      const sent = await native('payment_send', { review_id: review.review_id }); state = sent.state; render();
      const help = !state.community_paused ? element('button', { class: 'primary-button', text: 'Ask the community to help ↗', onclick: () => sharePrepared(sent.txid) }) : null;
      modal(sent.submitted ? 'Payment sent' : sent.delivery_queued ? 'Sending your payment' : 'Payment saved on this phone', [element('p', { text: sent.submitted ? 'Waiting for confirmation. Follow the payment in your wallet or chat.' : sent.delivery_queued ? 'Your payment is saved and is being sent to the network. Follow its status in your wallet or chat.' : state.paranoid_mode?'It will confirm after reaching the network. You can share it privately with a connected contact.':'It will need to reach the network to confirm. Ask the community to help send it.' }), element('button', { class: 'primary-button', text: 'View in wallet', onclick: () => { $('modal').close(); selectTab('wallet'); } }), help, element('details', { class: 'advanced-settings' }, element('summary', { text: 'Payment details' }), element('div', { class: 'detail', text: sent.txid }))], 'PAYMENT');
    }, 'Sending…'); } });
    const validity = element('p', { text: 'Send it soon. If the payment expires before reaching the network, you will need to approve a new one.' });
    if (review.valid_through_height) validity.append(element('details', { class: 'payment-validity-details' }, element('summary', { text: 'Payment timing' }), element('small', { text: `The network can accept this payment through block ${Number(review.valid_through_height).toLocaleString()}.` })));
    const busy = review.busy_fee ? element('p', { class: 'card-note', text: 'The network is busy, so this fee is above the usual minimum. Peers would not relay a lower fee.' }) : element('span');
    modal('Check your payment.', [...rows, busy, element('p', { text: review.using_saved_state ? 'Using the balance last checked on this phone.' : 'Check the recipient, amount and fee before sending.' }), validity, approve], 'AUTHORIZE');
  }, 'Preparing…');
}

function showAttachReceipt() {
  const invoice = chatMessages(chat).filter(m => m.message.type === 'invoice' && m.sender.id !== state.profile.id).at(-1)?.message;
  if (!invoice) { toast('Ask this contact to send a payment request first.'); return; }
  const items = [element('p', { text: 'Choose a checked payment receipt to send to this contact.' })];
  const receipts = (state.receipts || []).filter(r => r.verified);
  for (const receipt of receipts.slice(-20).reverse()) items.push(element('button', { class: 'saved-payment-choice', onclick: () => run(null, async () => { state = await native('share_receipt', { chat, txid: receipt.txid, invoice_id: invoice.invoice_id }); $('modal').close(); render(); }) }, element('strong', { text: receipt.amount ? `${receipt.amount} NOID` : 'Checked payment' }), element('small', { text: short(receipt.txid) }), element('span', { text: 'Send receipt' })));
  if (!receipts.length) items.push(element('p', { text: 'No checked receipts yet. Check a payment first, then its receipt will appear here.' }));
  const receipt = field('Receipt data', { multiline: true, placeholder: 'Paste exported receipt data' });
  const send = element('button', { class: 'outline-button', text: 'Send imported receipt', onclick: () => run(send, async () => { state = await native('send', { chat, message: { type: 'receipt', invoice_id: invoice.invoice_id, receipt_hex: receipt.input.value.trim() } }); $('modal').close(); render(); }) });
  items.push(element('details', { class: 'advanced-settings' }, element('summary', { text: 'Import receipt data' }), receipt.label, send));
  modal('Share a payment receipt', items, 'PAYMENTS');
}

async function verifyMessageReceipt(item) {
  const invoice = chatMessages(item.chat).find((m) => m.message.type === 'invoice' && m.message.invoice_id === item.message.invoice_id)?.message;
  if (!invoice) { toast('The original payment request is needed to check this receipt.'); return; }
  await run(null, async () => {
    toast('Checking the receipt against the verified chain…');
    const result = await native('receipt_verify', { receipt_hex: item.message.receipt_hex, invoice_id: invoice.invoice_id, address: invoice.address, amount: invoice.amount, message_id: item.id });
    state = result.state; render(); showReceipt(result.receipt);
  });
}

function showReceipt(receipt) {
  const amount = receipt.amount || (state.prepared_payments || []).find(p => p.txid === receipt.txid)?.amount;
  // iOS SaveableAddress: the recipient of a payment you sent can be named and saved to the address book.
  const to = receipt.to || (state.prepared_payments || []).find(p => p.txid === receipt.txid)?.to;
  const save = /^o1[0-9a-z]{20,126}$/.test(to || '') ? element('button', { class: 'text-button receipt-save', id: 'receipt-save-address', onclick: () => AddressBook.save(to, '', () => showReceipt(receipt)) }, icon('person'), `To ${AddressBook.nameFor(to) || shortAddr(to)} · Save address`) : '';
  modal('Payment verified.', [element('strong', { class: 'receipt-amount', text: amount ? `${amount} NOID` : 'Payment included in the blockchain' }), save, element('p', { text: `Checked on this phone. ${receipt.finalized ? 'Final confirmation reached.' : `${receipt.confirmations || 1} confirmations when checked.`}` }), element('details', { class: 'advanced-settings' }, element('summary', { text: 'Payment details' }), element('p', { text: `Block ${receipt.height}` }), element('div', { class: 'detail', text: receipt.txid }), element('button', { class: 'outline-button', text: 'Copy transaction ID', onclick: () => copy(receipt.txid) }), explorerButton(receipt.txid))], 'PAYMENT RECEIPT');
}
/** iOS TransactionExplorerLink: Parano1d World's explorer when it answers its status check, NOID Scan otherwise. */
function explorerButton(txid) {
  if (!/^[0-9a-f]{64}$/i.test(String(txid || ''))) return element('span');
  const button = element('button', { class: 'outline-button', text: 'Look up transaction in explorer ↗', onclick: () => run(button, () => native('explorer_tx', { txid })) });
  return button;
}

function showImportReceipt() {
  const receipt = field('Payment receipt', { multiline: true });
  const amount = field('Expected amount · NOID', { placeholder: '0.00' });
  const button = element('button', { class: 'primary-button', text: 'Verify receipt ↗', onclick: () => run(button, async () => {
    const result = await native('receipt_verify', { receipt_hex: receipt.input.value.trim(), address: state.profile.address, amount: amount.input.value, invoice_id: '', message_id: '' });
    state = result.state; render(); showReceipt(result.receipt);
  }) });
  modal('Check a payment.', [receipt.label, amount.label, button], 'IMPORT RECEIPT');
}

async function copy(text) { await run(null, async () => { await native('copy', { text }); toast('Copied.'); }); }
async function poll() {
  if (polling || !state?.profile || document.hidden) return;
  polling = true;
  try { state = await native('poll'); render(); }
  catch (error) { state.connection = { online: false }; renderConnection(); }
  finally { polling = false; }
}
window.paramo1dBack = () => {
  if ($('modal').open) { $('modal').close(); return true; }
  if (!$('onboarding').hidden) return WalletSetup.back();
  if (ChatUI.back()) return true;
  if (chat?.startsWith('d:')) { ChatUI.beforeSwitch(); chat = null; render(); return true; }
  const home='wallet';
  if (tab !== home) { selectTab(home); return true; }
  return false;
};
function selectTab(value) { if (state?.watch_only && ['chat', 'groups', 'direct'].includes(value)) { toast('Chat needs a wallet on this phone. Add the master key to use it.'); return; } if (['chat', 'groups', 'direct'].includes(value) && ChatName.needed()) { ChatName.ask(() => selectTab(value)); return; } if (value === 'chat') { value = lastChatTab; ChatSafety.requireRules(); } if (value === 'groups') ChatSafety.refreshModeration(); if(state?.community_paused && value==='groups') value='direct'; if (value === 'groups' || value === 'direct') lastChatTab = value; ChatUI.beforeSwitch(); tab = value; chat = value === 'groups' ? 'g:parano1d' : null; $('search').value = ''; render(); }

ChatUI.setup();
$('compose').addEventListener('keydown', (event) => { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && matchMedia('(min-width:761px)').matches) { event.preventDefault(); $('compose-form').requestSubmit(); } });
document.querySelectorAll('[data-tab]').forEach((button) => button.addEventListener('click', () => selectTab(button.dataset.tab)));
document.querySelectorAll('[data-chat]').forEach((button) => button.addEventListener('click', () => selectTab(button.dataset.chat)));
// Paranoid mode, App VPN and the connection status live in the Home "Connection" card, like iOS settings rows.
['connection', 'vpn-switch', 'vpn-description', 'paranoid-description'].forEach((id) => { const node = $(id); if (node) $('connection-strip').append(node); });
document.querySelector('.masthead').prepend($('paranoid-switch'));
$('lock-button').addEventListener('click', () => run(null, () => native('lock_now')));
$('nft-shortcut').addEventListener('click', () => selectTab('nfts'));
$('more-button').addEventListener('click', showHomeMore);
$('hide-balance').addEventListener('click', () => run(null, async () => { const next = !hideBalances; await native('watch_settings_set', { hide_balances: next }); hideBalances = next; renderWallet(); }));
native('watch_settings').then((w) => { hideBalances = !!w?.hide_balances; renderWallet(); }).catch(() => {});
$('search').addEventListener('input', renderConversations);
$('vpn-switch').addEventListener('click', async () => {
  const button = $('vpn-switch');
  if (button.disabled) return;
  if (!state?.profile) { toast('Create or restore your identity first.'); return; }
  const on = !!(state.protection?.enabled || state.protection?.checking || state.protection?.connected);
  button.switching = true; button.disabled = true;
  try {
    state = await native(on ? 'protection_disable' : 'protection_enable');
    render();
    toast(on ? 'App VPN off. Using your normal connection.' : state.protection?.connected ? 'App VPN connected. Only Parano1d uses it.' : 'App VPN on. Connecting…');
  } catch (error) { toast(explainError(error.message)); }
  finally { button.switching = false; render(); }
});
$('paranoid-switch').addEventListener('click', async () => {
  const button = $('paranoid-switch');
  if (button.disabled) return;
  if (!state?.profile) { toast('Create or restore your identity first.'); return; }
  const enabled = !state.paranoid_mode;
  button.disabled = true;
  try {
    state = await native('paranoid_mode', { enabled });
    render();
    if (!matchMedia('(prefers-reduced-motion: reduce)').matches) {
      document.body.classList.remove('mode-shift');
      void document.body.offsetWidth;
      document.body.classList.add('mode-shift');
      clearTimeout(button.animationTimer);
      button.animationTimer = setTimeout(() => document.body.classList.remove('mode-shift'), 380);
    }
    toast(enabled ? 'Paranoid mode on. Wallet via direct P2P, no endpoint.' : 'Paranoid mode off. Fast wallet data from your endpoint is on.');
  } catch (error) { toast(explainError(error.message)); }
  finally { button.disabled = false; }
});
$('back-button').addEventListener('click', () => { ChatUI.beforeSwitch(); chat = null; render(); });
$('new-button').addEventListener('click', showAddContact);
$('settings-button').addEventListener('click', () => state?.profile ? showSettings() : toast('Create or restore your identity first.'));
$('connection').addEventListener('click', () => state?.profile ? showConnections() : toast('Create or restore your identity first.'));
$('identity-button').addEventListener('click', showInvite);
$('invite-button').addEventListener('click', showInvite);
$('welcome-group').addEventListener('click', () => openChat('g:parano1d'));
$('close-modal').addEventListener('click', () => { if (!$('modal').classList.contains('locked-modal')) $('modal').close(); });
$('modal').addEventListener('cancel', event => { if ($('modal').classList.contains('locked-modal')) event.preventDefault(); });
$('modal').addEventListener('close', () => $('modal').classList.remove('locked-modal'));
$('modal').addEventListener('close', render);
$('message-scroll').addEventListener('scroll', () => { const s = $('message-scroll'); if (s.scrollHeight - s.scrollTop - s.clientHeight < 100) markCurrentRead(); }, { passive: true });
$('payment-button').addEventListener('click', showSend);
$('attachment-button').addEventListener('click', showActions);
$('chat-options-button').addEventListener('click', () => showChatOptions());
$('chat-profile').addEventListener('click', () => { if (chat?.startsWith('d:')) showContact(contact(chat.slice(2))); });
$('wallet-address').addEventListener('click', () => copy(state.profile.address));
$('receive-button').addEventListener('click', () => {
  const children = [element('p', { text: 'Scan this code or copy the address to receive NOID.' })];
  if (window.qrcode) {
    const code = qrcode(0, 'M'); code.addData(state.profile.address); code.make();
    const box = element('div', { class: 'qr' });
    const svg = new DOMParser().parseFromString(code.createSvgTag({ scalable: true, margin: 4 }), 'image/svg+xml').documentElement;
    box.append(document.importNode(svg, true)); children.push(box);
  }
  children.push(element('div', { class: 'detail', text: state.profile.address }), element('button', { class: 'primary-button', text: 'Copy address ↗', onclick: () => copy(state.profile.address) }));
  modal('Receive NOID.', children, 'RECEIVE');
});
$('send-button').addEventListener('click', showSend);
$('import-receipt-button').addEventListener('click', showImportReceipt);
$('sync-button').addEventListener('click', () => run(null, async () => {
  if (walletBusy) return;
  walletBusy = true; renderWallet();
  try { const before = state.chain?.balance; state = await native('sync'); render(); toast(state.chain?.running || state.protection?.checking ? 'Your wallet is updating in the background.' : state.chain?.balance === before ? 'No new payments.' : 'Balance updated.'); }
  finally { walletBusy = false; renderWallet(); }
}));
// Auto-lock counts time without use: taps and typing restart the period (reported at most every 5 seconds).
let lastUseReport = 0;
for (const type of ['pointerdown', 'keydown']) document.addEventListener(type, () => {
  const now = Date.now(); if (now - lastUseReport < 5000) return; lastUseReport = now;
  try { window.Paramo1d?.used?.(); } catch { }
}, { capture: true, passive: true });
document.addEventListener('visibilitychange', () => { reportVisibleChat(); clearInterval(pollTimer); if (!document.hidden) { poll(); pollTimer = setInterval(poll, 10000); } });
window.paramo1dOpenContact = (uri) => run(null, async () => { state = await native('import_contact', { uri }); render(); toast('Contact added.'); });
// Watch hand-off: open the contact's chat with Send NOID prefilled.
// Payment links (iOS 181): fill in Send only. The normal review, lookalike check and PIN still apply.
window.parano1dOpenPay = (to, amount) => {
  if (!/^o1[0-9a-z]{20,126}$/.test(to) || (amount && !/^\d+(\.\d{1,6})?$/.test(amount))) return;
  if (!state?.profile) { toast('Create or restore your wallet first.'); return; }
  $('modal').close(); selectTab('wallet'); showSend({ paymentLink: true, to, amount: amount || '' });
};
window.parano1dOpenSend = async (key, amount) => { if (/^d:[0-9a-f]{64}$/.test(key)) { $('modal').close(); await openChat(key); showSend(String(amount)); } };
window.paramo1dOpenChat = (key) => { if (/^d:[0-9a-f]{64}$/.test(key)) { $('modal').close(); return openChat(key); } };

(async () => {
  try { state = await native('boot'); render(); }
  catch (error) { WalletSetup.show(); if (window.Paramo1d) toast(error.message); }
  finally { window.Paramo1d?.ready?.(); }
  await poll(); pollTimer = setInterval(poll, 10000);
})();
