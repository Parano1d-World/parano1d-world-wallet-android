// iOS parity on Android and phone control: Offline mode, Auto-lock, wallet
// activity alerts, watch-only address change, Quick Send, Wallets and accounts, and "Phone control off" on a
// mining computer. Screenshots go to the courier-preview folder (parity-*.png).
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const { bridge, server, output } = require('./ui.test.cjs');

const ROOT = 'o1fixture-alice', SECOND = 'o1fixtureaccount2xxxxxxxxxxxxxxxxx', OTHER = 'o1separatewalletxxxxxxxxxxxxxxxxxx';
const BOB = 'o1' + 'b'.repeat(40), RECENT = 'o1' + 'c'.repeat(40);

async function extras(page) {
  await page.addInitScript(([root, second, other, bobAddress, recent]) => {
    window.testKeepHome = true;
    const base = window.Paramo1d.request.bind(window.Paramo1d);
    const s = window.testState;
    s.contacts[0].address = bobAddress;
    s.chain = { height: 2000, balance: '12.500000' };
    s.prepared_payments = [{ txid: 'ef'.repeat(32), to: recent, amount: '1.000000', fee: '0.010000', submitted: true }];
    s.backup_saved = true; s.wallet_name = 'Main wallet';
    s.alerts = { balance: false, assets: false, nft_changes: false, sends: false, messages: true, foreground: true, allowed: true };
    s.auto_lock_seconds = 60; s.offline = false; s.app_version = '1.0.0 (168)';
    s.accounts = { selected_root: root, default_root: root, wallets: [{ root, name: 'Main wallet', origin: true, selected: 0, scan_next: 0, accounts: [{ index: 0, name: 'Account 1', address: root }] }] };
    window.testRigState = 'control_off';
    window.testBook = { entries: [], categories: [] };
    const nft = (c, serial, hash) => ({ type: 'nft', collection_id: c, serial: String(serial), owner: root, metadata_hash: hash });
    window.testAssets = { installed: true, holdings: { height: 2000, items: [nft('a'.repeat(64), 3, 'm3'), nft('a'.repeat(64), 7, 'm7'), nft('b'.repeat(64), 1, 'm1')],
      collections: [{ collection_id: 'a'.repeat(64), metadata_hash: 'col-a' }, { collection_id: 'b'.repeat(64), metadata_hash: 'col-b' }] }, listings: { items: [] }, markets: { items: [] } };
    window.Paramo1d.request = (id, encoded) => {
      const request = JSON.parse(encoded); window.testCalls?.push(request);
      const reply = result => queueMicrotask(() => window.paramo1dReply(id, { ok: true, result: JSON.parse(JSON.stringify(result)) }));
      const fail = error => queueMicrotask(() => window.paramo1dReply(id, { ok: false, error }));
      const view = () => reply(s);
      if (request.op === 'asset_pending') return reply({ entries: [] });
      if (request.op === 'offline_mode') { s.offline = request.enabled; return view(); }
      if (request.op === 'auto_lock') { s.auto_lock_seconds = request.seconds; return view(); }
      if (request.op === 'alerts_set') { for (const k of Object.keys(request)) if (k !== 'op') s.alerts[k] = request[k]; return view(); }
      if (request.op === 'watch_start') { s.watch_address = request.address; s.profile = { ...s.profile, address: request.address }; return view(); }
      if (request.op === 'app_code_set') { if (!/^[0-9]{6}$/.test(request.code)) return fail('Enter exactly 6 digits.'); s.app_code = { set: true, kind: request.kind, erase_on_ten: false, attempts: 0 }; window.testCode = request.code; return view(); }
      if (request.op === 'app_code_erase') { s.app_code.erase_on_ten = request.enabled; return view(); }
      if (request.op === 'book_list') return reply(window.testBook);
      if (request.op === 'book_save') { window.testBook.entries = window.testBook.entries.filter(e => e.address !== request.address).concat([{ address: request.address, name: request.name, favorite: request.favorite, icon: request.icon, category: request.category, contact_id: request.contact_id }]); return reply(window.testBook); }
      if (request.op === 'book_remove') { window.testBook.entries = window.testBook.entries.filter(e => e.address !== request.address); return reply(window.testBook); }
      if (request.op === 'category_save') { window.testBook.categories.push({ name: request.name, icon: request.icon }); return reply(window.testBook); }
      if (request.op === 'assets') return reply(window.testAssets);
      if (request.op === 'asset_media') return reply({ name: request.hash === 'col-a' ? 'Pixel Guards' : request.hash === 'col-b' ? 'Ocean Cats' : `Item ${request.hash}`, thumbnail: 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10" fill="#3a7"/></svg>') });
      if (request.op === 'contact_holdings') return reply({ height: 1995, items: [{ type: 'nft', collection_id: 'b'.repeat(64), serial: '42', owner: request.address, metadata_hash: 'm42' }], collections: [{ collection_id: 'b'.repeat(64), metadata_hash: 'col-b' }] });
      if (request.op === 'wallet_key_reveal') return reply({ key_hex: 'ab12'.repeat(16), address: root });
      if (request.op === 'wallet_key_check') { if (request.key_hex !== 'ab12'.repeat(16)) return fail('That doesn’t match this wallet’s key. Check each group and try again.'); return view(); }
      if (request.op === 'paste_address') return reply({ address: bobAddress });
      if (request.op === 'payment_preview') return reply({ review_id: 'r1', to: request.address, amount: request.amount, fee: '0.010000', total: '3.135000' });
      if (request.op === 'accounts_add') { const w = s.accounts.wallets[0]; w.accounts.push({ index: 1, name: 'Account 2', address: second }); w.selected = 1; s.profile = { ...s.profile, address: second }; s.wallet_name = 'Main wallet · Account 2'; return view(); }
      if (request.op === 'accounts_select') { const w = s.accounts.wallets.find(x => x.root === request.root); s.accounts.selected_root = w.root; if (request.index !== undefined) w.selected = request.index; s.profile = { ...s.profile, address: w.accounts.find(a => a.index === w.selected).address }; return view(); }
      if (request.op === 'accounts_scan') { s.accounts.wallets[0].scan_next = 20; return reply({ ...s, scan_found: 0, scan_range: '1–20' }); }
      if (request.op === 'mining_list') return reply({ rigs: window.testRigs || [{ id: 'r1', label: 'Garage GPU rig', deviceName: 'Pixel' }], addresses: [] });
      if (request.op === 'mining_order') { const by = new Map(window.testRigs.map(r => [r.id, r])); window.testRigs = request.ids.map(i => by.get(i)); return reply({ rigs: window.testRigs }); }
      if (request.op === 'accounts_order') { const by = new Map(s.accounts.wallets.map(w => [w.root, w])); s.accounts.wallets = request.roots.map(r => by.get(r)); return view(); }
      if (request.op === 'mining_status') return reply(window.testRigState === 'control_off'
        ? { id: 'r1', received: Date.now(), phone_control_off: true }
        : { id: 'r1', received: Date.now(), status: { running: true, launched: { mode: 'gpu-pool', pool: 'pool.example:3333' }, telemetry: { hashrateText: '41.2 MH/s', accepted: 3 } } });
      if (request.op === 'mining_parse') return reply({ code: 'ABCDEFGHJKLM' });
      if (request.op === 'mining_pair') return fail('Phone control is off on that computer. Turn on Allow control from a phone in its mining dashboard, then show a new pairing QR.');
      if (request.op === 'mining_remove') { if (window.testRigs) window.testRigs = window.testRigs.filter(r => r.id !== request.id); return reply({ rigs: window.testRigs || [] }); }
      return base(id, encoded);
    };
  }, [ROOT, SECOND, OTHER, BOB, RECENT]);
}

async function open(browser, edition, errors, init) {
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  if (init) await page.addInitScript(init);
  page.on('pageerror', error => errors.push(`${edition}: ${error.stack || error.message}`));
  if (edition === 'store') await page.route('**/edition.js', route => route.fulfill({ contentType: 'text/javascript', body: "window.PARANO1D_EDITION = 'store';" }));
  await bridge(page); await extras(page);
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.waitForFunction(() => typeof state === 'object' && !!state?.profile);
  await page.evaluate(() => { testState.settings.theme = 'dark'; paramo1dUpdate(JSON.parse(JSON.stringify(testState))); selectTab('wallet'); });
  return page;
}
const shot = async (page, name) => { await page.waitForTimeout(300); await page.screenshot({ path: path.join(output, `parity-${name}.png`) }); };
const modalText = (page) => page.locator('#modal-content').innerText();
const calls = (page, op) => page.evaluate((o) => testCalls.filter(c => c.op === o), op);

(async () => {
  await fs.mkdir(output, { recursive: true });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ executablePath: process.env.CHROME || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox'] });
  const errors = [], checks = [];
  try {
    const page = await open(browser, 'store', errors);

    // App PIN or password (iOS AppCodeSetupView), required once a wallet exists, then the erase-after-10 setting.
    await page.evaluate(() => { testState.app_code = { set: false, kind: '', erase_on_ten: false, attempts: 0 }; paramo1dUpdate(JSON.parse(JSON.stringify(testState))); });
    await page.locator('#app-code-setup:not([hidden])').waitFor();
    assert.match(await page.locator('#app-code-setup').innerText(), /Make it personal\.[\s\S]*separate from your phone’s screen lock/);
    assert.equal(await page.locator('#app-code-cancel').count(), 0, 'required: no Cancel');
    assert.deepEqual(await page.locator('.app-code-kinds button').allTextContents(), ['6-digit PIN', '4-digit PIN', 'Password']);
    await shot(page, 'app-code-setup');
    await page.locator('#app-code-input').fill('123456'); await page.locator('#app-code-continue').click();
    assert.match(await page.locator('.app-code-error').innerText(), /less predictable/);
    await page.locator('#app-code-input').fill('583920'); await page.locator('#app-code-continue').click();
    assert.match(await page.locator('#app-code-setup h1').innerText(), /Confirm your code\./);
    await page.locator('#app-code-input').fill('583921'); await page.locator('#app-code-continue').click();
    assert.match(await page.locator('.app-code-error').innerText(), /did not match/);
    assert.match(await page.locator('#app-code-setup h1').innerText(), /Confirm your code\./);
    await page.locator('#app-code-input').fill('583920'); await page.locator('#app-code-continue').click();
    await page.locator('#app-code-setup').waitFor({ state: 'hidden' });
    assert.equal(await page.evaluate(() => window.testCode), '583920');
    await page.evaluate(() => openSettingsPage('wallet'));
    assert.match(await page.locator('#app-code-row').innerText(), /Change app 6-digit PIN/);
    await page.locator('#erase-ten-row [role="switch"]').click();
    assert.match(await modalText(page), /tenth confirmed wrong app PIN\/password entry deletes the local wallet key/);
    await shot(page, 'erase-ten-confirm');
    await page.locator('#erase-ten-confirm').click();
    await page.waitForFunction(() => state.app_code.erase_on_ten === true);
    await page.locator('#erase-ten-row').waitFor();
    await shot(page, 'app-code-settings');
    await page.locator('#close-modal').click().catch(() => {});
    checks.push('app code: required setup like iOS (6/4/password, predictable PIN refused, confirm must match), Change app PIN row, erase-after-10 with confirmation');

    // Offline mode: Settings › Network toggle; Home says so and Update balance is off.
    await page.evaluate(() => openSettingsPage('network'));
    await page.locator('#offline-row').waitFor();
    assert.match(await page.locator('#offline-row').innerText(), /Offline mode[\s\S]*Nearby Bluetooth relay keeps working/);
    await page.locator('#offline-row [role="switch"]').click();
    await page.waitForFunction(() => state.offline === true);
    await shot(page, 'offline-setting');
    await page.locator('#close-modal').click();
    assert.equal(await page.locator('#node-status-text').textContent(), 'Offline mode');
    assert.equal(await page.locator('#sync-button').isDisabled(), true);
    assert.match(await page.locator('#balance-description').textContent(), /Offline mode is on/);
    await shot(page, 'offline-home');
    await page.evaluate(() => native('offline_mode', { enabled: false }).then(s => { state = s; render(); }));
    checks.push('offline mode: Network toggle, Home chip "Offline mode", Update balance disabled');

    // Auto-lock: Wallet › Auto-lock with the iOS choices.
    await page.evaluate(() => openSettingsPage('wallet'));
    assert.match(await page.locator('#auto-lock-row').innerText(), /Auto-lock[\s\S]*1 minute/);
    assert.equal(await page.locator('#lock-now').count(), 1);
    await page.locator('#auto-lock-row').click();
    const choices = await page.locator('#modal-content [role="radio"] strong').allTextContents();
    assert.deepEqual(choices, ['Off while app is open', '30 seconds', '1 minute', '5 minutes', '15 minutes']);
    await page.locator('#auto-lock-300').click();
    await page.waitForFunction(() => state.auto_lock_seconds === 300);
    assert.equal(await page.locator('#auto-lock-300').getAttribute('aria-checked'), 'true');
    assert.match(await modalText(page), /Off keeps the wallet open until you lock it or leave the app/);
    await shot(page, 'auto-lock');
    checks.push('auto-lock: 5 iOS choices, selection saved, explanation');

    // Wallet activity alerts.
    await page.evaluate(() => openSettingsPage('notifications'));
    const alertTitles = await page.locator('#modal-content [id^="alert-"] strong').allTextContents();
    assert.deepEqual(alertTitles, ['Verified NOID balance increases', 'Verified token and NFT additions', 'NFT ownership changes to review', 'Finalized outgoing payments', 'New private messages', 'Show alerts while the app is open']);
    await page.locator('#alert-balance [role="switch"]').click();
    await page.waitForFunction(() => state.alerts.balance === true);
    assert.deepEqual((await calls(page, 'alerts_set')).at(-1), { op: 'alerts_set', balance: true });
    await shot(page, 'alerts');
    checks.push('alerts: 4 wallet activity toggles (off by default), private messages, show while open');

    // Quick Send: layout setting, people strips, Paste, percentages, same review.
    await page.evaluate(() => openSettingsPage('wallet'));
    await page.locator('#send-layout').selectOption('quick');
    assert.match(await page.locator('#modal-content').innerText(), /Both layouts use the same review and confirmation\./);
    await page.locator('#close-modal').click();
    await page.evaluate(() => showSend());
    assert.match(await page.locator('#modal-title, .modal-title, #modal h2').first().innerText().catch(() => modalText(page)), /Quick Send/);
    const text = await modalText(page);
    assert.match(text, /Recent recipients[\s\S]*Connected contacts/);
    assert.equal(await page.locator('.quick-person').count(), 2);
    await page.locator('.quick-person', { hasText: 'Bob' }).click();
    assert.match(await page.locator('#quick-chosen').innerText(), /Bob/);
    await page.locator('.quick-percent button', { hasText: '25%' }).click();
    assert.equal(await page.locator('.quick-amount').inputValue(), '3.125000');
    await shot(page, 'quick-send');
    await page.locator('.quick-paste').click();
    await page.waitForFunction(() => document.querySelector('.quick-person.active'));
    await page.locator('#modal-content .primary-button', { hasText: 'Review payment' }).click();
    await page.waitForFunction(() => /Check your payment/.test(document.querySelector('#modal')?.innerText || ''));
    const preview = (await calls(page, 'payment_preview')).at(-1);
    assert.equal(preview.address, BOB); assert.equal(preview.amount, '3.125000');
    await page.locator('#close-modal').click();
    await page.evaluate(() => localStorage.setItem('send.layout.v1', 'standard'));
    checks.push('quick send: Standard/Quick setting, recent + contacts with avatars, Paste, 25/50/75%, same review');

    // Wallets and accounts.
    await page.evaluate(() => openSettingsPage('wallet'));
    await page.locator('#accounts-row').click();
    assert.match(await modalText(page), /Your wallets[\s\S]*Import a separate master key[\s\S]*Create a separate wallet[\s\S]*Accounts under Main wallet[\s\S]*Scan wallets 1–20/i);
    await shot(page, 'accounts');
    await page.locator('#accounts-add').click();
    await page.waitForFunction(() => state.profile.address === 'o1fixtureaccount2xxxxxxxxxxxxxxxxx');
    await page.locator('.account-row[data-index="1"].selected').waitFor();
    await page.locator('.account-row[data-index="0"] .settings-row').click();
    await page.waitForFunction(() => state.profile.address === 'o1fixture-alice');
    await page.locator('#accounts-scan').click();
    await page.waitForFunction(() => /Scan wallets 21–40/.test(document.querySelector('#accounts-scan')?.textContent || ''));
    await shot(page, 'accounts-two');
    await page.locator('#accounts-import').click();
    assert.equal(await page.locator('#onboarding').isHidden(), false);
    assert.match(await page.locator('#onboarding').innerText(), /Cancel and return to Settings/);
    await shot(page, 'accounts-import');
    await page.locator('#onboarding .setup-cancel').click();
    assert.equal(await page.locator('#onboarding').isHidden(), true);
    checks.push('wallets and accounts: list, add account (selected), switch, scan next 20, separate import opens setup with Cancel back');

    // Watch-only: change the watched address.
    await page.evaluate(() => { testState.watch_only = true; testState.watch_address = 'o1watched0000000000000000000000000000'; testState.accounts = undefined; paramo1dUpdate(JSON.parse(JSON.stringify(testState))); openSettingsPage('wallet'); });
    await page.locator('#watch-address-input').fill('o1watchednew000000000000000000000000');
    await page.locator('#watch-address-save').click();
    await page.waitForFunction(() => state.watch_address === 'o1watchednew000000000000000000000000');
    assert.match(await modalText(page), /Watch-only never enables sending\./);
    await shot(page, 'watch-address');
    await page.evaluate(() => { testState.watch_only = false; delete testState.watch_address; paramo1dUpdate(JSON.parse(JSON.stringify(testState))); });
    await page.locator('#close-modal').click().catch(() => {});
    checks.push('watch-only: change watched address');

    // Phone control off.
    await page.evaluate(() => { MiningUI.setTab(true); selectTab('mining'); });
    const card = page.locator('.mining-card[data-rig="r1"]');
    await card.locator('.mining-control-off').waitFor();
    assert.equal((await card.locator('.mining-pill').innerText()).trim(), 'Phone control off');
    assert.match(await card.locator('.mining-pill').getAttribute('class'), /orange/);
    assert.equal(await card.locator('.mining-control-off').innerText(), 'Phone control is turned off on this computer. To use it from here, open its mining dashboard on that computer and turn on Allow control from a phone. Mining (if running) carries on by itself.');
    assert.equal(await card.locator('.mining-control-off strong').innerText(), 'Allow control from a phone');
    assert.deepEqual(await card.locator(':scope > button').allInnerTexts(), ['Try again', 'Unpair']);
    assert.equal(await card.locator('.mining-stats, .mining-go').count(), 0, 'no tiles, no Start/Stop');
    assert.ok(!/Offline/.test(await card.innerText()), 'not shown as offline');
    assert.equal((await calls(page, 'mining_remove')).length, 0, 'never unpaired');
    await shot(page, 'phone-control-off');
    // The 10 s refresh skips a computer in this state for 60 s.
    const before = (await calls(page, 'mining_status')).length;
    await page.waitForTimeout(10600);
    assert.equal((await calls(page, 'mining_status')).length, before, 'no refresh within 60 s');
    // Owner turns it on: Try again brings the card back.
    await page.evaluate(() => { window.testRigState = 'on'; });
    await card.locator('button', { hasText: 'Try again' }).click();
    await card.locator('.mining-stats').waitFor();
    assert.match(await card.locator('.mining-pill').innerText(), /Mining/);
    // Pairing a computer with phone control off.
    await page.evaluate(() => window.parano1dOpenMiningPair('ABCDEFGHJKLM'));
    await page.locator('#modal-content .primary-button', { hasText: 'Pair this computer' }).click();
    await page.locator('#modal-content p.danger:not([hidden])').waitFor();
    assert.equal(await page.locator('#modal-content p.danger').innerText(), 'Phone control is off on that computer. Turn on Allow control from a phone in its mining dashboard, then show a new pairing QR.');
    assert.equal(await page.locator('#modal-content p.danger strong').innerText(), 'Allow control from a phone');
    await shot(page, 'pairing-control-off');
    checks.push('phone control off: orange pill, doc text, Try again + Unpair only, not offline, no unpair, 60 s refresh, pairing message');

    // Address book, favourites, NFT gallery, a contact's NFTs, master key and backup (iOS, owner 25 Nov).
    await page.evaluate((root) => { testState.accounts = { selected_root: root, default_root: root, wallets: [{ root, name: 'Main wallet', origin: true, selected: 0, scan_next: 0, accounts: [{ index: 0, name: 'Account 1', address: root }] }] };
      paramo1dUpdate(JSON.parse(JSON.stringify(testState))); $('modal').open && $('modal').close(); MiningUI.setTab(false); selectTab('wallet'); showHomeMore(); }, ROOT);
    for (const id of ['more-wallets', 'more-address-book', 'more-gallery', 'more-contact-nfts', 'more-contacts']) assert.equal(await page.locator('#' + id).count(), 1, id);
    await shot(page, 'more');
    await page.locator('#more-address-book').click();
    await page.locator('#book-add-address').waitFor();
    assert.match(await modalText(page), /CONNECTED CONTACTS[\s\S]*Bob[\s\S]*SAVED ADDRESSES[\s\S]*Save an address from history or paste one below\./i);
    await page.locator('#book-add-address').fill(RECENT);
    await page.locator('#book-add').click();
    await page.locator('#book-name').fill('Mining pool');
    await page.locator('#book-favorite').check();
    await page.locator('#book-icon').selectOption('cpu.fill');
    assert.match(await modalText(page), /Saved locally for this wallet\. A saved name does not verify ownership/);
    await shot(page, 'save-address');
    await page.locator('#book-save').click();
    await page.locator(`.book-row[data-address="${RECENT}"]`).waitFor();
    assert.match(await page.locator(`.book-row[data-address="${RECENT}"]`).innerText(), /Mining pool/);
    assert.equal(await page.locator(`.book-row[data-address="${RECENT}"] .favorite-mark`).count(), 1);
    await shot(page, 'address-book');
    // Quick Send uses the saved name and lists Saved addresses.
    await page.evaluate(() => { localStorage.setItem('send.layout.v1', 'quick'); showSend(); });
    await page.locator('#quick-address-book').waitFor();
    assert.match(await modalText(page), /Saved addresses/);
    assert.ok((await page.locator('.quick-person small').allTextContents()).includes('Mining pool'));
    await page.locator(`.quick-person[data-address="${RECENT}"]`).first().click();
    assert.match(await page.locator('#quick-chosen').innerText(), /Mining pool/);
    await shot(page, 'quick-send-saved');
    await page.evaluate(() => localStorage.setItem('send.layout.v1', 'standard'));
    // Standard Send: Address book → Use fills the recipient.
    await page.evaluate(() => showSend());
    await page.locator('#send-address-book').click();
    await page.locator(`.book-row[data-address="${RECENT}"] .use`).click();
    await page.waitForFunction((a) => [...document.querySelectorAll('#modal-content input')].some(i => i.value === a), RECENT);
    // Favourites in Connected contacts.
    await page.evaluate(() => showConnectedContacts());
    await page.locator('.contact-line').first().waitFor();
    await page.locator('.contact-line .favorite-star').first().click();
    assert.equal(await page.locator('.contact-line .favorite-star.on').count(), 1);
    await page.locator('.contacts-filter button', { hasText: 'Favorites' }).click();
    assert.equal(await page.locator('.contact-line').count(), 1);
    await shot(page, 'contacts-favorites');
    // NFT gallery: collections, search, open one.
    await page.evaluate(() => { assetState.data = null; showNftGallery(); });
    await page.locator('.gallery-card').first().waitFor();
    assert.equal(await page.locator('.gallery-card').count(), 2);
    await page.waitForFunction(() => /Ocean Cats/.test(document.querySelector('#modal-content').innerText));
    await page.locator('#gallery-search').fill('ocean');
    assert.equal(await page.locator('.gallery-card').count(), 1);
    await page.locator('#gallery-search').fill('');
    await shot(page, 'nft-gallery');
    await page.locator('.gallery-card', { hasText: 'Pixel Guards' }).click();
    assert.equal(await page.locator('#modal-content .nft-tile').count(), 2);
    await page.locator('#gallery-search').fill('7');
    assert.equal(await page.locator('#modal-content .nft-tile').count(), 1);
    await shot(page, 'nft-gallery-collection');
    // A contact's NFTs.
    await page.evaluate(() => showContactNfts());
    await page.locator('#contact-nfts-choose').click();
    await page.locator('.contact-line .contact-open').first().click();
    await page.locator('.contact-nft-row').waitFor();
    assert.match(await modalText(page), /Holdings snapshot · block 1,995[\s\S]*NFT #42[\s\S]*Holding/);
    assert.match(await modalText(page), /This is not proof that the contact controls the address\./);
    await shot(page, 'contact-nfts');
    // Master key and backup: reveal after the screen lock, write down, verify.
    await page.evaluate(() => openSettingsPage('wallet'));
    await page.locator('#master-key-row').click();
    await page.locator('#onboarding:not([hidden])').waitFor();
    assert.match(await page.locator('#onboarding').innerText(), /Your recovery backup\./);
    await page.locator('#onboarding .setup-check').check();
    await page.locator('#setup-create').click();
    await page.locator('#onboarding', { hasText: 'Write down your key.' }).waitFor();
    await page.locator('#onboarding button', { hasText: 'Reveal master key' }).click();
    await page.locator('#onboarding', { hasText: 'Hide key now' }).waitFor();
    assert.equal((await calls(page, 'wallet_key_reveal')).length, 1);
    await shot(page, 'master-key');
    await page.locator('#onboarding button', { hasText: 'Hide key now' }).click();
    await page.locator('#onboarding button', { hasText: 'Reveal master key' }).click();
    await page.locator('#onboarding', { hasText: 'Hide key now' }).waitFor();
    assert.equal((await calls(page, 'wallet_key_reveal')).length, 2, 'hidden key is fetched again after the screen lock');
    await page.locator('#onboarding .setup-check').check();
    await page.locator('#onboarding button', { hasText: 'Next · verify backup' }).click();
    await page.locator('#onboarding input').first().fill('ab12'.repeat(16));
    await page.locator('#onboarding button', { hasText: 'Verify backup' }).click();
    await page.locator('#onboarding', { hasText: 'Your wallet is ready.' }).waitFor();
    assert.equal((await calls(page, 'wallet_create_begin')).length, 0);
    await page.locator('#onboarding button', { hasText: 'Open wallet' }).click();
    assert.equal(await page.locator('#onboarding').isHidden(), true);
    checks.push('address book (save with name, favourite, icon; Quick Send and Send use it), favourites, NFT gallery with search, a contact\'s NFTs, master key and backup');

    // iOS 200 / App Store 1.0.0 (24): mining computers, wallets and accounts reorder by drag; computers can be deleted (unpaired).
    const p2 = await open(browser, 'store', errors, () => { window.testRigs = [{ id: 'ra', label: 'Garage rig' }, { id: 'rb', label: 'Office rig' }, { id: 'rc', label: 'Cabin rig' }]; window.testRigState = 'mining'; });
    const drag = async (from, to) => {
      const a = await p2.locator(`.reorder-row[data-id="${from}"] .reorder-handle`).boundingBox(), b = await p2.locator(`.reorder-row[data-id="${to}"]`).boundingBox();
      await p2.mouse.move(a.x + a.width / 2, a.y + a.height / 2); await p2.mouse.down();
      for (let i = 1; i <= 8; i++) await p2.mouse.move(a.x + a.width / 2, a.y + a.height / 2 + (b.y + b.height / 2 - a.y - a.height / 2) * i / 8);
      await p2.mouse.up(); await p2.waitForTimeout(150);
    };
    const order = () => p2.locator('.reorder-row').evaluateAll(rows => rows.map(r => r.dataset.id));
    await p2.evaluate(() => { MiningUI.setTab(true); selectTab('mining'); });
    await p2.locator('#mining-reorder').click();
    assert.equal(await p2.locator('#modal-title').innerText(), 'Reorder computers');
    assert.deepEqual(await order(), ['ra', 'rb', 'rc']);
    await drag('rc', 'ra');
    assert.deepEqual(await order(), ['rc', 'ra', 'rb'], 'dragged to the top');
    assert.deepEqual((await p2.evaluate(() => testCalls.filter(c => c.op === 'mining_order').pop().ids)), ['rc', 'ra', 'rb'], 'saved at once');
    await p2.locator('.reorder-row[data-id="ra"] .reorder-handle').focus(); await p2.keyboard.press('ArrowDown');
    assert.deepEqual(await order(), ['rc', 'rb', 'ra'], 'arrow keys move too');
    await shot(p2, 'reorder-computers');
    await p2.locator('.reorder-row[data-id="rb"] .reorder-delete').click();
    assert.match(await p2.locator('#modal-title').innerText(), /Unpair Office rig\?/);
    await p2.locator('#modal-content .outline-button', { hasText: 'Cancel' }).click();
    assert.equal(await p2.locator('#modal-title').innerText(), 'Reorder computers', 'Cancel returns to the list');
    assert.equal((await calls(p2, 'mining_remove')).length, 0);
    await p2.locator('.reorder-row[data-id="rb"] .reorder-delete').click();
    await p2.locator('#modal-content .danger-fill').click();
    await p2.locator('#modal-title', { hasText: 'Reorder computers' }).waitFor();
    assert.deepEqual(await order(), ['rc', 'ra'], 'deleted computer unpaired and removed');
    assert.deepEqual((await calls(p2, 'mining_remove')).map(c => c.id), ['rb']);
    await p2.locator('#reorder-done').click();
    assert.deepEqual(await p2.locator('.mining-pager-dot, .mining-card').evaluateAll(n => n.length > 0), true);
    // Wallets: Save / Cancel; nothing changes until Save.
    await p2.evaluate(([root, other]) => { $('modal').open && $('modal').close(); MiningUI.setTab(false);
      testState.accounts.wallets.push({ root: other, name: 'Savings', origin: false, selected: 0, scan_next: 0, accounts: [{ index: 0, name: 'Account 1', address: other }] });
      const w = testState.accounts.wallets[0]; w.accounts.push({ index: 1, name: 'Account 2', address: 'o1' + 'd'.repeat(40) }, { index: 2, name: 'Account 3', address: 'o1' + 'e'.repeat(40) });
      paramo1dUpdate(JSON.parse(JSON.stringify(testState))); selectTab('wallet'); openSettingsPage('accounts'); }, [ROOT, OTHER]);
    await p2.locator('#accounts-reorder-wallets').click();
    assert.deepEqual(await order(), [ROOT, OTHER]);
    await drag(OTHER, ROOT);
    assert.deepEqual(await order(), [OTHER, ROOT]);
    assert.equal((await calls(p2, 'accounts_order')).length, 0, 'not saved before Save');
    await p2.locator('#reorder-cancel').click();
    assert.equal((await calls(p2, 'accounts_order')).length, 0, 'Cancel saves nothing');
    await p2.locator('#accounts-reorder-wallets').click();
    await drag(OTHER, ROOT); await shot(p2, 'reorder-wallets');
    await p2.locator('#reorder-save').click();
    await p2.locator('.account-root').first().waitFor();
    assert.deepEqual((await calls(p2, 'accounts_order')).pop().roots, [OTHER, ROOT]);
    assert.deepEqual(await p2.locator('.account-root').evaluateAll(n => n.map(x => x.dataset.root)), [OTHER, ROOT], 'page shows the new order');
    // Accounts: saved on this phone at each move; the page lists them in that order.
    await p2.locator('#accounts-reorder').click();
    assert.deepEqual(await order(), ['0', '1', '2']);
    await drag('2', '0');
    assert.deepEqual(await order(), ['2', '0', '1']);
    await shot(p2, 'reorder-accounts');
    await p2.locator('#reorder-done').click();
    await p2.locator('.account-row').first().waitFor();
    assert.deepEqual(await p2.locator('.account-row').evaluateAll(n => n.map(x => x.dataset.index)), ['2', '0', '1']);
    assert.equal(await p2.evaluate((r) => localStorage.getItem(`accounts-order:${r}`), ROOT), '[2,0,1]');
    // Light look titles (iOS 200 fix): after Cyber and World, Light's page and modal titles use Light's ink, not the old look's.
    const ink = async () => p2.evaluate(() => [getComputedStyle(document.querySelector('.settings-hero h2, .settings-hero strong, #settings-panel h1, .page-heading h1') || document.body).color, getComputedStyle(document.documentElement).getPropertyValue('--ink').trim()]);
    for (const from of ['cyber', 'world']) {
      await p2.evaluate((look) => { testState.settings.theme = look; paramo1dUpdate(JSON.parse(JSON.stringify(testState))); openSettingsPage('accounts'); }, from);
      await p2.evaluate(() => { testState.settings.theme = 'light'; paramo1dUpdate(JSON.parse(JSON.stringify(testState))); });
      await p2.waitForTimeout(100);
      const [color] = await ink();
      const rgb = color.match(/\d+/g).map(Number); const lum = (0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]) / 255;
      assert.ok(lum < 0.35, `Light title is dark ink after ${from} (got ${color})`);
    }
    await shot(p2, 'light-titles');
    await p2.close();
    checks.push('iOS (24): reorder computers by drag (saved at once, arrow keys, delete = unpair with confirmation), reorder wallets (Save/Cancel), reorder accounts (saved on this phone), Light titles after Cyber/World');

    // iOS 201: once, right after the first app code is saved, offer the phone's own unlock (fingerprint or screen lock).
    for (const answer of ['yes', 'no']) {
      const p3 = await open(browser, 'store', errors);
      await p3.evaluate(() => { localStorage.removeItem('biometricOfferPending.v1'); testState.security = { available: true, enabled: false };
        testState.app_code = { set: false, kind: '', erase_on_ten: false, attempts: 0 }; paramo1dUpdate(JSON.parse(JSON.stringify(testState))); });
      await p3.locator('#app-code-setup:not([hidden])').waitFor();
      for (let i = 0; i < 2; i++) { await p3.locator('#app-code-input').fill('583920'); await p3.locator('#app-code-continue').click(); }
      await p3.locator('#modal-title', { hasText: 'Unlock with fingerprint or screen lock?' }).waitFor();
      assert.match(await modalText(p3), /instead of typing your app code\. You can change this anytime in Settings → Wallet\./);
      if (answer === 'yes') await shot(p3, 'unlock-offer');
      await p3.locator(answer === 'yes' ? '#unlock-offer-yes' : '#unlock-offer-no').click();
      await p3.waitForFunction(() => !$('modal').open);
      assert.deepEqual([...new Set((await calls(p3, 'app_lock')).map(c => c.enabled))], answer === 'yes' ? [true] : []);
      await p3.evaluate(() => { paramo1dUpdate(JSON.parse(JSON.stringify(testState))); render(); });
      assert.equal(await p3.evaluate(() => $('modal').open), false, 'offered only once');
      // Changing the code later never offers it again.
      await p3.evaluate(() => { testState.security.enabled = false; paramo1dUpdate(JSON.parse(JSON.stringify(testState))); AppCodeSetup.open(false); });
      for (let i = 0; i < 2; i++) { await p3.locator('#app-code-input').fill('583921'); await p3.locator('#app-code-continue').click(); }
      await p3.waitForTimeout(200);
      assert.equal(await p3.evaluate(() => $('modal').open), false, 'not offered when changing the code');
      await p3.close();
    }
    checks.push('iOS (25): one-time "Unlock with fingerprint or screen lock?" after the first app code (Use / Not now), never again');

    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ ok: true, checks }, null, 1));
  } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
