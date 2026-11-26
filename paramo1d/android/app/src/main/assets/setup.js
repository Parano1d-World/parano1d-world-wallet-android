// Wallet setup, matching iOS WalletSetupView:
// Welcome (Create / Import / Watch) → Import ("Restore your wallet.", 16-box master key) → straight to Home,
// or Create → write down → verify → "Your wallet is ready." → Home.
// The master key never leaves the phone. A new wallet's key is held only in native memory until verified.
const WalletSetup = (() => {
  const HIDE_AFTER_MS = 15 * 60 * 1000;   // write-down screen, same as iOS
  const SHOW_KEY_MS = 30 * 1000;          // "Show key" on the entry boxes
  let screen = 'welcome';
  let holding = false;          // keep setup open after the wallet exists (the "ready" screen)
  let separate = false;
  let backup = false;            // Settings › Wallet › Master key and backup (iOS: view and re-verify the current wallet's key)         // adding another master key from Settings › Wallets and accounts
  let newKey = '';              // shown on the write-down screen only; cleared after verify or cancel
  let shownAt = 0, hideTimer = null, revealed = false;

  const root = () => $('setup-view');
  const clean = (text) => String(text || '').toLowerCase().replace(/[^0-9a-f]/g, '');
  const groupsOf = (hex) => Array.from({ length: 16 }, (_, i) => hex.slice(i * 4, i * 4 + 4));

  function active() { return holding; }

  function go(next) {
    screen = next;
    paint();
    root()?.scrollTo?.(0, 0); $('onboarding')?.scrollTo?.(0, 0);
  }

  function heading(title, subtitle, step) {
    const nodes = [];
    if (step) nodes.push(element('div', { class: 'setup-steps', 'aria-label': `Setup step ${Math.min(step, 3)} of 3` },
      ...[1, 2, 3].map(i => element('span', { class: i <= step ? 'on' : '' }))));
    nodes.push(element('h1', { class: 'setup-title', text: title }));
    if (subtitle) nodes.push(element('p', { class: 'setup-subtitle', text: subtitle }));
    return nodes;
  }

  function cancelButton(label = 'Cancel and return to wallet choices') {
    return element('button', { class: 'text-button setup-cancel', text: separate || backup ? 'Cancel and return to Settings' : state?.watch_only ? 'Cancel and return to the watched address' : label, onclick: () => { forgetNewKey(); if (separate || backup) { separate = false; backup = false; close(); } else if (state?.watch_only) close(); else go('welcome'); } });
  }

  // ---------- 1. Welcome ----------
  function welcome() {
    return [
      element('div', { class: 'setup-welcome' },
        element('img', { class: 'brand-logo official-logo logo-light setup-logo', src: 'logo-light.png', alt: 'Parano1d World' }),
        element('img', { class: 'brand-logo official-logo logo-dark setup-logo', src: 'logo-dark.png', alt: 'Parano1d World' }),
        element('h1', { class: 'setup-hero' }, 'Your world.', element('br'), 'Your wallet.'),
        element('p', { class: 'setup-lead', text: 'Create your Parano1d wallet or bring back an existing one. Your master key stays on this device.' }),
        element('div', { class: 'setup-actions' },
          element('button', { class: 'primary-button setup-primary', text: 'Create a new wallet', onclick: () => go('create') }),
          element('button', { class: 'setup-secondary', text: 'Import an existing wallet', onclick: () => go('import') }),
          element('button', { class: 'text-button setup-watch', text: 'Watch an address', onclick: () => go('watch') })),
        element('small', { class: 'setup-footer', text: 'Never share your master key. We cannot restore it for you.' }))
    ];
  }

  // ---------- 16-box master key grid ----------
  function keyGrid({ value = '', readOnly = false, onChange } = {}) {
    const boxes = [];
    const grid = element('div', { class: 'key-grid masked' + (readOnly ? ' readonly' : ''), role: 'group', 'aria-label': 'Parano1d master key, 16 groups' });
    const groups = groupsOf(value);
    for (let i = 0; i < 16; i++) {
      const cell = element('label', { class: 'key-cell' }, element('span', { class: 'key-index', text: String(i + 1) }));
      if (readOnly) {
        cell.append(element('span', { class: 'key-group', text: groups[i] }));
      } else {
        const input = element('input', {
          class: 'key-input', type: 'text', inputmode: 'text', autocomplete: 'off', autocapitalize: 'off', autocorrect: 'off',
          spellcheck: 'false', 'data-lpignore': 'true', 'data-1p-ignore': 'true', 'aria-label': `Group ${i + 1} of 16`,
          maxlength: i === 0 ? '128' : '4'
        });
        input.value = groups[i];
        input.addEventListener('input', () => {
          const text = clean(input.value);
          if (text.length >= 64) {                       // a full key pasted (into box 1, or any box): fill all 16
            groupsOf(text.slice(0, 64)).forEach((g, k) => { boxes[k].value = g; });
            boxes[15].focus();
          } else {
            input.value = text.slice(0, 4);
            if (input.value.length === 4 && i < 15) boxes[i + 1].focus();
          }
          onChange?.(boxes.map(b => b.value).join(''));
        });
        input.addEventListener('keydown', (event) => {
          if (event.key === 'Backspace' && !input.value && i > 0) { event.preventDefault(); boxes[i - 1].focus(); }
        });
        boxes.push(input); cell.append(input);
      }
      grid.append(cell);
    }
    // Hidden by default (dots); "Show key" reveals it for 30 seconds (owner's choice; iOS uses 15 s).
    let timer = null;
    const toggle = element('button', { class: 'text-button key-view', type: 'button', text: 'Show key' });
    const setVisible = (on) => {
      grid.classList.toggle('masked', !on); toggle.textContent = on ? 'Hide key' : 'Show key';
      toggle.setAttribute('aria-pressed', String(on)); clearTimeout(timer);
      if (on) timer = setTimeout(() => setVisible(false), readOnly ? HIDE_AFTER_MS : SHOW_KEY_MS);
    };
    toggle.addEventListener('click', () => setVisible(grid.classList.contains('masked')));
    if (readOnly) toggle.hidden = true;
    const wrap = element('div', { class: 'key-grid-wrap' }, grid, toggle);
    return { grid: wrap, boxes, setVisible, value: () => boxes.map(b => b.value).join(''), clear: () => { boxes.forEach(b => { b.value = ''; }); setVisible(false); } };
  }

  function counter(hex) { return `${hex.length} of 64 characters · Letters A–F and digits 0–9`; }

  // ---------- 2. Import ----------
  function importScreen() {
    const count = element('p', { class: 'setup-hint', text: counter('') });
    const restore = element('button', { class: 'primary-button setup-primary', text: 'Restore wallet', disabled: 'disabled' });
    const entry = keyGrid({ onChange: (hex) => { count.textContent = counter(hex); restore.disabled = hex.length !== 64; } });
    const name = element('input', { class: 'setup-name', maxlength: '40', placeholder: 'Personal', autocomplete: 'off', autocapitalize: 'words', spellcheck: 'false' });
    restore.addEventListener('click', () => run(restore, async () => {
      const key = entry.value();
      state = await native('wallet_import', { key_hex: key, wallet_name: name.value.trim(), separate });
      entry.clear();
      if (separate) { separate = false; close(); toast('Wallet added. It is now selected.'); return; }
      // iOS 188 item 51: an imported key is already the backup. Close setup and open Home.
      holding = false; screen = 'welcome'; tab = 'wallet';
      render(); poll();
    }, 'Restoring…'));
    return [
      ...heading('Restore your wallet.', 'Restore the 64-character hexadecimal master key exported by the official Parano1d wallet.'),
      element('section', { class: 'setup-panel' },
        element('h2', { class: 'setup-panel-title', text: 'Parano1d master key' }),
        element('p', { class: 'setup-hint', text: '16 groups of 4 hex characters. Paste the full 64-character key into the first box to fill all groups.' }),
        entry.grid, count),
      element('section', { class: 'setup-panel' },
        element('label', { class: 'setup-label' }, element('span', { text: 'Wallet name (optional)' }), name),
        element('p', { class: 'setup-hint', text: 'Saved only on this device. Up to 40 characters; you can change it later.' })),
      element('p', { class: 'setup-note', text: 'Compare the restored address with your desktop wallet before receiving or sending funds. Your key stays on this device.' }),
      restore,
      cancelButton(),
      element('button', { class: 'text-button setup-link', text: 'Have a backup file instead? Restore from a backup file', onclick: () => showRestore() })
    ];
  }

  // ---------- Create: intro ----------
  function guidance(icon, title, detail) {
    return element('div', { class: 'setup-guidance' },
      element('span', { class: 'setup-guidance-icon', 'aria-hidden': 'true' }, svgIcon(icon)),
      element('span', {}, element('strong', { text: title }), element('small', { text: detail })));
  }
  function svgIcon(icon) { const ns = 'http://www.w3.org/2000/svg', svg = document.createElementNS(ns, 'svg'), use = document.createElementNS(ns, 'use'); svg.setAttribute('class', 'icon'); svg.setAttribute('aria-hidden', 'true'); use.setAttribute('href', `icons.svg#${icon}`); svg.append(use); return svg; }

  function createIntro() {
    const create = element('button', { class: 'primary-button setup-primary', id: 'setup-create', text: backup ? 'View master key' : 'Create wallet', disabled: 'disabled' });
    const agree = element('input', { type: 'checkbox', class: 'setup-check' });
    agree.addEventListener('change', () => { create.disabled = !agree.checked; });
    create.addEventListener('click', () => run(create, async () => {
      const result = await native(backup ? 'wallet_key_reveal' : 'wallet_create_begin', { separate });
      newKey = clean(result.key_hex); revealed = false;
      go('write');
    }));
    return [
      ...heading(backup ? 'Your recovery backup.' : separate ? 'Create another wallet.' : 'Secure from the start.', 'Your recovery secret controls your wallet. Only you should know it.', 1),
      element('div', { class: 'setup-guidance-list' },
        guidance('eye-off', 'Keep the key private', 'Anyone with this 64-character key can restore and control your wallet, even on another device.'),
        guidance('pencil', 'Keep an offline backup', 'Use paper or another durable offline backup. Avoid photos, email and cloud notes.'),
        guidance('lock', 'There is no password reset', 'If you lose the device and your master key, we cannot recover the wallet.')),
      element('label', { class: 'setup-toggle' }, agree, element('span', { text: 'I understand and am in a private place' })),
      create,
      cancelButton()
    ];
  }

  function scheduleHide() {
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => { revealed = false; if (backup) newKey = ''; if (screen === 'write') paint(); }, HIDE_AFTER_MS);
  }

  // ---------- Create: write down ----------
  function writeDown() {
    const written = element('input', { type: 'checkbox', class: 'setup-check' });
    const next = element('button', { class: 'primary-button setup-primary', text: 'Next · verify backup', disabled: 'disabled' });
    written.addEventListener('change', () => { next.disabled = !written.checked; });
    next.addEventListener('click', () => { revealed = false; if (backup) newKey = ''; clearTimeout(hideTimer); go('verify'); });
    const keyPanel = revealed && newKey.length === 64
      ? element('section', { class: 'setup-panel' },
          element('h2', { class: 'setup-panel-title', text: 'Parano1d master key' }),
          (() => { const g = keyGrid({ value: newKey, readOnly: true }); g.setVisible(true); return g.grid; })(),
          element('button', { class: 'text-button setup-link', text: 'Hide key now', onclick: () => { revealed = false; if (backup) newKey = ''; paint(); } }))
      : element('section', { class: 'setup-panel setup-hidden-key' },
          element('h2', { class: 'setup-panel-title', text: 'Master key hidden' }),
          element('p', { class: 'setup-hint', text: 'Your new key is hidden. Reveal it when you are in a private place and no one can see your screen.' }),
          element('button', { class: 'primary-button setup-primary', text: 'Reveal master key', onclick: (event) => {
            // The current wallet's key is fetched again after the phone's screen lock (it isn't kept while hidden).
            if (backup && newKey.length !== 64) { run(event.currentTarget, async () => { newKey = clean((await native('wallet_key_reveal')).key_hex); revealed = true; shownAt = Date.now(); scheduleHide(); paint(); }); return; }
            revealed = true; shownAt = Date.now(); scheduleHide(); paint(); } }));
    return [
      ...heading('Write down your key.', 'Write all 16 groups of your master key in order. Each group has four hexadecimal characters. Store it offline.', 2),
      keyPanel,
      element('p', { class: 'setup-hint', text: 'The key hides automatically after 15 minutes. You can hide it sooner and reveal it again. Screenshots are blocked unless you allowed them in Settings.' }),
      element('label', { class: 'setup-toggle' }, written, element('span', { text: 'I wrote down all 16 groups in order' })),
      next,
      cancelButton()
    ];
  }

  // ---------- Create: verify ----------
  function verify() {
    const count = element('p', { class: 'setup-hint', text: counter('') });
    const check = element('button', { class: 'primary-button setup-primary', text: 'Verify backup', disabled: 'disabled' });
    const entry = keyGrid({ onChange: (hex) => { count.textContent = counter(hex); check.disabled = hex.length !== 64; } });
    check.addEventListener('click', () => run(check, async () => {
      // The phone compares with the key it is holding; the wallet is saved only when they match.
      holding = true;
      try { state = await native(backup ? 'wallet_key_check' : 'wallet_create_finish', { key_hex: entry.value(), separate }); }
      catch (error) { holding = false; throw error; }
      entry.clear(); newKey = ''; clearTimeout(hideTimer);
      go('ready');
    }, 'Checking…'));
    return [
      ...heading('Check your backup.', 'Enter all 16 groups from your written backup. The previous screen’s key is now hidden.', 3),
      element('section', { class: 'setup-panel' },
        element('h2', { class: 'setup-panel-title', text: 'Parano1d master key' }),
        element('p', { class: 'setup-hint', text: '16 groups of 4 hex characters, in the order you wrote them.' }),
        entry.grid, count),
      check,
      element('button', { class: 'text-button setup-link', text: 'Review my master key', onclick: () => { revealed = false; go('write'); } }),
      cancelButton()
    ];
  }

  // ---------- Create: ready ----------
  function ready() {
    return [
      ...heading('Your wallet is ready.', 'Your saved backup matches this wallet. Keep it offline and never share it.', 4),
      element('div', { class: 'setup-shield', 'aria-hidden': 'true' }, svgIcon('shield')),
      element('section', { class: 'setup-panel' },
        element('p', { class: 'setup-hint', text: 'Your Parano1d address' }),
        element('p', { class: 'setup-address', text: state?.profile?.address || '' }),
        element('p', { class: 'setup-verified', text: '✓ Recovery backup verified' })),
      element('p', { class: 'setup-note', text: 'Verify this address against your independent backup. Test sends with a disposable wallet after device validation.' }),
      element('button', { class: 'primary-button setup-primary', text: 'Open wallet', onclick: () => { separate = false; backup = false; holding = false; screen = 'welcome'; tab = 'wallet'; $('onboarding').hidden = true; render(); poll(); } })
    ];
  }

  // ---------- Watch an address (watch-only look-up; nothing can be sent) ----------
  // iOS WatchAddressEntryView: save a public address and open it as a watch-only wallet on Home.
  function watch() {
    const address = element('input', { class: 'setup-name setup-mono', placeholder: 'o1…', autocomplete: 'off', autocapitalize: 'off', autocorrect: 'off', spellcheck: 'false', maxlength: '80' });
    const start = element('button', { class: 'primary-button setup-primary', text: 'Start watching', disabled: 'disabled' });
    address.addEventListener('input', () => { start.disabled = !address.value.trim(); });
    start.addEventListener('click', () => run(start, async () => {
      state = await native('watch_start', { address: address.value.trim() });
      holding = false; screen = 'welcome'; tab = 'wallet'; render(); poll();
    }, 'Saving…'));
    return [
      ...heading('Watch an address.', 'Watch an address without importing its master key. This device cannot spend from a watch-only wallet.'),
      element('section', { class: 'setup-panel' }, element('label', { class: 'setup-label' }, element('span', { text: 'Public Parano1d address' }), address)),
      start,
      cancelButton()
    ];
  }

  function forgetNewKey() {
    if (newKey && !backup) native('wallet_create_cancel').catch(() => {});
    newKey = ''; revealed = false; clearTimeout(hideTimer);
  }

  // Leaving the app hides a revealed key (iOS hides it when the app is no longer active).
  document.addEventListener('visibilitychange', () => { if (document.hidden && revealed) { revealed = false; if (backup) newKey = ''; if (screen === 'write') paint(); } });
  function paint() {
    const view = root(); if (!view) return;
    const build = { welcome, import: importScreen, create: createIntro, write: writeDown, verify, ready, watch }[screen] || welcome;
    view.className = `setup-view setup-${screen}`;
    view.replaceChildren(...build().filter(Boolean));
  }

  // Paint only when setup first appears, so a background re-render never wipes what is being typed.
  // From a watch-only Home: open Import or Create on top of it ("Add its master key").
  // Wallets and accounts: { separate: true } adds another master key next to the current wallet.
  function open(next, options = {}) { separate = !!options.separate; backup = next === 'backup'; if (backup) next = 'create'; holding = true; screen = next; $('onboarding').hidden = false; paint(); $('onboarding').scrollTo?.(0, 0); }
  function close() { holding = false; screen = 'welcome'; $('onboarding').hidden = true; render(); }

  function show() { const first = $('onboarding').hidden || !root()?.childElementCount; $('onboarding').hidden = false; if (first) paint(); }

  // Back button inside setup: one screen back. Welcome hands back to the app (moves it to the background);
  // the "ready" screen stays until "Open wallet".
  function back() {
    if ($('onboarding').hidden) return false;
    if (screen === 'ready') return true;
    const previous = { import: 'welcome', create: 'welcome', watch: 'welcome', write: 'create', verify: 'write' }[screen];
    if (!previous) return false;
    if (state?.watch_only && previous === 'welcome') { forgetNewKey(); close(); return true; }
    if (screen === 'write') forgetNewKey();
    go(previous); return true;
  }

  // Like iOS: leaving the app hides the key at once (entry boxes back to dots, write-down back to "hidden").
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) return;
    document.querySelectorAll('.key-grid').forEach(grid => grid.classList.add('masked'));
    document.querySelectorAll('.key-view').forEach(button => { button.textContent = 'Show key'; button.setAttribute('aria-pressed', 'false'); });
    if (revealed) { revealed = false; clearTimeout(hideTimer); if (screen === 'write') paint(); }
  });

  return { show, active, back, paint, open };
})();

// The display name is asked the first time Chat opens (iOS: chat setup), not during wallet setup.
const ChatName = (() => {
  function needed() { return state?.profile && state.profile_named === false; }
  function ask(then) {
    const name = field('Your display name', { placeholder: 'What should people call you?' });
    name.input.maxLength = 40; name.input.autocomplete = 'nickname';
    const save = element('button', { class: 'primary-button', text: 'Enable my chat identity', onclick: () => run(save, async () => {
      state = await native('profile_name', { name: name.input.value.trim() }); $('modal').close(); render(); then?.();
    }) });
    // First setup turns online delivery on (not in Parano1d mode).
    const note = state?.paranoid_mode
      ? 'Parano1d mode uses no servers, so messages go only to nearby phones. You can turn on online delivery in Settings → Chat.'
      : 'Messages are delivered encrypted through chat.parano1d.org, which can’t read them but sees which chat identities talk and when. You can switch to nearby-only in Settings → Chat.';
    modal('Set up chat.', [element('p', { text: 'Set up chat: enter a display name and enable your identity, then add a contact below.' }), name.label, save,
      element('p', { class: 'card-note', text: note })], 'CHAT');
    setTimeout(() => name.input.focus(), 50);
  }
  return { needed, ask };
})();
