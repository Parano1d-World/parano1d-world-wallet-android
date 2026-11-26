// Avatars like iOS. Your avatar (wallet icon until picked; character, pixel, symbol,
// monogram), the Create your avatar editor (presets, no NOID badge or branding switch, Anonymous mask per face,
// neck into the hood), sharing in chat with the iOS recipe format, and showing other people's shared avatars.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const { bridge, server, output } = require('./ui.test.cjs');

const PREFIX = 'Parano1d avatar v1: ';
const BOB = '22'.repeat(32), CAROL = '33'.repeat(32);
// Exactly what iOS (Swift JSONEncoder) sends for the Anonymous preset: every non-optional key present.
const IOS_ANON = { version: 1, kind: 'character', letters: '', symbol: 'person.fill', color: 'Green',
  character: { style: 'Anonymous', presentation: 'Masculine', face: 'Round', skin: 'Warm', hair: 'Crop', hairColor: 'Black', facialHair: 'None', eyes: 'Calm', accessory: 'Mask', clothing: 'Full Anonymous', accent: 'Green', background: 'Midnight', branding: true },
  metadataHash: '', collection: '', serial: '' };
const IOS_SYMBOL = { version: 1, kind: 'symbol', letters: '', symbol: 'bolt.fill', color: 'Orange', metadataHash: '', collection: '', serial: '' };

(async () => {
  await fs.mkdir(output, { recursive: true });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ executablePath: process.env.CHROME || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox'] });
  const errors = [], checks = [];
  const shot = async (page, name) => { await page.waitForTimeout(250); await page.screenshot({ path: path.join(output, `avatar198-${name}.png`) }); };
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    page.on('pageerror', error => errors.push(error.stack || error.message));
    await page.addInitScript(() => { window.testKeepHome = true; });
    await bridge(page);
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(() => typeof state === 'object' && !!state?.profile);
    await page.evaluate(() => { testState.settings.theme = 'dark'; paramo1dUpdate(JSON.parse(JSON.stringify(testState))); });

    // Default: nothing picked → wallet icon (settings card and chat), never "?".
    await page.locator('#settings-button').click();
    assert.equal(await page.locator('.settings-wallet-card .avatar.default-avatar use[href$="#wallet"]').count(), 1);
    await page.locator('#modal-content .settings-row:has(strong:text-is("Chat"))').click();
    await page.locator('#personalize-avatar').click();
    await page.locator('#modal-title', { hasText: 'Your avatar' }).waitFor();
    assert.equal(await page.locator('#avatar-preview.default-avatar').count(), 1, 'wallet icon until an avatar is picked');
    checks.push('default: wallet icon until picked, never "?"');

    // Character editor: presets, no NOID anywhere, no "NOID branding" switch.
    await page.locator('#avatar-source').selectOption('character');
    await page.locator('#avatar-edit').click();
    await page.locator('#modal-title', { hasText: 'Create your avatar' }).waitFor();
    assert.equal(await page.getByText(/NOID branding/i).count(), 0, 'no NOID branding switch');
    assert.equal(await page.locator('.avatar-preset').count(), 17, 'all 17 iOS presets');
    const ownSvg = () => page.evaluate(() => decodeURIComponent(document.querySelector('#avatar-editor .avatar img').src.split(',')[1]));
    for (const name of ['Anonymous', 'Hacker', 'Ghost']) {
      await page.locator(`.avatar-preset[aria-label="Use ${name} avatar"]`).click();
      await page.locator(`.avatar-preset.on[aria-label="Use ${name} avatar"]`).waitFor();
      const svg = await ownSvg();
      assert.ok(!/NOID/i.test(svg), `${name}: no NOID on the shirt`);
      assert.ok(svg.includes('fill="#101a21"'), `${name}: the hood opening (neck runs into the hood)`);
      await page.locator('#avatar-editor').scrollIntoViewIfNeeded();
      await shot(page, `editor-${name.toLowerCase()}`);
    }
    // Anonymous mask widens to each face shape (iOS maskScale = max(1, (faceWidth + 6) / 68)).
    await page.locator('.avatar-preset[aria-label="Use Anonymous avatar"]').click();
    const maskLeft = async () => page.evaluate(() => {
      const svg = decodeURIComponent(document.querySelector('#avatar-editor .avatar img').src.split(',')[1]);
      const m = svg.match(/<polygon points="([^"]+)" fill="#e6ebe5"/); return Number(m[1].split(' ')[0].split(',')[0]);
    });
    const widths = {};
    for (const face of ['Oval', 'Round', 'Angular']) {
      await page.locator('#avatar-face').selectOption(face);
      widths[face] = await maskLeft();
      await page.locator('#avatar-editor').scrollIntoViewIfNeeded();
      await shot(page, `anonymous-${face.toLowerCase()}`);
    }
    const expectLeft = (w) => Math.round((100 + (66 - 100) * Math.max(1, (w + 6) / 68)) * 100) / 100;
    assert.deepEqual(widths, { Oval: expectLeft(75), Round: expectLeft(83), Angular: expectLeft(71) }, 'mask fitted per face');
    // Pixel rendering.
    await page.locator('#avatar-rendering').selectOption('Pixel');
    assert.match(await ownSvg(), /viewBox="0 0 48 48"/);
    assert.ok(!/NOID/i.test(await ownSvg()));
    await page.locator('#avatar-pixelTheme').waitFor();
    await page.locator('#avatar-editor').scrollIntoViewIfNeeded(); await shot(page, 'editor-pixel');
    await page.locator('#avatar-rendering').selectOption('Vector');
    await page.locator('.avatar-preset[aria-label="Use Hacker avatar"]').click();
    checks.push('editor: 17 presets; Anonymous, Hacker, Ghost with no NOID; no branding switch; mask fitted per face; pixel rendering');

    // Your avatar now shows on the settings wallet card and in chat.
    await page.locator('#close-modal').click();
    assert.equal(await page.locator('#my-avatar.art-avatar img').count(), 1);
    await page.locator('#settings-button').click();
    assert.equal(await page.locator('.settings-wallet-card .avatar.art-avatar img').count(), 1);
    await shot(page, 'settings-card');
    await page.locator('#close-modal').click();
    checks.push('your picked avatar shows on the wallet card and in chat');

    // Share in a private chat: the iOS recipe, as one signed text message.
    await page.evaluate(() => selectTab('direct'));
    await page.locator('.conversation-row').filter({ hasText: 'Bob' }).click();
    await page.locator('#chat-options-button').click();
    await page.locator('#share-avatar').click();
    await page.getByRole('button', { name: 'Share encrypted avatar' }).click();
    const sent = await page.evaluate(() => testCalls.filter(c => c.op === 'send').at(-1));
    assert.equal(sent.chat, `d:${BOB}`);
    assert.ok(sent.message.text.startsWith(PREFIX));
    const recipe = JSON.parse(sent.message.text.slice(PREFIX.length));
    assert.deepEqual(Object.keys(recipe), ['version', 'kind', 'letters', 'symbol', 'color', 'character', 'metadataHash', 'collection', 'serial'], 'same fields and order as iOS');
    assert.equal(recipe.kind, 'character'); assert.equal(recipe.character.style, 'Hooded'); assert.equal(recipe.character.branding, true, 'branding kept in the recipe so older apps still load it');
    assert.match(await page.locator('#messages').innerText(), /Shared a selected avatar\./);
    assert.ok(!(await page.locator('#messages').innerText()).includes(PREFIX), 'the recipe text is never shown');
    checks.push('share: iOS recipe (same keys and order, branding kept), shown as "Shared a selected avatar."');

    // Receive: an avatar shared from iOS shows for that person; invalid or older ones are ignored.
    await page.evaluate(([bob, carol, a, s, prefix]) => {
      const msg = (id, chat, sender, text, at) => ({ id, chat, sender, message: { type: 'text', text }, created_ms: at, status: 'received' });
      const bobP = testState.contacts.find(c => c.id === bob);
      const now = Date.now();
      testState.messages.push(msg('av-1', `d:${bob}`, bobP, prefix + JSON.stringify(a), now - 5000));
      testState.messages.push(msg('av-0', `d:${bob}`, bobP, prefix + JSON.stringify(s), now - 9000)); // older: ignored
      testState.messages.push(msg('av-bad', `d:${bob}`, bobP, prefix + JSON.stringify({ ...a, character: { ...a.character, face: 'Dragon' } }), now - 1000)); // invalid: ignored
      testState.messages.push(msg('av-pub', 'g:parano1d', { id: carol, name: 'Carol' }, prefix + JSON.stringify(s), now - 3000));
      paramo1dUpdate(JSON.parse(JSON.stringify(testState)));
    }, [BOB, CAROL, IOS_ANON, IOS_SYMBOL, PREFIX]);
    await page.waitForFunction(() => document.querySelector('#chat-avatar')?.classList.contains('art-avatar'));
    const bobSvg = await page.evaluate(() => decodeURIComponent(document.querySelector('#chat-avatar img').src.split(',')[1]));
    assert.ok(bobSvg.includes('fill="#e6ebe5"'), "Bob's iOS Anonymous avatar shows (mask drawn)");
    await shot(page, 'private-chat-received');
    await page.locator('#back-button').click();
    assert.equal(await page.locator('.conversation-row').filter({ hasText: 'Bob' }).locator('.avatar.art-avatar').count(), 1, 'Bob in the chat list');
    await shot(page, 'chat-list');
    // Community: Carol's public avatar; Bob's private one never shows in public.
    await page.evaluate(() => selectTab('groups'));
    await page.locator(`#messages .message-avatar.symbol-avatar`).first().waitFor();
    assert.equal(await page.locator('#messages .message-avatar.symbol-avatar use[href$="#sym-bolt"]').count() >= 1, true, "Carol's public symbol avatar");
    await shot(page, 'community');
    assert.equal(await page.evaluate((bob) => { const n = document.createElement('span'); Avatars.peer(n, bob, 'Bob', { publicOnly: true }); return n.classList.contains('art-avatar'); }, BOB), false, 'a private selection stays private');
    // A contact with no shared avatar and no name: a person icon, never "?".
    assert.equal(await page.evaluate(() => { const n = document.createElement('span'); Avatars.peer(n, '44'.repeat(32), ''); return n.classList.contains('person-avatar') && !n.textContent.includes('?'); }), true);
    checks.push('receive: iOS avatars show for that person (private and public kept apart); invalid and older ignored; person icon when no name');

    // Symbol and monogram modes; monogram with no letters falls back to initials, then the wallet icon.
    await page.evaluate(() => { selectTab('wallet'); Avatars.showSettings(); });
    await page.locator('#avatar-source').selectOption('symbol');
    await page.locator('.avatar-symbol[aria-label="Shield"]').click();
    await page.locator('#avatar-color').selectOption('Purple');
    assert.equal(await page.locator('#avatar-preview.symbol-avatar use[href$="#sym-shield"]').count(), 1);
    await shot(page, 'symbol');
    await page.locator('#avatar-source').selectOption('monogram');
    await page.locator('#avatar-monogram').fill('ab!9z');
    assert.equal(await page.locator('#avatar-monogram').inputValue(), 'AB9');
    assert.equal((await page.locator('#avatar-preview').textContent()).trim(), 'AB9');
    await page.locator('#avatar-monogram').fill('');
    assert.equal((await page.locator('#avatar-preview').textContent()).trim(), 'A', 'empty monogram: your initials');
    checks.push('symbol and monogram modes (letters cleaned to 3), with fallbacks');

    // Store edition has no "Owned NFT" source (as the App Store edition).
    assert.equal(await page.locator('#avatar-source option[value="nft"]').count(), 1, 'internal build offers Owned NFT');
    await page.close();
    const store = await browser.newPage({ viewport: { width: 390, height: 844 } });
    store.on('pageerror', error => errors.push(error.stack || error.message));
    await store.route('**/edition.js', route => route.fulfill({ contentType: 'text/javascript', body: "window.PARANO1D_EDITION = 'store';" }));
    await store.addInitScript(() => { window.testKeepHome = true; });
    await bridge(store);
    await store.goto(`http://127.0.0.1:${server.address().port}/`);
    await store.waitForFunction(() => typeof state === 'object' && !!state?.profile);
    await store.evaluate(() => Avatars.showSettings());
    assert.equal(await store.locator('#avatar-source option[value="nft"]').count(), 0, 'no NFT avatar in the store edition');
    await store.close();
    checks.push('NFT avatars only in the internal build');

    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ ok: true, checks }, null, 1));
  } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
