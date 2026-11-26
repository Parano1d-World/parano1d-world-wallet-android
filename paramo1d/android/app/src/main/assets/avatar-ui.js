'use strict';
// Your avatar and other people's shared avatars, ported from iOS (PersonalAvatar.swift, SharedAvatar.swift,
// ChatAvatar, CharacterAvatarEditor; 198 / 1.0.0 (22)).
// - Your own avatar: what you picked (character or pixel, symbol, monogram, initials, or an owned NFT in the
//   internal build); until you pick one it's a wallet icon, never "?".
// - Saved on this phone for your chat identity, like iOS (device-local, not secret). Nothing is published until
//   you tap "Share my selected avatar" in a private chat or, publicly, in #parano1d.
// - Shared avatars travel as an ordinary signed chat message, "Parano1d avatar v1: {json}", the same recipe iOS
//   sends, so the two apps show each other's avatars. Only verified messages from that person are accepted.
// - Anyone without a shared avatar shows their initials, or a person icon when there's no name.
window.Avatars = (() => {
  const PREFIX = 'Parano1d avatar v1: ';
  const SYMBOLS = ['person.fill', 'cat.fill', 'bolt.fill', 'leaf.fill', 'moon.stars.fill', 'sparkles', 'shield.fill'];
  const SYMBOL_ICON = { 'person.fill': 'sym-person', 'cat.fill': 'sym-cat', 'bolt.fill': 'sym-bolt', 'leaf.fill': 'sym-leaf', 'moon.stars.fill': 'sym-moon', sparkles: 'sym-sparkles', 'shield.fill': 'sym-shield' };
  const SYMBOL_NAME = { 'person.fill': 'Person', 'cat.fill': 'Cat', 'bolt.fill': 'Bolt', 'leaf.fill': 'Leaf', 'moon.stars.fill': 'Moon and stars', sparkles: 'Sparkles', 'shield.fill': 'Shield' };
  const COLORS = ['Green', 'Cyan', 'Purple', 'Orange', 'Pink'];
  const C = () => window.CharacterAvatar;
  const hexColor = (v) => '#' + v.toString(16).padStart(6, '0');
  const tintHex = (name) => hexColor(C().tint(name));

  // ---- your own avatar (device-local, keyed by your chat identity like iOS's profile.avatar.<field>.<root>) ----
  const root = () => state?.profile?.id || '';
  const key = (field, r = root()) => `profile.avatar.${field}.${r}`;
  function get(field, fallback = '') { try { const v = localStorage.getItem(key(field)); return v == null ? fallback : v; } catch { return fallback; } }
  function has(field) { try { return localStorage.getItem(key(field)) != null; } catch { return false; } }
  function set(field, value) { try { localStorage.setItem(key(field), value); } catch { } changed(); }
  function changed() { try { refreshOwn(); } catch { } }
  const mode = () => get('mode', 'initials');
  const character = () => C().decode(get('character'));
  function initials(name) {
    const parts = String(name || '').trim().split(/\s+/).filter(Boolean);
    if (!parts.length) return '';
    return ([...parts[0]][0] + (parts.length > 1 ? [...parts.at(-1)][0] : '')).toUpperCase();
  }

  function walletIcon(node) {
    node.classList.add('avatar', 'default-avatar');
    node.style.removeProperty('--avatar-tint');
    node.replaceChildren(icon('wallet')); node.setAttribute('aria-label', 'Wallet avatar'); return node;
  }
  function reset(node) {
    node.classList.remove('default-avatar', 'warrior-avatar', 'person-avatar', 'symbol-avatar', 'monogram-avatar', 'art-avatar', 'pixel-avatar', 'initials-avatar');
    node.classList.add('avatar'); node.style.removeProperty('--avatar-tint'); node.removeAttribute('aria-label'); return node;
  }
  function art(node, recipe, label) {
    reset(node); node.classList.add('art-avatar');
    const img = document.createElement('img'); img.alt = ''; img.draggable = false; img.src = C().uri(recipe);
    node.replaceChildren(img); node.setAttribute('aria-label', label); return node;
  }
  function symbol(node, value, color) {
    reset(node); node.classList.add('symbol-avatar'); node.style.setProperty('--avatar-tint', tintHex(color));
    node.replaceChildren(icon(SYMBOL_ICON[value] || 'sym-person')); node.setAttribute('aria-label', 'Symbol avatar'); return node;
  }
  function letters(node, text, color) {
    reset(node); node.classList.add('monogram-avatar'); node.style.setProperty('--avatar-tint', tintHex(color));
    node.replaceChildren(document.createTextNode(text)); node.setAttribute('aria-label', 'Monogram avatar'); return node;
  }
  /** iOS ChatAvatar: initials, or a person icon when there's no name. */
  function nameAvatar(node, name) {
    reset(node); const text = initials(name);
    if (text) { node.classList.add('initials-avatar'); node.replaceChildren(document.createTextNode(text)); node.setAttribute('aria-label', `Initials for ${name}`); }
    else { node.classList.add('person-avatar'); node.replaceChildren(icon('person')); node.setAttribute('aria-label', 'No name'); }
    return node;
  }
  function nftArt(node, hash, fallback) {
    fallback(node);
    if (!hash || !/^[0-9a-f]{64}$/.test(hash) || state?.prefs?.external_media === false || state?.offline) return node;
    const want = hash; node.dataset.nft = hash;
    native('asset_media', { hash }).then((m) => {
      if (node.dataset.nft !== want || !m?.thumbnail) return;
      reset(node); node.classList.add('art-avatar');
      const img = document.createElement('img'); img.alt = ''; img.src = m.thumbnail; node.replaceChildren(img); node.setAttribute('aria-label', 'NFT avatar');
    }).catch(() => {});
    return node;
  }
  function ownedNFTs() {
    const me = state?.profile?.address || ''; if (!me || typeof assetState === 'undefined' || !assetState) return [];
    const found = new Map();
    for (const l of assetState.listings?.items || []) if (l.seller === me && l.collection_id) found.set(`${l.collection_id}:${l.serial}:${me}`, { ...l, listed: true });
    for (const h of assetState.holdings?.items || []) if (h.type === 'nft' && h.owner === me && h.collection_id) found.set(`${h.collection_id}:${h.serial}:${me}`, h);
    return [...found.entries()].sort(([a], [b]) => a < b ? -1 : 1).map(([k, item]) => ({ key: k, item }));
  }

  /** Paints your own avatar into node (settings wallet card, chat identity strip, previews). */
  function own(node = element('span', { class: 'avatar' })) {
    delete node.dataset.avatarSig; delete node.dataset.nft;
    if (!root()) return walletIcon(node);
    const m = mode(), color = get('color', 'Green');
    if (m === 'character') return art(node, character(), 'Your customized character avatar');
    if (m === 'symbol') return symbol(node, get('symbol', 'person.fill'), color);
    if (m === 'monogram') {
      const text = get('monogram') || initials(state?.profile?.name);
      return text ? letters(node, text, color) : walletIcon(node);
    }
    if (m === 'nft' && !STORE_EDITION) {
      const pick = ownedNFTs().find((x) => x.key === get('nft'));
      return nftArt(node, pick?.item.metadata_hash, walletIcon);
    }
    // Nothing picked yet, or initials with no name: a wallet icon, not a "?".
    if (!has('mode') || !initials(state?.profile?.name)) return walletIcon(node);
    return letters(node, initials(state.profile.name), color);
  }
  function refreshOwn() {
    document.querySelectorAll('[data-own-avatar]').forEach((n) => own(n));
    const my = document.getElementById('my-avatar'); if (my) own(my);
  }

  // ---- shared avatars (SharedAvatar.swift) ----
  const hex64 = (v) => typeof v === 'string' && /^[0-9a-f]{64}$/.test(v);
  function validShared(a) {
    if (!a || a.version !== 1 || !['initials', 'monogram', 'symbol', 'character', 'nft'].includes(a.kind) || !COLORS.includes(a.color)
      || !SYMBOLS.includes(a.symbol) || typeof a.letters !== 'string' || [...a.letters].length > 3 || !/^[\p{L}\p{N}]*$/u.test(a.letters)) return false;
    if (a.kind === 'nft') {
      if (!hex64(a.metadataHash) || !hex64(a.collection) || !/^[0-9]{1,20}$/.test(a.serial || '') || BigInt(a.serial) > 18446744073709551615n) return false;
    } else if (a.metadataHash || a.collection || a.serial) return false;
    if (a.kind === 'character') return !!a.character && C().valid(a.character);
    return a.character == null;
  }
  function parse(text) {
    if (typeof text !== 'string' || !text.startsWith(PREFIX) || new TextEncoder().encode(text).length > 4096) return null;
    try {
      const v = JSON.parse(text.slice(PREFIX.length));
      const a = { version: v.version, kind: v.kind, letters: v.letters ?? '', symbol: v.symbol ?? 'person.fill', color: v.color ?? 'Green', character: v.character ?? null, metadataHash: v.metadataHash ?? '', collection: v.collection ?? '', serial: v.serial ?? '' };
      return validShared(a) ? a : null;
    } catch { return null; }
  }
  function displayText(text) {
    const a = parse(text); if (!a) return null;
    return a.kind === 'nft' ? `Shared an NFT avatar · #${a.serial}. This image is not proof of current ownership.` : 'Shared a selected avatar.';
  }
  /** Your current selection as a shareable recipe (SharedAvatar.selected). */
  function selected() {
    if (!root()) throw new Error('Set up your chat identity first.');
    let m = mode();
    const result = { version: 1, kind: m, letters: '', symbol: 'person.fill', color: get('color', 'Green'), metadataHash: '', collection: '', serial: '' };
    if (m === 'monogram') { result.letters = get('monogram'); if (!result.letters) result.kind = 'initials'; }
    else if (m === 'symbol') result.symbol = get('symbol', 'person.fill');
    else if (m === 'character') result.character = C().encode(character());
    else if (m === 'nft') {
      const pick = STORE_EDITION ? null : ownedNFTs().find((x) => x.key === get('nft'));
      if (!pick) throw new Error('Refresh verified NFT holdings or listings before sharing this avatar.');
      result.metadataHash = pick.item.metadata_hash; result.collection = pick.item.collection_id; result.serial = String(pick.item.serial);
    } else result.kind = 'initials';
    if (!validShared({ ...result, character: result.character ?? null })) throw new Error('Your avatar selection is unavailable or invalid. Open avatar settings to choose again.');
    // Same key order and fields as iOS's JSONEncoder (character only when present).
    const ordered = { version: 1, kind: result.kind, letters: result.letters, symbol: result.symbol, color: result.color };
    if (result.character) ordered.character = result.character;
    Object.assign(ordered, { metadataHash: result.metadataHash, collection: result.collection, serial: result.serial });
    const text = PREFIX + JSON.stringify(ordered);
    if (new TextEncoder().encode(text).length > 4096) throw new Error('Avatar exceeds the message limit.');
    return text;
  }

  // Peer avatars (PeerAvatarStore): newest verified message per person; private selections stay inside this
  // wallet's private chats, public ones come from #parano1d. At most 256 people per scope.
  const storeKey = (scope) => `peer.avatars.v1.${scope}`;
  const cacheByScope = new Map();
  function read(scope) {
    if (cacheByScope.has(scope)) return cacheByScope.get(scope);
    let rows = {}; try { rows = JSON.parse(localStorage.getItem(storeKey(scope)) || '{}') || {}; } catch { rows = {}; }
    for (const [k, r] of Object.entries(rows)) if (!r || !validShared(r.avatar)) delete rows[k];
    cacheByScope.set(scope, rows); return rows;
  }
  function write(scope, rows) { cacheByScope.set(scope, rows); try { localStorage.setItem(storeKey(scope), JSON.stringify(rows)); } catch { } }
  const seen = new Set();
  let peerVersion = 0;
  /** Reads shared avatars from verified messages in this phone's chat history (call after each update). */
  function observe(messages) {
    const me = root(); if (!me || !Array.isArray(messages)) return false;
    let touched = false;
    for (const m of messages) {
      if (!m?.id || seen.has(m.id)) continue; seen.add(m.id);
      if (m.message?.type !== 'text' || !String(m.message.text || '').startsWith(PREFIX)) continue;
      const sender = m.sender?.id; if (!sender || sender === me) continue;
      if (typeof ChatSafety !== 'undefined' && (ChatSafety.isBlocked?.(sender) || ChatSafety.screen?.(m))) continue;
      const avatar = parse(m.message.text); if (!avatar) continue;
      const created = Number(m.created_ms) || 0; if (!created || created > Date.now() + 300000) continue;
      let scope;
      if (m.chat === `d:${sender}`) scope = `private.${me}`;
      else if (m.chat === 'g:parano1d') scope = 'public';
      else continue;
      const rows = { ...read(scope) };
      const prior = rows[sender];
      if (prior && !(created > prior.created_ms || (created === prior.created_ms && String(m.id) > String(prior.id)))) continue;
      rows[sender] = { avatar, created_ms: created, id: String(m.id) };
      const ids = Object.keys(rows);
      if (ids.length > 256) { const oldest = ids.reduce((a, b) => rows[a].created_ms <= rows[b].created_ms ? a : b); delete rows[oldest]; }
      write(scope, rows); touched = true;
    }
    if (touched) peerVersion++;
    return touched;
  }
  function peerSelection(identity, publicOnly) {
    if (!publicOnly && root()) { const r = read(`private.${root()}`)[identity]; if (r) return r.avatar; }
    return read('public')[identity]?.avatar || null;
  }
  /** Paints someone else's avatar: their shared selection, else initials, else a person icon. */
  function peer(node, identity, name = '', { publicOnly = false } = {}) {
    const a = identity ? peerSelection(identity, publicOnly) : null;
    // Unchanged avatar: keep the painted node (no flicker on every update).
    const sig = JSON.stringify([a, name || '', STORE_EDITION]);
    if (node.dataset.avatarSig === sig && node.firstChild) return node;
    node.dataset.avatarSig = sig; delete node.dataset.nft;
    node.title = name || '';
    if (a) {
      if (a.kind === 'character') return art(node, a.character, `Avatar for ${name || 'contact'}`);
      if (a.kind === 'monogram' && a.letters) return letters(node, a.letters, a.color);
      if (a.kind === 'symbol') return symbol(node, a.symbol, a.color);
      if (a.kind === 'nft' && !STORE_EDITION) return nftArt(node, a.metadataHash, (n) => nameAvatar(n, name));
    }
    return nameAvatar(node, name);
  }
  function peerNode(identity, name = '', tag = 'span', attributes = {}, options = {}) {
    const target = document.createElement(tag);
    for (const [k, v] of Object.entries(attributes)) { if (k.startsWith('on')) target.addEventListener(k.slice(2), v); else target.setAttribute(k, v); }
    target.classList.add('avatar');
    return peer(target, identity, name, options);
  }

  // ---- sharing ----
  function share(scope) {
    const isPublic = scope === 'g:parano1d';
    let text; try { text = selected(); } catch (error) { toast(error.message); return; }
    const go = element('button', { class: 'primary-button', text: isPublic ? 'Share publicly' : 'Share encrypted avatar', onclick: () => run(go, async () => {
      state = await native('send', { chat: scope, message: { type: 'text', text } }); $('modal').close(); render(); toast('Avatar shared.');
    }) });
    modal(isPublic ? 'Share your selected avatar publicly?' : 'Share your selected avatar with this contact?', [
      element('div', { class: 'avatar-share-preview' }, own(element('span', { class: 'avatar avatar-xl' }))),
      element('p', { text: isPublic
        ? 'Everyone in #parano1d can see it, keep it or forward it. NFT selections include their collection and number. Older apps may show the avatar recipe as text.'
        : 'This contact can retain or forward the avatar. NFT selections include their collection and number. Older clients may show the avatar recipe as text. This does not publish it.' }),
      go, element('button', { class: 'outline-button', text: 'Cancel', onclick: () => $('modal').close() })], 'AVATAR');
  }

  // ---- "Your avatar" (AvatarSettingsView) ----
  const back = (label, to) => typeof settingsBack === 'function' ? settingsBack(label, to) : '';
  function selectRow(label, value, options, onchange, id) {
    const select = element('select', { class: 'avatar-select', 'aria-label': label, ...(id ? { id } : {}) }, ...options.map(([v, t]) => element('option', { value: v, text: t })));
    select.value = value; select.addEventListener('change', () => onchange(select.value));
    return element('label', { class: 'settings-line avatar-option' }, element('span', { class: 'row-text' }, element('strong', { text: label })), select);
  }
  function showSettings() {
    const m = mode();
    const sources = [['initials', 'Name initials (default)'], ['symbol', 'Symbol'], ['character', 'Character / Pixel'], ['monogram', 'Monogram']];
    if (!STORE_EDITION) sources.push(['nft', 'Owned NFT']);
    const preview = own(element('span', { class: 'avatar avatar-xl', id: 'avatar-preview', 'data-own-avatar': '' }));
    const nodes = [back('Chat', () => openSettingsPage('chat')),
      element('div', { class: 'avatar-hero' }, preview),
      settingsSection('Avatar', selectRow('Avatar source', m, sources, (v) => { set('mode', v); showSettings(); }, 'avatar-source'))];
    const rows = [];
    if (m === 'character') rows.push(settingsRow({ icon: 'palette', title: 'Edit character and pixel style', onclick: showEditor, id: 'avatar-edit' }));
    if (m === 'monogram') {
      const input = element('input', { id: 'avatar-monogram', value: get('monogram'), autocapitalize: 'characters', autocomplete: 'off', spellcheck: 'false', placeholder: 'Initials · up to 3 letters or numbers', 'aria-label': 'Monogram' });
      input.addEventListener('input', () => { const clean = [...input.value.toUpperCase()].filter((ch) => /[\p{L}\p{N}]/u.test(ch)).slice(0, 3).join(''); if (clean !== input.value) input.value = clean; set('monogram', clean); own(preview); });
      rows.push(element('label', { class: 'settings-line avatar-option' }, element('span', { class: 'row-text' }, element('strong', { text: 'Letters' })), input));
    }
    if (m === 'symbol') {
      rows.push(element('div', { class: 'avatar-symbols', role: 'radiogroup', 'aria-label': 'Symbol' }, ...SYMBOLS.map((s) => {
        const on = get('symbol', 'person.fill') === s;
        return element('button', { class: `avatar-symbol${on ? ' on' : ''}`, role: 'radio', 'aria-checked': String(on), 'aria-label': SYMBOL_NAME[s], style: `--avatar-tint:${tintHex(get('color', 'Green'))}`, onclick: () => { set('symbol', s); showSettings(); } }, icon(SYMBOL_ICON[s]));
      })));
      rows.push(settingsRow({ icon: 'palette', title: 'Create a character avatar', onclick: () => { set('mode', 'character'); showEditor(); } }));
    }
    if (m !== 'nft' && m !== 'character') rows.push(selectRow('Color', get('color', 'Green'), COLORS.map((c) => [c, c]), (v) => { set('color', v); showSettings(); }, 'avatar-color'));
    if (rows.length) nodes.push(settingsSection('', ...rows));
    if (m === 'nft' && !STORE_EDITION) {
      const list = ownedNFTs();
      const current = get('nft');
      const options = [['', 'Select an NFT'], ...(current && !list.some((x) => x.key === current) ? [[current, 'Previous selection · ownership unavailable']] : []),
        ...list.map(({ key: k, item }) => [k, `${item.collection_id.slice(0, 8)}… · #${item.serial}${item.listed ? ' · Listed' : ''}`])];
      nodes.push(settingsSection('Use an NFT you own',
        element('p', { class: 'card-note', text: 'Choose an NFT in your holdings or one you listed for sale. Sharing requires a current verified holdings or listing snapshot. If it is no longer available, the wallet icon is shown instead.' }),
        list.length ? selectRow('Owned NFT', current, options, (v) => { set('nft', v); showSettings(); }, 'avatar-nft') : element('p', { class: 'card-note', text: 'No NFTs in the current snapshot. Open the NFTs tab to refresh.' })));
    }
    nodes.push(element('p', { class: 'card-note', text: 'Saved for your chat identity on this phone and shown in your wallet and chat. It stays after locking or restarting. Use Share my selected avatar in a private conversation, or Share my selected avatar publicly in #parano1d. Contacts using a compatible version receive it after verifying the message. Local edits are not published automatically.' }));
    modal('Your avatar', nodes, 'SETTINGS');
  }

  // ---- "Create your avatar" (CharacterAvatarEditor) ----
  let selectedPreset = 'Custom';
  function update(changes, { keepPreset = false } = {}) {
    const c = { ...character(), ...changes };
    if (c.style === 'Pixel' && c.rendering == null) c.rendering = 'Pixel';
    set('character', JSON.stringify(C().encode(c))); set('mode', 'character');
    if (!keepPreset) selectedPreset = 'Custom';
    showEditor();
  }
  function choose(field, value) {
    const c = character();
    c[field] = value;
    if (field === 'face' && C().isNonHuman(c)) C().removeHumanLayers(c);
    if (field === 'style' && value === 'Anonymous' && C().isNonHuman(c)) c.face = 'Oval';
    if (field === 'accessory' && value === 'Mask' && C().isNonHuman(c)) c.face = 'Oval';
    if (((field === 'hair' && value !== 'Bald') || (field === 'facialHair' && value !== 'None')) && C().isNonHuman(c)) c.face = 'Oval';
    update(c);
  }
  function showEditor() {
    const scroll = document.getElementById('modal-content')?.scrollTop || 0;
    const reopen = document.getElementById('avatar-editor') != null;
    const c = character(), O = C().options;
    const opt = (title, field, values) => selectRow(title, c[field], values.map((v) => [v, v]), (v) => choose(field, v), `avatar-${field}`);
    const presets = element('div', { class: 'avatar-presets', id: 'avatar-presets' }, ...O.presets.map((name) => {
      const recipe = C().preset(name, c);
      const img = document.createElement('img'); img.alt = ''; img.src = C().uri(recipe);
      return element('button', { class: `avatar-preset${selectedPreset === name ? ' on' : ''}`, 'aria-label': `Use ${name} avatar`, onclick: () => {
        set('character', JSON.stringify(C().encode(C().preset(name, character())))); set('mode', 'character'); selectedPreset = name; showEditor();
      } }, element('span', { class: 'avatar avatar-preset-art' }, img), element('small', { text: name }));
    }));
    const nodes = [back('Your avatar', showSettings),
      element('div', { class: 'avatar-hero', id: 'avatar-editor' }, own(element('span', { class: 'avatar avatar-xxl', 'data-own-avatar': '' })),
        element('small', { class: 'card-note', text: 'Changes appear instantly' }),
        element('button', { class: `primary-button${mode() === 'character' ? ' selected' : ''}`, text: mode() === 'character' ? 'Character avatar selected' : 'Use this character avatar', onclick: () => { set('mode', 'character'); showEditor(); } })),
      settingsSection('Start with a character', presets, settingsRow({ icon: 'refresh', title: 'Reset to classic', onclick: () => { set('character', JSON.stringify(C().encode({ ...C().DEFAULT }))); set('mode', 'character'); selectedPreset = 'Custom'; showEditor(); } })),
      settingsSection('Artwork',
        selectRow('Rendering', C().isPixel(c) ? 'Pixel' : 'Vector', [['Vector', 'Vector'], ['Pixel', 'Pixel']], (v) => { const u = { ...character(), rendering: v }; if (u.style === 'Pixel') u.style = 'Classic'; update(u, { keepPreset: true }); }, 'avatar-rendering'),
        C().isPixel(c) ? selectRow('Pixel palette', c.pixelTheme || 'Neon', O.pixelThemes.map((v) => [v, v]), (v) => update({ pixelTheme: v }, { keepPreset: true }), 'avatar-pixelTheme') : ''),
      settingsSection('Style and face', opt('Style', 'style', O.styles), opt('Presentation', 'presentation', O.presentations), opt('Face shape', 'face', O.faces), opt('Skin tone', 'skin', O.skins), opt('Expression', 'eyes', O.expressions)),
      settingsSection('Hair', opt('Hairstyle', 'hair', O.hairstyles), opt('Hair color', 'hairColor', O.hairColors), opt('Facial hair', 'facialHair', O.facialHair)),
      settingsSection('Clothing and accessories', opt('Clothing', 'clothing', O.clothing), opt('Head, face or eyewear', 'accessory', O.accessories),
        selectRow('Jewelry', c.jewelry || 'None', O.jewelry.map((v) => [v, v]), (v) => { const u = character(); if (v === 'None') delete u.jewelry; else u.jewelry = v; set('character', JSON.stringify(C().encode(u))); set('mode', 'character'); selectedPreset = 'Custom'; showEditor(); }, 'avatar-jewelry'),
        opt('Accent', 'accent', O.accents), opt('Background', 'background', O.backgrounds)),
      element('p', { class: 'card-note', text: 'Edits save and select this character automatically on this phone. Share your selected avatar in chat when you want others to receive it. These options describe the artwork, not verified identity or personal information.' })];
    modal('Create your avatar', nodes, 'SETTINGS');
    if (reopen) document.getElementById('modal-content').scrollTop = scroll;
    const strip = document.getElementById('avatar-presets'); const on = strip?.querySelector('.on');
    if (on && !reopen) on.scrollIntoView({ inline: 'center', block: 'nearest' });
  }

  return Object.freeze({ PREFIX, own, peer, peerNode, observe, parse, displayText, selected, share, showSettings, showEditor, initials, nameAvatar, validShared,
    get version() { return peerVersion; } });
})();
