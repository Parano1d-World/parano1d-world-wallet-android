'use strict';
// Settings and More, rebuilt as a home page with category pages.
// Settings home: Parano1d mode, the wallet card, category rows, then Help and legal.
// Each category page opens with a header card (larger icon in a soft accent circle, the name in the
// editorial font, one line). Rows carry an icon; standard looks use an outline icon in the accent colour,
// Cyber uses pixel-panel icon tiles and Parano1d World uses the game's icons (or a riveted socket).
// The existing cards (App lock, Background messages, watch and so on) are filled by their render*Setting
// functions in app.js, which find their containers by id.

// Parano1d World: the game's icon for a row icon where one exists; anything else sits in a riveted socket.
const WORLD_ICON_ART = {
  lock: 'icon-privacy', wallet: 'icon-dome', chat: 'icon-comms', bell: 'icon-alliance', photo: 'icon-nfts', globe: 'icon-network',
  palette: 'icon-build', info: 'icon-support', help: 'icon-support', history: 'icon-receipts', receipt: 'icon-receipts', refresh: 'icon-sync',
  nearby: 'icon-nearby', users: 'icon-squad', community: 'icon-squad', mining: 'icon-mining', home: 'icon-home', send: 'icon-send',
  receive: 'icon-receive', more: 'icon-more', settings: 'icon-more', cube: 'icon-node',
};
const SETTINGS_LOOKS = [
  ['system', 'System', 'Follows your phone’s light or dark setting', 'monitor'],
  ['dark', 'Dark', 'Always dark', 'moon'],
  ['light', 'Light', 'Always light', 'sun'],
  ['cyber', 'Cyber', 'Pixel headings and outlined panels', 'grid'],
  ['world', 'Parano1d World', 'Lime on black, like the game', 'gamepad'],
];
let settingsPage = 'home';

function settingsIcon(name, danger = false) {
  const art = WORLD_ICON_ART[name];
  return element('span', { class: `row-icon${danger ? ' danger' : ''}${art ? ' has-art' : ''}`, 'aria-hidden': 'true' },
    art ? element('img', { class: 'world-art', src: `world/${art}.png`, alt: '' }) : '', icon(name));
}
/** One settings row: an icon, a title, an optional one-line description, and a chevron or a control. */
function settingsRow({ icon: name, title, detail = '', onclick, control, danger = false, check, id }) {
  const text = element('span', { class: 'row-text' }, element('strong', { text: title }), detail ? element('small', { text: detail }) : '');
  if (control) return element('div', { class: 'settings-line icon-row', ...(id ? { id } : {}) }, settingsIcon(name, danger), text, control);
  const trailing = check !== undefined ? element('span', { class: `row-check${check ? ' on' : ''}`, 'aria-hidden': 'true' }, check ? icon('check') : '')
    : element('span', { class: 'settings-chevron', 'aria-hidden': 'true', text: '›' });
  const attrs = { class: `settings-row icon-row${danger ? ' danger-row' : ''}`, onclick, ...(id ? { id } : {}) };
  if (check !== undefined) { attrs.role = 'radio'; attrs['aria-checked'] = String(!!check); }
  return element('button', attrs, settingsIcon(name, danger), text, trailing);
}
function settingsSection(title, ...nodes) {
  return element('section', { class: 'settings-group' }, title ? element('h3', { class: 'settings-group-title', text: title }) : '', element('div', { class: 'settings-group-body' }, ...nodes));
}
function settingsHero(name, title, line) {
  return element('div', { class: 'settings-hero' }, element('span', { class: 'hero-icon' }, settingsIcon(name)), element('span', {}, element('h2', { text: title }), element('p', { text: line })));
}
function settingsBack(label = 'Settings', to = showSettings) {
  return element('button', { class: 'settings-back', onclick: () => to() }, icon('back'), label);
}
function settingsToggle(label, on, action) { return connectionToggle(label, on, async () => { await action(); render(); refreshSettings(); }); }
function refreshSettings() { if ($('modal').open && $('modal').dataset.kind === 'SETTINGS') openSettingsPage(settingsPage); }

function showSettings() { openSettingsPage('home'); }
function openSettingsPage(page) {
  settingsPage = page;
  const pages = { home: settingsHome, wallet: settingsWallet, autolock: settingsAutoLock, accounts: settingsAccounts, chat: settingsChat, nearby: settingsNearby, notifications: settingsNotifications, nfts: settingsNfts, network: settingsNetwork, appearance: settingsAppearance, about: settingsAbout };
  const [title, nodes, after] = (pages[page] || settingsHome)();
  const scroll = $('modal-content').scrollTop;
  const same = $('modal').open && $('modal').dataset.page === page;
  modal(title, nodes, 'SETTINGS');
  $('modal').dataset.page = page;
  $('modal').addEventListener('close', () => { $('modal').dataset.page = ''; }, { once: true });
  if (same) $('modal-content').scrollTop = scroll;
  after?.();
}

function settingsHome() {
  const paranoidOn = !!state.paranoid_mode;
  const mode = settingsRow({ icon: 'lock', title: 'Parano1d mode', detail: paranoidOn ? 'On: talks only to blockchain peers' : 'Most private setting. Turns the app orange.',
    control: settingsToggle('Parano1d mode', paranoidOn, async () => {
      if (!state?.profile) { toast('Create or restore your identity first.'); return; }
      // Turning Parano1d mode on or off recolours the whole page at once (applyTheme runs in render()).
      state = await native('paranoid_mode', { enabled: !paranoidOn });
    }) });
  const status = state.watch_only ? 'Watch-only' : state.locked ? 'Locked' : 'Unlocked on this phone';
  const address = state.profile?.address || '';
  const card = element('button', { class: 'settings-wallet-card', onclick: () => openSettingsPage('wallet') }, ownAvatar(),
    element('span', { class: 'row-text' }, element('strong', { text: state.watch_only ? 'Watch-only' : state.wallet_name || 'Main wallet' }),
      element('small', { class: 'addr', text: address ? `${address.slice(0, 10)}…${address.slice(-6)}` : 'No address yet' }),
      element('small', { class: `wallet-status${state.watch_only ? ' watch' : ''}`, text: status })), element('span', { class: 'settings-chevron', 'aria-hidden': 'true', text: '›' }));
  const mining = typeof MiningUI !== 'undefined';
  const nodes = [
    element('div', { class: 'settings-group parano1d-mode' }, element('div', { class: 'settings-group-body' }, mode)),
    element('div', { class: 'settings-group' }, card),
    settingsSection('',
      settingsRow({ icon: 'wallet', title: 'Wallet', detail: 'Lock, backup and your accounts', onclick: () => openSettingsPage('wallet') }),
      state.watch_only ? '' : settingsRow({ icon: 'chat', title: 'Chat', detail: 'Your contacts and how messages travel', onclick: () => openSettingsPage('chat') }),
      settingsRow({ icon: 'bell', title: 'Notifications', detail: 'Choose what you’re alerted about', onclick: () => openSettingsPage('notifications') }),
      settingsRow({ icon: 'photo', title: 'NFTs', detail: 'Show or hide NFT pictures', onclick: () => openSettingsPage('nfts') }),
      settingsRow({ icon: 'globe', title: 'Network', detail: 'Offline mode and checking the chain', onclick: () => openSettingsPage('network') }),
      settingsRow({ icon: 'palette', title: 'Appearance', detail: !STORE_EDITION && mining ? 'Look, tabs and hidden balances' : 'Pick a look and hide balances', onclick: () => openSettingsPage('appearance') }),
      settingsRow({ icon: 'info', title: 'About', detail: 'Version, privacy and help', onclick: () => openSettingsPage('about') })),
    settingsSection('Help and legal',
      settingsRow({ icon: 'flag', title: 'Report a problem', detail: 'Opens your email app to support@koajent.com', onclick: () => run(null, () => native('report_email', { subject: 'Parano1d Android — problem report', body: `App: Parano1d Android ${state.app_version || ''}\nDescribe what happened:\n\n(Never include your recovery key or PIN.)` })) }),
      settingsRow({ icon: 'globe', title: 'Support', onclick: () => run(null, () => native('open_link', { url: 'https://parano1dworld.com/support' })) }),
      settingsRow({ icon: 'shield', title: 'Privacy policy', onclick: () => run(null, () => native('open_link', { url: 'https://parano1dworld.com/privacy' })) }),
      settingsRow({ icon: 'layers', title: 'Documentation', onclick: () => run(null, () => native('open_link', { url: 'https://docs.parano1d.org' })) })),
  ];
  if (state.storage_full) nodes.unshift(element('section', { class: 'wallet-reminder' }, element('strong', { text: 'Storage is full' }), element('p', { text: 'Choose the chats to clear so new messages can arrive.' }), element('button', { class: 'outline-button', text: 'Choose chats', onclick: () => void ChatStorageUI.open() })));
  return ['Settings', nodes];
}

function settingsWallet() {
  return ['Wallet', [settingsBack(), settingsHero('wallet', 'Wallet', 'Keep your wallet locked, backed up and organized.'),
    settingsSection('Wallet access', element('div', { id: 'security-setting' }), element('div', { id: 'capture-setting' }),
      state.watch_only ? '' : settingsRow({ icon: 'key', id: 'app-code-row', title: state.app_code?.set ? `Change app ${codeLabel(state.app_code.kind)}` : 'Set app PIN or password', onclick: () => AppCodeSetup.open(false) }),
      state.watch_only ? '' : settingsRow({ icon: 'key', id: 'master-key-row', title: 'Master key and backup', onclick: () => { $('modal').close(); WalletSetup.open('backup'); } }),
      state.watch_only || !(state.security?.enabled || state.app_code?.set) ? '' : settingsRow({ icon: 'lock', title: 'Lock wallet now', id: 'lock-now', onclick: () => run(null, async () => { $('modal').close(); await native('lock_now'); }) }),
      settingsRow({ icon: 'clock', title: 'Auto-lock', detail: autoLockLabel(state.auto_lock_seconds ?? 60), id: 'auto-lock-row', onclick: () => openSettingsPage('autolock') })),
    state.watch_only ? settingsSection('Watch-only address', watchAddressEditor()) : '',
    state.watch_only || !state.accounts ? '' : settingsSection('Wallet management',
      settingsRow({ icon: 'person', title: 'Wallets and accounts', detail: state.wallet_name || '', id: 'accounts-row', onclick: () => openSettingsPage('accounts') }),
      settingsRow({ icon: 'trash', danger: true, id: 'erase-ten-row', title: 'Erase local wallet key after 10 wrong app-code entries',
        control: (() => { const t = settingsToggle('Erase local wallet key after 10 wrong app-code entries', !!state.app_code?.erase_on_ten, async () => {
          if (state.app_code?.erase_on_ten) { state = await native('app_code_erase', { enabled: false }); return; }
          confirmEraseOnTen();
        }); if (!state.app_code?.set) t.disabled = true; return t; })() }),
      element('details', { class: 'advanced-settings' }, element('summary', { text: 'About local key erase' }),
        element('p', { class: 'card-note', text: 'Off by default. Only wrong app PIN/password entries count, not fingerprint or phone-PIN failures. A thief can intentionally trigger this erase. Funds remain on-chain and anyone with the original master key can restore them. Pending signed payments may still settle.' }))),
    state.watch_only ? '' : settingsSection('Send screen', sendLayoutRow()),
    settingsSection('Backup',
      settingsRow({ icon: 'key', title: 'Save an encrypted backup', detail: state.backup_saved ? 'Saved on this phone' : 'Recommended', onclick: showBackup }),
      settingsRow({ icon: 'download', title: 'Import a payment receipt', onclick: showImportReceipt })),
    settingsSection('Wear OS watch', element('div', { id: 'watch-setting' }))],
  () => { renderSecuritySetting(); renderCaptureSetting(); renderWatchSetting(); }];
}

function settingsChat() {
  return ['Chat', [settingsBack(), settingsHero('chat', 'Chat', 'Private messages, signed by your wallet.'),
    settingsSection('How messages travel',
      settingsRow({ icon: 'globe', title: 'Online message delivery', detail: 'Encrypted through chat.parano1d.org, which can’t read messages but sees which chat identities talk and when. Off: nearby phones only.',
        control: settingsToggle('Online message delivery', !!state.online_delivery, async () => { state = await native('online_delivery', { enabled: !state.online_delivery }); }) }),
      settingsRow({ icon: 'nearby', title: 'Nearby relay', detail: 'Pass messages to nearby phones over Bluetooth.', onclick: () => openSettingsPage('nearby') }),
      element('div', { id: 'public-chat-setting' })),
    settingsSection('Your avatar', element('button', { class: 'settings-row icon-row', id: 'personalize-avatar', onclick: () => Avatars.showSettings() },
      Avatars.own(element('span', { class: 'avatar row-avatar', 'data-own-avatar': '' })), element('span', { class: 'row-text' }, element('strong', { text: 'Personalize avatar' }), element('small', { text: 'A character, pixel art, symbol or monogram' })), element('span', { class: 'settings-chevron', 'aria-hidden': 'true', text: '›' }))),
    settingsSection('Your contacts',
      settingsRow({ icon: 'block', title: 'Blocked contacts', onclick: () => ChatSafety.blockedList() }),
      settingsRow({ icon: 'flag', title: 'Chat rules', onclick: () => ChatSafety.showRules() }),
      settingsRow({ icon: 'layers', title: 'Chat storage', onclick: () => void ChatStorageUI.open() }))],
  () => renderPublicChatSetting()];
}

function settingsNearby() {
  return ['Nearby relay', [settingsBack('Chat', () => openSettingsPage('chat')), settingsHero('nearby', 'Nearby relay', 'Pass messages to nearby phones over Bluetooth.'), element('div', { id: 'nearby-panel' })], () => renderNearbyPanel()];
}

function settingsNotifications() {
  const a = state.alerts || {};
  const alert = (key, name, title) => settingsRow({ icon: name, title, id: `alert-${key}`, control: settingsToggle(title, !!a[key], async () => {
    if (!a[key] && a.allowed === false) { await native('notification_settings'); toast('Allow notifications for Parano1d, then turn this on.'); return; }
    state = await native('alerts_set', { [key]: !a[key] });
  }) });
  return ['Notifications', [settingsBack(), settingsHero('bell', 'Notifications', 'Alerts never show your address or amounts.'),
    settingsSection('Wallet activity',
      alert('balance', 'receive', 'Verified NOID balance increases'), alert('assets', 'layers', 'Verified token and NFT additions'),
      alert('nft_changes', 'refresh', 'NFT ownership changes to review'), alert('sends', 'send', 'Finalized outgoing payments'),
      element('p', { class: 'card-note', text: 'Alerts use the app icon and contain no wallet address or amount. A balance increase can include a transfer, a reward or returned change; review history to identify it.' })),
    settingsSection('Private chat', alert('messages', 'chat', 'New private messages'),
      element('p', { class: 'card-note', text: 'Alerts say only that a signed message arrived. Message text and sender identity stay off the lock screen. Tap an alert to open the conversation after unlocking.' })),
    settingsSection('Delivery', alert('foreground', 'bell', 'Show alerts while the app is open'), element('div', { id: 'background-setting' }),
      settingsRow({ icon: 'settings', title: 'Open Android notification settings', onclick: () => run(null, () => native('notification_settings')) }))], () => renderBackgroundSetting()];
}

function settingsNfts() {
  return ['NFTs', [settingsBack(), settingsHero('photo', 'NFTs', 'Every picture is checked against the blockchain.'),
    settingsSection('Pictures', settingsRow({ icon: 'photo', title: 'Show NFT and token artwork', detail: 'Pictures load from tradedata.parano1d.org, which can see your IP address and which images you view. Each image is checked against its on-chain metadata hash. On by default; turn it off here. Offline mode always blocks it.',
      control: settingsToggle('Show NFT and token artwork', state.prefs?.external_media !== false, async () => { await setArtwork(state.prefs?.external_media === false); }) }))]];
}

function settingsNetwork() {
  const livePrices = state.prefs?.live_prices !== false;
  const backgroundNode = state.prefs?.background_node !== false;
  const relays = field('Delivery relays · one HTTPS address per line', { multiline: true, value: (state.settings?.relays || []).join('\n'), placeholder: 'https://your-relay.example' });
  const rpc = field('Chain data endpoint · your own node goes here', { value: state.settings?.rpc || '', placeholder: 'https://your-node.example/rpc' });
  const backups = field('Backup chain endpoints · up to three, one per line. If an endpoint can’t be reached, the next is tried, then the public node chat.parano1d.org, then mainnet peers. Your phone verifies every answer. Parano1d mode always uses peers.', { multiline: true, value: (state.settings?.rpc_backups || []).join('\n'), placeholder: 'https://backup-node.example/rpc' });
  const save = element('button', { class: 'primary-button', text: 'Save connection settings ↗', onclick: () => run(save, async () => {
    state = await native('settings', { relays: relays.input.value.split('\n').map((s) => s.trim()).filter(Boolean), rpc: rpc.input.value.trim(), rpc_backups: backups.input.value.split('\n').map((s) => s.trim()).filter(Boolean) }); $('modal').close(); render(); await poll();
  }) });
  return ['Network', [settingsBack(), settingsHero('globe', 'Network', 'How this phone connects and checks the blockchain.'),
    settingsSection('Connections',
      settingsRow({ icon: 'globe', title: 'Offline mode', id: 'offline-row', detail: 'Stops every internet request: chain checks, chat relay, prices, artwork and mining. Nearby Bluetooth relay keeps working.',
        control: settingsToggle('Offline mode', !!state.offline, async () => { state = await native('offline_mode', { enabled: !state.offline }); }) })),
    settingsSection('Checking the chain', element('div', { id: 'node-setting' }),
      settingsRow({ icon: 'refresh', title: 'Update in background', detail: 'While charging on Wi-Fi, Android may run the on-device node with the app closed, so the chain, tokens and NFTs are close to current when you open it. Public chain data only: no keys, nothing is signed or sent.',
        control: settingsToggle('Update in background', backgroundNode, async () => { state = await native('prefs_set', { background_node: !backgroundNode }); }) }),
      settingsRow({ icon: 'receive', title: 'Live USD estimates', detail: `Price requests omit your wallet address. Estimates need a price at most 30 minutes old. Paused while App VPN is on.${priceFresh() ? ` Updated ${time(usdQuote.updated_ms)}.` : ''}`,
        control: settingsToggle('Live USD estimates', livePrices, async () => { state = await native('prefs_set', { live_prices: !livePrices }); usdQuote = null; refreshUsd(true); }) })),
    settingsSection('Connection',
      settingsRow({ icon: 'check', title: 'Network check', detail: 'Internet, App VPN, node, peers, tokens and NFTs, chat safety list', onclick: showNetworkCheck }),
      settingsRow({ icon: 'nearby', title: 'Connections', detail: 'Peers, endpoint, App VPN and nearby', onclick: showConnections })),
    element('details', { class: 'advanced-settings' }, element('summary', { text: 'Advanced settings' }), element('p', { text: 'Choose your delivery relays and wallet data service.' }), relays.label, rpc.label, backups.label, save, element('p', { text: 'Direct-message encryption: X25519 and XSalsa20-Poly1305. The community chat and NOID payments are public.' }))],
  () => renderNodeSetting()];
}

/** iOS AccountsView: master keys on this phone, then the accounts derived from the selected one. */
function settingsAccounts() {
  const a = state.accounts || { wallets: [] };
  const roots = a.wallets, current = roots.find((w) => w.root === a.selected_root) || roots[0];
  const call = (op, args = {}, done) => run(null, async () => { const next = await native(op, args); state = next; render(); refreshSettings(); done?.(next); });
  const ask = (title, label, value, save) => {
    const input = element('input', { value, maxlength: '40', 'aria-label': label });
    const go = element('button', { class: 'primary-button', text: 'Save', onclick: () => { const name = input.value.trim(); if (!name) return; $('modal').close(); save(name); } });
    modal(title, [element('label', { class: 'field' }, element('span', { text: label }), input), element('p', { class: 'card-note', text: 'The name is saved only on this device.' }), go,
      element('button', { class: 'outline-button', text: 'Cancel', onclick: () => openSettingsPage('accounts') })], 'WALLETS');
  };
  const confirmRemove = (title, text, action) => modal(title, [element('p', { text }),
    element('button', { class: 'primary-button danger-fill', text: 'Remove from this device', onclick: action }),
    element('button', { class: 'outline-button', text: 'Cancel', onclick: () => openSettingsPage('accounts') })], 'WALLETS');
  const rootMenu = (w, i) => modal(w.name, [element('div', { class: 'modal-actions' },
    element('button', { text: 'Rename', onclick: () => ask('Rename wallet', 'Wallet name', w.name, (name) => call('accounts_rename_root', { root: w.root, name }, () => openSettingsPage('accounts'))) }),
    w.root === a.default_root ? '' : element('button', { text: 'Set as default', onclick: () => { $('modal').close(); call('accounts_default', { root: w.root }, () => openSettingsPage('accounts')); } }),
    roots.length > 1 ? element('button', { text: 'Reorder wallets', onclick: reorderWallets }) : '',
    element('button', { text: w.hidden ? 'Show in the main list' : 'Hide from the main list', onclick: () => { $('modal').close(); call('accounts_hide', { root: w.root, hidden: !w.hidden }, () => openSettingsPage('accounts')); } }),
    w.origin ? '' : element('button', { class: 'danger', text: 'Remove from this device', onclick: () => confirmRemove(`Remove ${w.name}?`, 'This deletes its local key and saved account list from this phone. It does not move or erase on-chain funds. Confirm you have its master key backup. Pending payments block removal.', () => { $('modal').close(); call('accounts_remove_root', { root: w.root }, () => openSettingsPage('accounts')); }) }))], 'WALLET');
  // iOS 200: drag to reorder. The wallet order is saved in the encrypted store (Save / Cancel);
  // the account order is how this phone lists them, saved as each row is moved (as iOS's UserDefaults order).
  const reorderWallets = () => ReorderList.open({ title: 'Reorder wallets', eyebrow: 'WALLETS', mode: 'save',
    items: roots.map((w) => ({ id: w.root, title: w.name, subtitle: `${w.root.slice(0, 10)}…${w.root.slice(-6)}`, icon: 'wallet' })),
    onSave: async (order) => { const next = await native('accounts_order', { roots: order }); state = next; render(); refreshSettings(); openSettingsPage('accounts'); },
    onCancel: () => openSettingsPage('accounts') });
  const orderKey = (root) => `accounts-order:${root}`;
  const orderedAccounts = (w) => {
    let saved = []; try { saved = JSON.parse(localStorage.getItem(orderKey(w.root)) || '[]'); } catch { }
    const rank = new Map(saved.map((index, at) => [index, at]));
    return w.accounts.slice().sort((x, y) => (rank.has(x.index) ? rank.get(x.index) : 1e6 + x.index) - (rank.has(y.index) ? rank.get(y.index) : 1e6 + y.index));
  };
  const reorderAccounts = () => ReorderList.open({ title: 'Reorder accounts', eyebrow: 'ACCOUNTS', mode: 'live',
    items: orderedAccounts(current).map((acc) => ({ id: String(acc.index), title: acc.name, subtitle: `${acc.address.slice(0, 10)}…${acc.address.slice(-6)} · #${acc.index}`, icon: 'person' })),
    onSave: async (order) => { try { localStorage.setItem(orderKey(current.root), JSON.stringify(order.map(Number))); } catch { } },
    onDone: () => openSettingsPage('accounts') });
  const walletRow = (w, i) => element('div', { class: `account-root${w.root === current?.root ? ' selected' : ''}`, 'data-root': w.root },
    element('button', { class: 'settings-row icon-row', 'aria-pressed': String(w.root === current?.root), onclick: () => { if (w.root !== current?.root) call('accounts_select', { root: w.root }, () => openSettingsPage('accounts')); } },
      settingsIcon('wallet'), element('span', { class: 'row-text' }, element('strong', { text: w.name }),
        element('small', { class: 'addr', text: `${w.root.slice(0, 10)}…${w.root.slice(-6)}` }),
        w.root === a.default_root ? element('small', { class: 'account-tag', text: 'DEFAULT' }) : '', w.hidden ? element('small', { class: 'account-tag muted', text: 'HIDDEN FROM MAIN LIST' }) : ''),
      w.root === current?.root ? element('span', { class: 'row-check on', 'aria-hidden': 'true' }, icon('check')) : element('span')),
    element('button', { class: 'icon-button', 'aria-label': `Options for ${w.name}`, onclick: () => rootMenu(w, i) }, icon('more')));
  const accountRow = (acc) => element('div', { class: `account-row${acc.index === current.selected ? ' selected' : ''}`, 'data-index': String(acc.index) },
    element('button', { class: 'settings-row icon-row', role: 'radio', 'aria-checked': String(acc.index === current.selected), onclick: () => { if (acc.index !== current.selected) call('accounts_select', { root: current.root, index: acc.index }, () => openSettingsPage('accounts')); } },
      settingsIcon('person'), element('span', { class: 'row-text' }, element('strong', { text: acc.name }), element('small', { class: 'addr', text: `${acc.address.slice(0, 10)}…${acc.address.slice(-6)} · #${acc.index}${acc.used ? ' · used' : ''}` })),
      acc.index === current.selected ? element('span', { class: 'row-check on', 'aria-hidden': 'true' }, icon('check')) : element('span')),
    element('button', { class: 'icon-button', 'aria-label': `Options for ${acc.name}`, onclick: () => modal(acc.name, [element('div', { class: 'modal-actions' },
      element('button', { text: 'Rename', onclick: () => ask('Rename account', 'Account name', acc.name, (name) => call('accounts_rename', { root: current.root, index: acc.index, name }, () => openSettingsPage('accounts'))) }),
      current.accounts.length > 1 ? element('button', { class: 'danger', text: 'Remove account', onclick: () => confirmRemove(`Remove ${acc.name}?`, 'This removes the account from the list, not its funds or master key. You can derive it again with its account index.', () => { $('modal').close(); call('accounts_remove', { root: current.root, index: acc.index }, () => openSettingsPage('accounts')); }) }) : '',
      element('button', { text: 'Cancel', onclick: () => openSettingsPage('accounts') }))], 'ACCOUNT') }, icon('more')));
  const scanFrom = current?.scan_next || 0;
  const separate = (mode) => { $('modal').close(); WalletSetup.open(mode, { separate: true }); };
  return ['Wallets and accounts', [settingsBack('Wallet', () => openSettingsPage('wallet')),
    settingsHero('person', 'Your wallets', 'Choose a master key, then an account derived from it. Each separate key needs its own backup. Hidden wallets remain on this device.'),
    settingsSection('Master keys', ...roots.map(walletRow),
      roots.length > 1 ? settingsRow({ icon: 'menu', title: 'Reorder wallets', id: 'accounts-reorder-wallets', onclick: reorderWallets }) : '',
      element('p', { class: 'card-note', text: 'Use each wallet’s menu to rename, set a default, hide, or remove it. Reorder wallets lets you drag them into any order. Your default opens at the next unlock. Hiding changes only this list, not on-chain visibility.' }),
      settingsRow({ icon: 'download', title: 'Import a separate master key', id: 'accounts-import', onclick: () => separate('import') }),
      settingsRow({ icon: 'key', title: 'Create a separate wallet', id: 'accounts-create', onclick: () => separate('create') })),
    current ? settingsSection(`Accounts under ${current.name}`, ...orderedAccounts(current).map(accountRow),
      current.accounts.length > 1 ? settingsRow({ icon: 'menu', title: 'Reorder accounts', id: 'accounts-reorder', onclick: reorderAccounts }) : '',
      element('button', { class: 'primary-button', id: 'accounts-add', text: 'Add account under this key', onclick: () => call('accounts_add', { root: current.root }) })) : '',
    current ? settingsSection('Find previously used accounts',
      element('p', { class: 'card-note', text: 'Scanning sends 20 derived public addresses to NOID Scan, never your key. Explorer history is incomplete: an empty result does not prove an account is unused.' }),
      element('button', { class: 'outline-button', id: 'accounts-scan', text: `Scan wallets ${scanFrom + 1}–${scanFrom + 20}`, onclick: () => call('accounts_scan', { root: current.root }, (r) => toast(r.scan_found ? `Found ${r.scan_found} used account${r.scan_found === 1 ? '' : 's'}.` : `No used accounts found in ${r.scan_range}.`)) })) : '',
    element('p', { class: 'card-note', text: 'Chat keeps the same identity whichever wallet or account you choose. Balances, sending, receiving and NFTs follow the selected account.' })]];
}
function codeLabel(kind) { return kind === 'four' ? '4-digit PIN' : kind === 'password' ? 'password' : '6-digit PIN'; }
function confirmEraseOnTen() {
  modal('Enable local-key erase?', [element('p', { text: 'The tenth confirmed wrong app PIN/password entry deletes the local wallet key on this phone. This cannot be undone without the original master key. Anyone holding the phone can deliberately trigger it. Fingerprint and phone-PIN failures do not count.' }),
    element('button', { class: 'primary-button danger-fill', id: 'erase-ten-confirm', text: 'Enable erase after 10 wrong entries', onclick: () => run(null, async () => { state = await native('app_code_erase', { enabled: true }); render(); openSettingsPage('wallet'); }) }),
    element('button', { class: 'outline-button', text: 'Keep it off', onclick: () => openSettingsPage('wallet') })], 'APP LOCK');
}
/** iOS AppCodeSetupView: an app PIN or password, separate from the phone's screen lock. Required once a wallet exists, as on iOS. */
const AppCodeSetup = (() => {
  let kind = 'six', first = '', confirming = false, required = false, view = null;
  const PREDICTABLE = ['1234', '4321', '0123', '9876', '123456', '654321', '012345', '987654', '1212', '121212'];
  const digits = () => kind === 'four' ? 4 : kind === 'six' ? 6 : 0;
  function check(code) {
    const n = digits();
    if (n) { if (!new RegExp(`^[0-9]{${n}}$`).test(code)) throw new Error(`Enter exactly ${n} digits.`); if (new Set(code).size < 2 || PREDICTABLE.includes(code)) throw new Error('Choose a less predictable PIN.'); }
    else if (code.length < 12 || code.length > 128 || !code.trim()) throw new Error('Use a password of 12–128 characters.');
  }
  function paint(errorText = '') {
    const input = element('input', { id: 'app-code-input', type: 'password', autocomplete: 'off', 'aria-label': kind === 'password' ? 'Password' : 'PIN', inputmode: digits() ? 'numeric' : 'text', maxlength: digits() ? String(digits()) : '128', placeholder: digits() ? '•'.repeat(digits()) : 'Password' });
    const go = element('button', { class: 'primary-button', id: 'app-code-continue', text: confirming ? 'Save app lock' : 'Continue', onclick: () => advance(input) });
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') advance(input); });
    const kinds = element('div', { class: 'app-code-kinds', role: 'group', 'aria-label': 'App lock type' }, ...[['six', '6-digit PIN'], ['four', '4-digit PIN'], ['password', 'Password']].map(([k, label]) =>
      element('button', { class: kind === k ? 'active' : '', 'aria-pressed': String(kind === k), text: label, onclick: () => { kind = k; first = ''; confirming = false; paint(); } })));
    view.replaceChildren(element('div', { class: 'app-code-card' },
      element('img', { class: 'app-code-logo', src: 'brand.svg', alt: '' }),
      element('h1', { text: confirming ? 'Confirm your code.' : 'Make it personal.' }),
      element('p', { class: 'app-code-sub', text: confirming ? 'Enter the same code again.' : 'Create an app lock. This is separate from your phone’s screen lock.' }),
      errorText ? element('p', { class: 'app-code-error', role: 'alert', text: errorText }) : '',
      confirming ? '' : kinds,
      !confirming && kind === 'four' ? element('p', { class: 'card-note', text: 'A 4-digit PIN is easier to guess. Six digits is recommended.' }) : '',
      !confirming && kind === 'password' ? element('p', { class: 'card-note', text: 'Use 12–128 characters. This is not your wallet master key.' }) : '',
      input, go,
      confirming ? element('button', { class: 'text-button', text: 'Start again', onclick: () => { first = ''; confirming = false; paint(); } }) : '',
      required ? '' : element('button', { class: 'text-button', id: 'app-code-cancel', text: 'Cancel', onclick: close }),
      element('p', { class: 'card-note', text: 'Local to this phone. Ten incorrect attempts require a reset with your phone’s screen lock; funds are never erased unless you turn on local key erase.' })));
    setTimeout(() => input.focus(), 50);
  }
  async function advance(input) {
    const code = input.value; input.value = '';
    try {
      check(code);
      if (!confirming) { first = code; confirming = true; paint(); return; }
      if (code !== first) throw new Error('The codes did not match. Enter the same code again to confirm.');
      const saved = first; first = '';
      const firstSetup = !state.app_code?.set;
      $('app-code-continue').disabled = true; $('app-code-continue').textContent = 'Saving…';
      if (firstSetup) offerPending(true);
      try { state = await native('app_code_set', { kind, code: saved }); }
      catch (failed) { first = saved; if (firstSetup) offerPending(false); throw failed; }
      close(); render(); toast('App lock saved.'); offerUnlock();
    } catch (error) { paint(explainError(error.message)); }
  }
  function open(isRequired) {
    required = !!isRequired; kind = 'six'; first = ''; confirming = false;
    if (!view) { view = element('section', { id: 'app-code-setup', class: 'app-code-setup', role: 'dialog', 'aria-modal': 'true', 'aria-label': 'App lock' }); document.body.append(view); }
    try { $('modal').open && $('modal').close(); } catch { }
    view.hidden = false; paint();
  }
  function close() { first = ''; confirming = false; if (view) { view.hidden = true; view.replaceChildren(); } }
  // iOS 201: right after a new or restored wallet gets its app code, offer the phone's own unlock once.
  // The offer is remembered until answered, so it still shows if the app closes in between.
  const OFFER = 'biometricOfferPending.v1';
  function offerPending(on) { try { on ? localStorage.setItem(OFFER, '1') : localStorage.removeItem(OFFER); } catch { } }
  function offerUnlock() {
    let pending = false; try { pending = localStorage.getItem(OFFER) === '1'; } catch { }
    if (!pending || !state?.app_code?.set || state.locked) return;
    const security = state.security || {};
    if (!security.available || security.enabled) { offerPending(false); return; }
    if ($('modal').open) return;
    offerPending(false); // one time: closing the sheet any way counts as Not now
    const yes = element('button', { class: 'primary-button', id: 'unlock-offer-yes', text: 'Use fingerprint or screen lock', onclick: () => run(yes, async () => {
      state = await native('app_lock', { enabled: true }); $('modal').close(); render();
    }) });
    modal('Unlock with fingerprint or screen lock?', [element('p', { text: 'Open your wallet with your fingerprint or phone screen lock instead of typing your app code. You can change this anytime in Settings → Wallet.' }),
      yes, element('button', { class: 'outline-button', id: 'unlock-offer-no', text: 'Not now', onclick: () => { offerPending(false); $('modal').close(); } })], 'APP LOCK');
  }
  /** Like iOS: once a wallet exists on this phone, an app code must be set before the wallet opens. */
  function enforce() {
    const needed = !!state?.app_code && !state.app_code.set && !!state.profile && !state.watch_only && !state.locked && !WalletSetup.active();
    if (needed && (!view || view.hidden)) open(true);
    else if (!needed && required && view && !view.hidden) close();
    else if (!needed && (!view || view.hidden)) offerUnlock();
  }
  // Leaving the app clears anything typed, like iOS.
  document.addEventListener('visibilitychange', () => { if (document.hidden && view && !view.hidden) { first = ''; confirming = false; paint(); } });
  return { open, close, enforce, active: () => !!view && !view.hidden };
})();
const AUTO_LOCK = [[0, 'Off while app is open'], [30, '30 seconds'], [60, '1 minute'], [300, '5 minutes'], [900, '15 minutes']];
function autoLockLabel(seconds) { return (AUTO_LOCK.find(([s]) => s === seconds) || [, `${seconds} seconds`])[1]; }
function settingsAutoLock() {
  const current = state.auto_lock_seconds ?? 60;
  return ['Auto-lock', [settingsBack('Wallet', () => openSettingsPage('wallet')), settingsHero('clock', 'Auto-lock', 'Choose when the wallet locks itself.'),
    settingsSection('Inactivity period', ...AUTO_LOCK.map(([seconds, title]) => settingsRow({ icon: 'clock', title, check: seconds === current, id: `auto-lock-${seconds}`,
      onclick: () => run(null, async () => { state = await native('auto_lock', { seconds }); render(); refreshSettings(); }) }))),
    element('p', { class: 'card-note', text: 'Off keeps the wallet open until you lock it or leave the app; it locks as soon as the app leaves the screen. Other periods include time away from the app.' })]];
}
/** Watch-only: change the watched address (iOS "Watch-only address"). Watching never enables sending. */
function watchAddressEditor() {
  const input = element('input', { id: 'watch-address-input', value: state.watch_address || '', autocapitalize: 'off', autocomplete: 'off', spellcheck: 'false', placeholder: 'o1…', 'aria-label': 'Watched address' });
  const save = element('button', { class: 'outline-button', text: 'Change watched address', id: 'watch-address-save', onclick: () => run(save, async () => {
    state = await native('watch_start', { address: input.value.trim() }); render(); toast('Now watching this address.'); refreshSettings();
  }) });
  return element('div', { class: 'watch-address-editor' }, input, save, element('p', { class: 'card-note', text: 'Watch-only never enables sending. Create or import a wallet to use local signing.' }));
}
/** Send screen layout (iOS SendLayout): Standard or Quick. Both use the same review and confirmation. */
function sendLayoutRow() {
  let value = 'standard'; try { value = localStorage.getItem('send.layout.v1') === 'quick' ? 'quick' : 'standard'; } catch { }
  const select = element('select', { class: 'avatar-select', id: 'send-layout', 'aria-label': 'Preferred layout' }, element('option', { value: 'standard', text: 'Standard Send' }), element('option', { value: 'quick', text: 'Quick Send' }));
  select.value = value; select.addEventListener('change', () => { try { localStorage.setItem('send.layout.v1', select.value); } catch { } });
  return element('label', { class: 'settings-line icon-row avatar-option' }, settingsIcon('send'), element('span', { class: 'row-text' }, element('strong', { text: 'Preferred layout' }), element('small', { text: 'Both layouts use the same review and confirmation.' })), select);
}
function settingsAppearance() {
  const current = state.settings?.theme || 'system';
  // Tapping a look applies it at once; the page stays open and redraws in the new look.
  const looks = SETTINGS_LOOKS.map(([value, title, detail, name]) => settingsRow({ icon: name, title, detail, check: value === current, id: `look-${value}`,
    onclick: () => run(null, async () => { if ((state.settings?.theme || 'system') === value) return; state = await native('theme', { value }); render(); refreshSettings(); }) }));
  const mining = typeof MiningUI !== 'undefined';
  // Order: header card, Main tabs, the looks, then Balances.
  return ['Appearance', [settingsBack(), settingsHero('palette', 'Appearance', 'Tap a look to see it right away.'),
    mining ? settingsSection('Main tabs', settingsRow({ icon: 'mining', title: 'Mining tab', detail: 'Show Mining in the tab bar to check and start or stop your own mining computers. This phone never mines.',
      control: settingsToggle('Mining tab', MiningUI.tabOn(), async () => { const on = !MiningUI.tabOn(); MiningUI.setTab(on); if (!on && tab === 'mining') selectTab('wallet'); }) })) : '',
    settingsSection('Look', ...looks),
    settingsSection('Balances',
      settingsRow({ icon: 'eye-off', title: 'Hide balances', detail: 'Shows dots instead of amounts on Home and on your watch.',
        control: settingsToggle('Hide balances', hideBalances, async () => { const next = !hideBalances; await native('watch_settings_set', { hide_balances: next }); hideBalances = next; }) }))]];
}

function settingsAbout() {
  return ['About', [settingsBack(), settingsHero('info', 'About', 'Version, privacy and help.'),
    settingsSection('This app', element('div', { class: 'settings-line icon-row' }, settingsIcon('cube'), element('span', { class: 'row-text' }, element('strong', { text: 'Version' })), element('span', { class: 'row-value', text: state.app_version || '—' }))),
    settingsSection('Privacy and help',
      settingsRow({ icon: 'shield', title: 'Privacy', detail: 'What stays on this phone and what the network sees', onclick: showPrivacy }),
      settingsRow({ icon: 'flag', title: 'Crash reports', detail: 'Review and share saved crash details', onclick: showCrashReports }),
      settingsRow({ icon: 'layers', title: 'Open-source licenses', onclick: () => run(null, () => native('open_link', { url: 'https://github.com/ignotusnemo/paramo1d' })) }))]];
}

/** More (Home): a header card, then icon rows. */
function showHomeMore() {
  const go = (work) => () => { $('modal').close(); work(); };
  const count = (state?.receipts || []).length;
  modal('More', [settingsHero('more', 'More', 'Your wallets, history, NFTs and contacts.'),
    settingsSection('',
      settingsRow({ icon: 'refresh', title: 'Update balance', onclick: go(() => $('sync-button').click()) }),
      settingsRow({ icon: 'history', title: 'History', detail: count ? `${count} verified payment${count === 1 ? '' : 's'}` : 'Verified payment receipts', onclick: showHistory }),
      settingsRow({ icon: 'photo', title: 'NFTs', onclick: go(() => selectTab('nfts')) }),
      state?.watch_only ? '' : settingsRow({ icon: 'users', title: 'Contacts', onclick: go(() => selectTab('direct')) }),
      state?.watch_only || !state?.accounts ? '' : settingsRow({ icon: 'wallet', title: 'Your wallets', id: 'more-wallets', onclick: () => openSettingsPage('accounts') }),
      settingsRow({ icon: 'book', title: 'Address book', id: 'more-address-book', onclick: () => AddressBook.show() })),
    settingsSection('NFTs and contacts',
      settingsRow({ icon: 'photo', title: 'NFT gallery', id: 'more-gallery', onclick: () => showNftGallery() }),
      state?.watch_only ? '' : settingsRow({ icon: 'users', title: 'A contact’s NFTs', id: 'more-contact-nfts', onclick: () => showContactNfts() }),
      state?.watch_only ? '' : settingsRow({ icon: 'person', title: 'Connected contacts', id: 'more-contacts', onclick: () => showConnectedContacts() })),
    settingsSection('',
      state?.watch_only ? '' : settingsRow({ icon: 'receive', title: 'Request NOID in a chat', onclick: go(() => { selectTab('direct'); toast('Open a private chat, then tap + to request NOID.'); }) }),
      state?.community_paused ? '' : settingsRow({ icon: 'community', title: 'Ask the community to help send', onclick: go(showBroadcastHelp) }),
      settingsRow({ icon: 'download', title: 'Import a payment receipt', onclick: go(showImportReceipt) }),
      settingsRow({ icon: 'settings', title: 'Settings', onclick: go(showSettings) }))], 'WALLET');
}

/** Conversation options: the same icon rows; destructive rows are red. */
function showChatOptions(scope = chat) {
  if (!scope) return;
  const peer = scope.startsWith('d:') ? contact(scope.slice(2)) : null;
  const rows = [];
  if (peer) rows.push(settingsRow({ icon: 'users', title: 'View contact', onclick: () => showContact(peer) }));
  const keys = [...document.querySelectorAll('#conversation-list [data-chat-key]')].map(n => n.dataset.chatKey).filter((k, i, a) => a.indexOf(k) === i);
  if (keys.length > 1 && keys.includes(scope)) rows.push(settingsRow({ icon: 'up', title: 'Move to top', onclick: () => run(null, async () => { await ChatSafety.move(keys, scope, -keys.length); $('modal').close(); }) }));
  rows.push(settingsRow({ icon: 'person', title: peer ? 'Share my selected avatar' : 'Share my selected avatar publicly', onclick: () => Avatars.share(scope), id: 'share-avatar' }));
  rows.push(settingsRow({ icon: 'layers', title: 'Manage chat storage', onclick: () => void ChatStorageUI.open() }), settingsRow({ icon: 'block', title: 'Blocked contacts', onclick: () => ChatSafety.blockedList() }));
  const danger = [settingsRow({ icon: 'trash', title: 'Clear history', danger: true, onclick: () => void ChatStorageUI.open(scope) })];
  if (peer) {
    const blocked = ChatSafety.isBlocked(peer.id);
    danger.push(settingsRow({ icon: 'block', title: blocked ? 'Unblock contact' : 'Block contact', danger: !blocked, onclick: (event) => run(event.currentTarget, async () => { blocked ? await ChatSafety.unblock(peer.id) : await ChatSafety.block(peer.id); $('modal').close(); toast(blocked ? 'Contact unblocked.' : 'Contact blocked on this device. No report was sent.'); }) }));
    danger.push(settingsRow({ icon: 'trash', title: 'Delete chat', danger: true, onclick: () => showDeleteConversation(scope) }));
  }
  modal(peer?.name || 'Community', [settingsSection('Conversation', ...rows), settingsSection('', ...danger)], 'CHAT OPTIONS');
}
