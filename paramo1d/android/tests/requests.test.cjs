// Message requests, older messages and price: UI checks with the test bridge.
// - Message requests row, sender list ("Unverified"), text only, payments closed, Delete, Block and delete.
// - "Show older messages (n)" in a conversation, the older list, deleting an older message.
// - The USD price shows on Home and its source is never named; Settings shows only "Updated <time>".
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const { bridge, server, output } = require('./ui.test.cjs');

const MALLORY = 'ee'.repeat(32), BOB = '22'.repeat(32);

async function extras(page) {
  await page.addInitScript(([mallory, bob]) => {
    (() => { // installed before the app's scripts run
      const base = window.Paramo1d.request.bind(window.Paramo1d), now = Date.now();
      window.testRequests = { [mallory]: [
        { id: 'r1', created_ms: now - 60000, text: 'Hi, I saw you in the community chat.' },
        { id: 'r2', created_ms: now - 30000, hidden: 'Contains a payment or payment request. Add this person as a contact to view it.' }] };
      window.testOlder = [1, 2, 3].map(n => ({ id: 'old-' + n, chat: 'd:' + bob, sender: { id: bob, name: 'Bob' }, message: { type: 'text', text: 'Older note ' + n }, created_ms: now - 864e5 * (10 - n) }));
      const summary = () => Object.entries(window.testRequests).map(([id, list]) => ({ id, name: 'Mallory', short: id.slice(0, 6), count: list.length, last_ms: now }));
      const publish = () => { testState.requests = summary(); testState.older_counts = { ['d:' + bob]: window.testOlder.length }; };
      publish();
      window.Paramo1d.request = (id, encoded) => {
        const request = JSON.parse(encoded); window.testCalls.push(request);
        const reply = result => queueMicrotask(() => window.paramo1dReply(id, { ok: true, result: JSON.parse(JSON.stringify(result)) }));
        if (request.op === 'asset_pending') return reply({ entries: [] });
        if (request.op === 'usd_quote') return reply({ usd: '0.094388', updated_ms: Date.now() - 60000 });
        if (request.op === 'requests_open') return reply({ sender: request.sender, messages: window.testRequests[request.sender] || [] });
        if (request.op === 'requests_delete' || request.op === 'requests_block') { delete window.testRequests[request.sender]; publish(); return reply(testState); }
        if (request.op === 'chat_older') return reply({ chat: request.chat, messages: window.testOlder });
        if (request.op === 'chat_older_delete') { window.testOlder = window.testOlder.filter(m => m.id !== request.id); publish(); return reply(testState); }
        return base(id, encoded);
      };
    })();
  }, [MALLORY, BOB]);
}

(async () => {
  await fs.mkdir(output, { recursive: true });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ executablePath: process.env.CHROME || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox'] });
  const errors = [];
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    page.on('pageerror', error => errors.push(error.stack || error.message));
    await bridge(page); await extras(page);
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(() => typeof state === 'object' && !!state?.profile);
    await page.evaluate(() => paramo1dUpdate(JSON.parse(JSON.stringify(testState))));

    // Message requests (177b)
    await page.evaluate(() => selectTab('direct'));
    const row = page.locator('.requests-row');
    await row.waitFor();
    assert.match(await row.innerText(), /Message requests \(1\)/);
    assert.equal(await page.locator('.requests-row .unread').count(), 0, 'requests have no unread badge');
    await page.screenshot({ path: path.join(output, 'requests-row-mobile.png') });
    await row.click();
    await page.locator('#modal').getByText('Mallory').waitFor();
    assert.match(await page.locator('#modal-content').innerText(), /Unverified/);
    await page.locator('#modal-content .conversation-row').first().click();
    await page.locator('#modal-content').getByText('Hi, I saw you in the community chat.').waitFor();
    const detail = await page.locator('#modal-content').innerText();
    assert.match(detail, /Contains a payment or payment request/);
    assert.equal(await page.locator('#modal-content .payment-card, #modal-content [data-pay]').count(), 0, 'no payment screen from a request');
    assert.match(detail, /own contact invitation/);
    await page.screenshot({ path: path.join(output, 'requests-detail-mobile.png') });
    await page.locator('#modal-content').getByRole('button', { name: 'Delete', exact: true }).click();
    await page.waitForFunction(() => !document.querySelector('.requests-row'));
    assert.ok((await page.evaluate(() => testCalls.map(c => c.op))).includes('requests_delete'));

    // Older messages (R1)
    await page.locator('.conversation-row').filter({ hasText: 'Bob' }).click();
    const older = page.locator('#messages .history-button', { hasText: 'Show older messages (3)' });
    await older.waitFor();
    await page.screenshot({ path: path.join(output, 'older-button-mobile.png') });
    await older.click();
    await page.locator('#modal-title', { hasText: 'Older messages (3)' }).waitFor();
    await page.locator('#modal-content').getByText('Older note 1').waitFor();
    await page.locator('#modal-content .older-delete').first().click();
    await page.locator('#modal-title', { hasText: 'Older messages (2)' }).waitFor();
    await page.screenshot({ path: path.join(output, 'older-list-mobile.png') });
    await page.evaluate(() => document.getElementById('modal').close());

    // Price (iOS 193): shown on Home, never names its source
    await page.evaluate(() => selectTab('wallet'));
    await page.evaluate(() => refreshUsd(true));
    await page.waitForFunction(() => /NOID \$0\.0943/.test(document.getElementById('noid-price')?.textContent || ''));
    await page.screenshot({ path: path.join(output, 'price-home-mobile.png') });
    await page.locator('#settings-button').click();
    await page.locator('#modal-content .settings-row', { hasText: 'Network' }).first().click(); // Live USD sits under Network
    await page.locator('#modal-content').getByText('Live USD estimates').waitFor();
    const settings = await page.locator('#modal-content').innerText();
    assert.match(settings, /Updated \d/);
    for (const name of ['SafeTrade', 'CoinPaprika', 'Paprika', 'NOID Scan quote']) assert.ok(!settings.includes(name), `Settings must not name the price source (${name})`);
    const everywhere = await page.evaluate(() => document.documentElement.innerText);
    assert.ok(!/coinpaprika|safetrade/i.test(everywhere), 'no price source named anywhere');
    // An empty verified wallet is told at once instead of waiting for a payment to be prepared.
    await page.evaluate(() => { testState.chain = { height: 100, balance: '0.000000', running: false }; paramo1dUpdate(JSON.parse(JSON.stringify(testState))); });
    const before = await page.evaluate(() => testCalls.filter(c => c.op === 'payment_preview').length);
    await page.evaluate(() => reviewPayment('o1fixture-bob', '1'));
    assert.equal(await page.evaluate(() => testCalls.filter(c => c.op === 'payment_preview').length), before, 'a zero balance is refused at once');
  } finally { await browser.close(); server.close(); }
  assert.deepEqual(errors, []);
  console.log('requests, older messages and price checks passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
