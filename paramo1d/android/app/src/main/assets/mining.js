'use strict';
// Mining (iOS 185 MiningView). This phone never mines: it pairs with the owner's rig through parano1dworld.com,
// shows what the rig reports and sends signed Start / Stop. Pairings and keys live only on this phone (Mining.java).
const MiningUI = (() => {
  const REFRESH_MS = 10000;
  let rigs = [], addresses = [], loaded = false, timer = null, selected = null, scanId = null, shown = false, sending = 0;
  const results = new Map();           // id -> {status, received, error, revoked}
  const refreshing = new Set();
  let tiles = false; try { tiles = localStorage.getItem('mining-layout') === 'tiles'; } catch { }

  // ---- tab bar setting (iOS Settings -> Main tabs) ----
  function tabOn() { try { return localStorage.getItem('mining-tab') === '1'; } catch { return false; } }
  function setTab(on) { try { localStorage.setItem('mining-tab', on ? '1' : '0'); } catch { } applyTab(); }
  function applyTab() { document.querySelectorAll('[data-tab="mining"]').forEach(b => { b.hidden = !tabOn(); }); }

  // ---- data ----
  async function load() {
    const r = await native('mining_list'); rigs = r.rigs || []; addresses = (r.addresses || []).filter(Boolean); loaded = true;
    if (!rigs.some(x => x.id === selected)) selected = rigs[0]?.id || null;
  }
  function accept(r) { if (r?.id) results.set(r.id, r); }
  async function refresh(id) {
    if (refreshing.has(id)) return; refreshing.add(id);
    try { accept(await native('mining_status', { id })); }
    catch (error) { results.set(id, { id, received: Date.now(), error: explainError(error.message) }); }
    finally { refreshing.delete(id); if (visible() && !sending) paint(); }
  }
  // While a computer has phone control off, refresh it at most once a minute (never unpair it).
  const SLOW_MS = 60000;
  async function refreshAll() { await Promise.all(rigs.filter(r => { const last = results.get(r.id); return !last?.phone_control_off || Date.now() - (last.received || 0) >= SLOW_MS; }).map(r => refresh(r.id))); }
  function visible() { return !$('mining-panel').hidden && !document.hidden; }
  function schedule() {
    clearInterval(timer); timer = null;
    if (visible() && rigs.length) timer = setInterval(() => { if (visible()) refreshAll(); else schedule(); }, REFRESH_MS);
  }

  // ---- status helpers (same reading as iOS RigStatus) ----
  const ADDRESS = /^o1[0-9a-z]{20,126}$/;
  function status(r) {
    const s = r?.status; if (!s) return null;
    const settings = s.settings || {}, telemetry = s.telemetry || {}, launched = s.launched || {};
    const num = v => typeof v === 'number' && isFinite(v) ? v : null;
    const payout = [s.payout, settings.payout, settings.payoutAddress, settings.wallet, settings.address, launched.wallet].find(v => typeof v === 'string' && ADDRESS.test(v)) || null;
    const started = typeof s.startedAt === 'string' ? Date.parse(s.startedAt) : NaN;
    return { running: s.running === true, mode: launched.mode || s.preferredMode || null, pool: launched.pool || settings.pool || null,
      worker: typeof settings.worker === 'string' ? settings.worker : null, hashrate: typeof telemetry.hashrateText === 'string' ? telemetry.hashrateText : null,
      accepted: num(telemetry.accepted), rejected: num(telemetry.rejected), startedAt: isNaN(started) ? null : started, payout,
      stopState: typeof s.stop?.state === 'string' ? s.stop.state : null };
  }
  function state(rig) {
    const r = results.get(rig.id);
    if (r?.revoked) return ['Not paired', 'orange'];
    if (r?.phone_control_off) return ['Phone control off', 'orange'];
    if (r?.error) return ['Offline', 'orange'];
    const s = status(r); if (!s) return ['Checking…', 'muted'];
    return s.running ? ['Mining', 'mint'] : ['Idle', 'muted'];
  }
  const shortAddress = a => a.length > 18 ? `${a.slice(0, 8)}…${a.slice(-8)}` : a;
  function since(ms) {
    const m = Math.max(0, Math.floor((Date.now() - ms) / 60000));
    if (m < 1) return 'under a minute'; if (m < 60) return `${m} min`;
    const h = Math.floor(m / 60); if (h < 48) return `${h} h ${m % 60} min`;
    return `${Math.floor(h / 24)} days`;
  }
  const clock = ms => new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  const dot = tone => element('span', { class: `mining-dot ${tone}`, 'aria-hidden': 'true' });

  // ---- pairing ----
  function scan() {
    if (scanId) return;
    scanId = String(Date.now() % 1e12);
    native('scan_qr', { kind: 'mining', scan_id: scanId }).catch(error => { scanId = null; toast(explainError(error.message)); });
  }
  const original = window.paramo1dScanResult;
  window.paramo1dScanResult = result => {
    if (!scanId || result?.scan_id !== scanId) return original?.(result);
    scanId = null;
    if (result.cancelled) return;
    if (result.error) { toast(explainError(result.error)); return; }
    if (result.kind === 'mining' && typeof result.text === 'string') confirmPair(result.text);
  };
  function typeCode() {
    const code = field('Pairing code', { placeholder: 'XXXX-XXXX-XXXX', autocomplete: 'off' });
    code.input.autocapitalize = 'characters'; code.input.spellcheck = false;
    const go = element('button', { class: 'primary-button', text: 'Continue', onclick: () => run(go, async () => {
      const r = await native('mining_parse', { text: code.input.value }); $('modal').close(); confirmPair(r.code);
    }) });
    modal('Enter pairing code', [element('p', { text: 'Use the 12-character code shown under the rig’s pairing QR.' }), code.label, go,
      element('button', { class: 'outline-button', text: 'Cancel', onclick: () => $('modal').close() })], 'MINING');
  }
  function confirmPair(code) {
    const shown = code.length === 12 ? `${code.slice(0, 4)}-${code.slice(4, 8)}-${code.slice(8)}` : code;
    const name = field('Name or location (for example Home rig)', { value: rigs.length ? `Mining computer ${rigs.length + 1}` : 'Mining computer' });
    name.input.maxLength = 40;
    const error = element('p', { class: 'danger', hidden: true });
    const pair = element('button', { class: 'primary-button', text: 'Pair this computer', onclick: async () => {
      pair.disabled = true; pair.textContent = 'Pairing…'; error.hidden = true;
      try {
        const r = await native('mining_pair', { code, label: name.input.value.trim() || 'Mining computer' });
        rigs = r.rigs || rigs; selected = r.id; accept(r.status); $('modal').close();
        toast('Paired. This phone can now see and start or stop that computer.');
        if (tab !== 'mining') selectTab('mining'); else paint();
      } catch (failed) {
        const text = explainError(failed.message), mark = 'Allow control from a phone';
        if (text.startsWith('Phone control is off on that computer') && text.includes(mark)) { const [before, after] = text.split(mark); error.replaceChildren(before, element('strong', { text: mark }), after); }
        else error.textContent = text;
        error.hidden = false; pair.disabled = false; pair.textContent = 'Pair this computer'; }
    } });
    modal('Pair mining computer', [element('div', { class: 'review-row' }, element('span', { text: 'Pairing code' }), element('span', { class: 'mining-code', text: shown })),
      name.label,
      element('p', { class: 'card-note', text: 'Only pair a computer you own. This phone gets its own key, so you can revoke it on the rig at any time. The code works once and expires after 10 minutes.' }),
      error, pair, element('button', { class: 'outline-button', text: 'Cancel', onclick: () => $('modal').close() })], 'MINING');
  }
  function addMenu() {
    modal('Add a mining computer', [element('div', { class: 'modal-actions' },
      element('button', { text: 'Scan pairing QR', onclick: () => { $('modal').close(); scan(); } }),
      element('button', { text: 'Enter code', onclick: () => { $('modal').close(); typeCode(); } }),
      element('button', { text: 'Set up a mining computer ↗', onclick: () => { $('modal').close(); run(null, () => native('open_link', { url: 'https://parano1dworld.com/#Mining' })); } }))], 'MINING');
  }
  /** A pairing link opened from the camera or another app (held by Android until the app is unlocked). */
  window.parano1dOpenMiningPair = code => { if (!/^[A-Z0-9]{12}$/.test(String(code))) return; if (tab !== 'mining') selectTab('mining'); confirmPair(code); };

  // ---- actions ----
  async function command(rig, cmd, button, note) {
    button.disabled = true; button.textContent = 'Sending…'; note.hidden = true; sending++;
    try {
      const r = await native('mining_command', { id: rig.id, cmd });
      if (r.error) { note.textContent = r.error; note.hidden = false; if (r.revoked) results.set(rig.id, { ...(results.get(rig.id) || {}), revoked: true }); }
      else accept(r);
    } catch (error) { note.textContent = explainError(error.message); note.hidden = false; }
    finally { sending--; }
    paint(note.hidden ? null : { id: rig.id, text: note.textContent });
  }
  function options(rig) {
    modal(rig.label, [element('div', { class: 'modal-actions' },
      element('button', { text: 'Refresh', onclick: () => { $('modal').close(); refresh(rig.id); } }),
      element('button', { text: 'Rename', onclick: () => rename(rig) }),
      rigs.length > 1 ? element('button', { text: 'Reorder computers', onclick: reorder }) : '',
      element('button', { class: 'danger', text: 'Unpair this phone', onclick: () => unpair(rig) }))], 'MINING COMPUTER');
  }
  /** iOS 200 "Reorder computers": drag into any order (saved on this phone at once); − unpairs, after the same confirmation. */
  function reorder() {
    const items = () => rigs.map(r => ({ id: r.id, title: r.label, icon: 'computer' }));
    ReorderList.open({ title: 'Reorder computers', eyebrow: 'MINING', mode: 'live', items: items(),
      note: 'Drag a computer to move it. Delete unpairs it from this phone.',
      onSave: async (ids) => { const r = await native('mining_order', { ids }); rigs = r.rigs || rigs; paint(); },
      onDelete: (item) => { const rig = rigs.find(r => r.id === item.id); if (rig) unpair(rig, () => rigs.length > 1 ? reorder() : $('modal').close()); },
      onDone: () => { $('modal').close(); paint(); } });
  }
  function rename(rig) {
    const name = field('Name or location', { value: rig.label }); name.input.maxLength = 40;
    const save = element('button', { class: 'primary-button', text: 'Save', onclick: () => run(save, async () => {
      const r = await native('mining_rename', { id: rig.id, label: name.input.value.trim() || rig.label }); rigs = r.rigs || rigs; $('modal').close(); paint();
    }) });
    modal('Rename', [name.label, save, element('button', { class: 'outline-button', text: 'Cancel', onclick: () => $('modal').close() })], 'MINING COMPUTER');
  }
  function unpair(rig, after) {
    const go = element('button', { class: 'primary-button danger-fill', text: 'Unpair this phone', onclick: () => run(go, async () => {
      const r = await native('mining_remove', { id: rig.id }); rigs = r.rigs || []; results.delete(rig.id); if (!rigs.some(x => x.id === selected)) selected = rigs[0]?.id || null;
      $('modal').close(); paint(); toast('Unpaired. Also revoke this phone in the rig’s dashboard.'); after?.();
    }) });
    modal(`Unpair ${rig.label}?`, [element('p', { text: 'This removes the pairing and its key from this phone. Also revoke this phone in the rig’s dashboard. Mining on the computer is not affected.' }), go,
      element('button', { class: 'outline-button', text: 'Cancel', onclick: () => (after ? reorder() : $('modal').close()) })], 'MINING COMPUTER');
  }

  // ---- views ----
  function card(rig, issue) {
    const r = results.get(rig.id) || {}, s = status(r), [label, tone] = state(rig);
    // A 2×2 grid of stat tiles; Pool and Worker only when the computer GPU-mines
    // (CPU mining is solo with its own node, so it has no pool); "Your payout address" for one of your wallets.
    const rows = [], tiles = [];
    if (r.phone_control_off) return controlOffCard(rig, label, tone);
    const row = (title, value, extra) => rows.push(element('div', { class: 'mining-row' }, element('small', { text: title }), element('span', {}, element('span', { class: 'mining-value', text: value }), extra || element('span'))));
    const tile = (title, value, extra) => tiles.push(element('div', { class: 'mining-stat' }, element('small', { text: title }), element('strong', { class: 'mining-value', text: value }), extra || ''));
    if (s) {
      const gpu = /gpu/i.test(String(s.mode || ''));
      tile('Hashrate', s.running ? (s.hashrate || 'waiting…') : '—');
      tile('Shares', s.running || (s.accepted || 0) > 0 ? `${s.accepted || 0} accepted` : '—', (s.rejected || 0) > 0 ? element('small', { class: 'mining-sub', text: `· ${s.rejected} rejected` }) : '');
      tile('Running', s.running && s.startedAt ? since(s.startedAt) : '—');
      tile('Mode', s.mode ? (gpu ? 'GPU' : 'CPU') : '—');
      if (gpu && s.pool) row('Pool', s.pool);
      if (gpu && s.worker) row('Worker', s.worker);
      const mine = s.payout && addresses.includes(s.payout);
      row(mine ? 'Your payout address' : 'Payout address', s.payout ? shortAddress(s.payout) : 'Not reported', s.payout && !mine ? element('small', { class: 'mining-payout orange', text: '⚠ Not a wallet in this app' }) : null);
    }
    const note = element('p', { class: 'mining-note orange', hidden: !(issue && issue.id === rig.id) , text: issue?.id === rig.id ? issue.text : '' });
    const parts = [element('div', { class: 'mining-card-head' }, element('span', { class: 'mining-computer', 'aria-hidden': 'true' }, icon('computer')), element('strong', { text: rig.label }),
      element('span', { class: `mining-pill ${tone}` }, tone === 'mint' ? element('span', { class: 'mining-pill-dot', 'aria-hidden': 'true', text: '●' }) : '', label),
      element('button', { class: 'icon-button', 'aria-label': `Options for ${rig.label}`, onclick: () => options(rig) }, icon('more')))];
    if (r.error && !r.revoked) parts.push(element('p', { class: 'mining-note orange', text: r.error }));
    if (tiles.length) parts.push(element('div', { class: 'mining-stats' }, ...tiles));
    if (rows.length) parts.push(element('div', { class: 'mining-grid' }, ...rows));
    if (s?.stopState) parts.push(element('p', { class: `mining-note ${s.stopState === 'timeout' ? 'orange' : ''}`, text: s.stopState === 'confirmed' ? 'Stopped (confirmed: the miner exited).' : s.stopState === 'timeout' ? 'Stop not confirmed after 60 s. Check the rig.' : 'Stopping… waiting for the miner to exit.' }));
    if (s && r.received) parts.push(element('small', { class: 'mining-updated', text: `Updated ${clock(r.received)} · reported by the computer` }));
    parts.push(note);
    if (r.revoked) parts.push(element('p', { class: 'card-note', text: 'This phone is no longer paired with that computer. Unpair it here, then scan a new pairing QR.' }));
    else if (s && !r.error) {
      const go = element('button', { class: 'primary-button mining-go', text: s.running ? '■ Stop mining' : '▶ Start mining' });
      go.onclick = () => command(rig, s.running ? 'stop' : 'start', go, note);
      parts.push(go);
    }
    parts.push(element('small', { class: 'mining-paired', text: `Paired as ${rig.deviceName || 'Android phone'}${rig.fingerprint ? ` · key ${rig.fingerprint}` : ''}` }));
    return element('section', { class: 'home-card mining-card', 'data-rig': rig.id }, ...parts);
  }
  /** HTTP 423 phone-control-off. The pairing is still valid; only Try again and Unpair. */
  function controlOffCard(rig, label, tone) {
    const retry = element('button', { class: 'primary-button', text: 'Try again', onclick: async () => { retry.disabled = true; retry.textContent = 'Checking…'; await refresh(rig.id); } });
    return element('section', { class: 'home-card mining-card', 'data-rig': rig.id },
      element('div', { class: 'mining-card-head' }, element('span', { class: 'mining-computer', 'aria-hidden': 'true' }, icon('computer')), element('strong', { text: rig.label }),
        element('span', { class: `mining-pill ${tone}` }, label),
        element('button', { class: 'icon-button', 'aria-label': `Options for ${rig.label}`, onclick: () => options(rig) }, icon('more'))),
      element('p', { class: 'mining-note mining-control-off' }, 'Phone control is turned off on this computer. To use it from here, open its mining dashboard on that computer and turn on ',
        element('strong', { text: 'Allow control from a phone' }), '. Mining (if running) carries on by itself.'),
      retry,
      element('button', { class: 'outline-button danger', text: 'Unpair', onclick: () => unpair(rig) }),
      element('small', { class: 'mining-paired', text: `Paired as ${rig.deviceName || 'Android phone'}${rig.fingerprint ? ` · key ${rig.fingerprint}` : ''}` }));
  }
  function summary() {
    const mining = rigs.filter(x => status(results.get(x.id))?.running && !results.get(x.id)?.error).length;
    const offline = rigs.filter(x => results.get(x.id)?.error || results.get(x.id)?.revoked).length;
    const seg = (value, text) => element('button', { class: tiles === value ? 'active' : '', 'aria-pressed': String(tiles === value), text, onclick: () => { tiles = value; try { localStorage.setItem('mining-layout', value ? 'tiles' : 'cards'); } catch { } paint(); } });
    return element('div', { class: 'mining-summary' },
      element('div', { class: 'mining-count' }, dot(mining ? 'mint' : 'muted'), element('strong', { text: `${mining} of ${rigs.length} mining` }), offline ? element('span', { class: 'orange', text: `· ${offline} offline` }) : element('span')),
      element('div', { class: 'segmented', role: 'group', 'aria-label': 'Layout' }, seg(false, 'Cards'), seg(true, 'Tiles')));
  }
  function pager(issue) {
    const chips = element('div', { class: 'mining-chips' }, ...rigs.map(rig => {
      const [, tone] = state(rig);
      return element('button', { class: `mining-chip${rig.id === selected ? ' active' : ''}`, 'aria-pressed': String(rig.id === selected), onclick: () => { selected = rig.id; show(); } }, dot(tone), element('span', { text: rig.label }));
    }));
    const track = element('div', { class: 'mining-pager' }, ...rigs.map(rig => card(rig, issue)));
    const dots = element('div', { class: 'mining-pages', 'aria-hidden': 'true' }, ...rigs.map(rig => element('i', { class: rig.id === selected ? 'on' : '' })));
    function show() {
      const i = rigs.findIndex(x => x.id === selected);
      track.children[i]?.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'start' });
      [...chips.children].forEach((c, n) => { c.classList.toggle('active', n === i); c.setAttribute('aria-pressed', String(n === i)); });
      [...dots.children].forEach((d, n) => d.classList.toggle('on', n === i));
    }
    let settle; track.addEventListener('scroll', () => { clearTimeout(settle); settle = setTimeout(() => {
      const i = Math.round(track.scrollLeft / Math.max(1, track.clientWidth)); if (rigs[i] && rigs[i].id !== selected) { selected = rigs[i].id; show(); }
    }, 90); });
    requestAnimationFrame(() => { const i = rigs.findIndex(x => x.id === selected); if (i > 0) track.scrollLeft = i * track.clientWidth; });
    return [chips, track, dots];
  }
  function tileGrid() {
    return element('div', { class: 'mining-tiles' }, ...rigs.map(rig => {
      const r = results.get(rig.id), s = status(r), [, tone] = state(rig);
      const big = r?.phone_control_off ? 'Phone control off' : r?.error ? 'Offline' : r?.revoked ? 'Not paired' : s?.running ? (s.hashrate || 'Mining') : s ? 'Idle' : 'Checking…';
      const open = () => { selected = rig.id; tiles = false; try { localStorage.setItem('mining-layout', 'cards'); } catch { } paint(); };
      const tile = element('button', { class: `home-card mining-tile ${tone}`, 'aria-label': `${rig.label}, ${r?.phone_control_off ? 'phone control off' : r?.error ? 'offline' : s?.running ? `mining ${s.hashrate || ''}` : 'idle'}`, onclick: open },
        element('span', { class: 'mining-tile-name' }, dot(tone), element('strong', { text: rig.label })),
        element('span', { class: `mining-tile-big ${r?.error || r?.revoked || r?.phone_control_off ? 'orange' : ''}`, text: big }),
        element('small', { text: s ? `${s.accepted || 0} shares · ${new Date(r.received).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}` : '—' }));
      longPress(tile, () => tileMenu(rig, open));
      return tile;
    }));
  }
  /** Press and hold (or right-click) opens a menu; the tap that ends the hold doesn't also fire. */
  function longPress(target, action) {
    let hold = null, held = false;
    const cancel = () => { clearTimeout(hold); hold = null; };
    target.addEventListener('touchstart', () => { held = false; hold = setTimeout(() => { held = true; hold = null; action(); }, 550); }, { passive: true });
    ['touchend', 'touchmove', 'touchcancel'].forEach(t => target.addEventListener(t, cancel, { passive: true }));
    target.addEventListener('click', e => { if (held) { held = false; e.preventDefault(); e.stopImmediatePropagation(); } }, true);
    target.addEventListener('contextmenu', e => { e.preventDefault(); cancel(); held = false; action(); });
  }
  function tileMenu(rig, open) {
    modal(rig.label, [element('div', { class: 'modal-actions' },
      element('button', { text: 'Open', onclick: () => { $('modal').close(); open(); } }),
      element('button', { text: 'Refresh', onclick: () => { $('modal').close(); refresh(rig.id); } }),
      rigs.length > 1 ? element('button', { text: 'Reorder computers', onclick: reorder }) : '')], 'MINING COMPUTER');
  }
  function empty() {
    return element('section', { class: 'home-card mining-empty' }, element('strong', { text: 'Not connected' }),
      element('p', { class: 'card-note', text: 'On your mining computer, open the Parano1d Mining dashboard and press Show pairing QR. Then scan it here. You can pair more than one computer, for example at different locations.' }),
      element('button', { class: 'primary-button', onclick: scan }, element('span', {}, icon('scan')), 'Scan pairing QR'),
      element('button', { class: 'text-button', text: 'Enter code instead', onclick: typeCode }),
      element('button', { class: 'text-button', text: 'Set up a mining computer ↗', onclick: () => run(null, () => native('open_link', { url: 'https://parano1dworld.com/#Mining' })) }));
  }
  function paint(issue) {
    const panel = $('mining-view'); if (!panel) return;
    $('mining-subtitle').textContent = `Watch and control your own computers${loaded && rigs.length ? ` · ${rigs.length} paired` : ''}`;
    const nodes = [];
    if (loaded && !rigs.length) nodes.push(empty());
    else if (rigs.length === 1) nodes.push(card(rigs[0], issue));
    else if (rigs.length > 1) { nodes.push(summary()); if (tiles) nodes.push(tileGrid()); else nodes.push(...pager(issue)); }
    if (rigs.length > 1) nodes.push(element('button', { class: 'text-button mining-reorder', id: 'mining-reorder', onclick: reorder }, 'Reorder computers'));
    if (rigs.length) nodes.push(element('button', { class: 'settings-row icon-row mining-add-row', onclick: scan }, settingsIcon('plus'),
      element('span', { class: 'row-text' }, element('strong', { text: 'Add another computer' }), element('small', { text: 'Scan the pairing QR on your mining computer' })), element('span', { class: 'settings-chevron', 'aria-hidden': 'true', text: '›' })));
    nodes.push(element('p', { class: 'card-note', text: 'Mining runs on your own computer. This phone never mines; it shows what your rig reports and can start or stop it. The payout address, pool and miner programs can only be changed on the rig itself.' }),
      element('p', { class: 'card-note', text: 'Figures are reported by your rig’s miner, not verified by the chain. Hashrate is not earnings: payouts arrive as NOID in your wallet.' }));
    panel.replaceChildren(...nodes);
  }
  /** Called by render() whenever the Mining panel is showing. */
  async function render() {
    if (!shown) { shown = true; paint(); if (loaded) refreshAll(); }
    if (!render.busy && !loaded) { render.busy = true; try { await load(); paint(); await refreshAll(); } catch (error) { toast(explainError(error.message)); } finally { render.busy = false; } }
    schedule();
  }
  function hidden() { shown = false; schedule(); }
  document.addEventListener('visibilitychange', () => { if (visible()) { refreshAll(); } schedule(); });
  // The + button: tap to scan a pairing QR, press and hold for Enter code / Set up a mining computer (iOS 185).
  if ($('mining-add')) { $('mining-add').addEventListener('click', scan); longPress($('mining-add'), addMenu); }
  applyTab();
  return { render, hidden, tabOn, setTab, applyTab, addMenu };
})();
