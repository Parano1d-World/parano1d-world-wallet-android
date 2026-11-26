'use strict';
// iOS parity (owner request, 25 Nov: the same design as iOS):
// - ContactFavorites: a star per connected contact, favourites first, an All / Favorites filter.
// - WalletAddressBook: saved addresses with a name, favourite, icon and category, per wallet; contact categories.
// - ConnectedContactsView, CollectionsView ("NFT gallery") and ContactNFTGallery ("A contact's NFTs").
// Everything is local: the address book lives in the phone's encrypted store; nothing is sent anywhere.

/* ---------------- Favourites (iOS ContactFavorites, key contacts.favorites.v1) ---------------- */
const Favorites = (() => {
  const KEY = 'contacts.favorites.v1';
  function read() {
    try { const raw = localStorage.getItem(KEY) || '[]'; if (raw.length > 20000) return new Set(); const v = JSON.parse(raw); return new Set((Array.isArray(v) ? v : []).filter(x => /^[0-9a-f]{64}$/.test(x)).slice(0, 250)); }
    catch { return new Set(); }
  }
  function toggle(id) {
    if (!/^[0-9a-f]{64}$/.test(id)) return;
    const ids = read(); if (ids.has(id)) ids.delete(id); else if (ids.size < 250) ids.add(id);
    try { localStorage.setItem(KEY, JSON.stringify([...ids].sort())); } catch { }
  }
  /** The star button (iOS ContactFavoriteButton). */
  function button(peer, after) {
    const on = read().has(peer.id);
    const b = element('button', { class: `favorite-star${on ? ' on' : ''}`, 'aria-label': `${on ? 'Remove' : 'Add'} ${peer.name} ${on ? 'from' : 'to'} favorites`, 'aria-pressed': String(on),
      onclick: (e) => { e.stopPropagation(); toggle(peer.id); after?.(); } }, icon('star'));
    return b;
  }
  const sort = (list) => { const f = read(); return [...list].sort((a, b) => (f.has(b.id) - f.has(a.id)) || String(a.name).localeCompare(String(b.name)) || (a.id < b.id ? -1 : 1)); };
  return { read, toggle, button, sort };
})();

/* ---------------- Contact icons (iOS ContactIcon / ContactIconView) ---------------- */
const CONTACT_ICONS = [['initials', 'Name initials'], ['person.fill', 'Person'], ['man', 'Man'], ['woman', 'Woman'], ['cpu.fill', 'GPU / miner'], ['laptopcomputer', 'Laptop'],
  ['desktopcomputer', 'Desktop'], ['iphone', 'Phone'], ['globe', 'Website'], ['gamecontroller.fill', 'Controller'], ['building.2.fill', 'Business'], ['house.fill', 'Home']];
const CONTACT_ICON_SYMBOL = { 'person.fill': 'person', 'cpu.fill': 'cpu', laptopcomputer: 'laptop', desktopcomputer: 'computer', iphone: 'phone', globe: 'globe', 'gamecontroller.fill': 'gamepad', 'building.2.fill': 'building', 'house.fill': 'home' };
function contactIconView(name, iconName, size = 40) {
  // Sizes go through the style object (the page's content policy doesn't allow inline style attributes).
  const sized = (node) => { node.style.width = `${size}px`; node.style.height = `${size}px`; node.style.minWidth = `${size}px`; return node; };
  if (!iconName || iconName === 'initials') { const node = personAvatar(name); node.classList.add('contact-icon'); return sized(node); }
  const label = (CONTACT_ICONS.find(([v]) => v === iconName) || [, 'Contact'])[1] + ' icon';
  if (iconName === 'man' || iconName === 'woman') { const node = sized(element('span', { class: 'contact-icon emoji', role: 'img', 'aria-label': label, text: iconName === 'man' ? '👨' : '👩' })); node.style.fontSize = `${Math.round(size * 0.6)}px`; return node; }
  return sized(element('span', { class: 'contact-icon', role: 'img', 'aria-label': label }, icon(CONTACT_ICON_SYMBOL[iconName] || 'person')));
}
const shortAddr = (a) => `${String(a).slice(0, 9)}…${String(a).slice(-6)}`;

/* ---------------- Address book (iOS WalletAddressBook) ---------------- */
const AddressBook = (() => {
  let cache = { entries: [], categories: [] }, loadedFor = '';
  const sorted = (list) => [...list].sort((a, b) => (b.favorite - a.favorite) || a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }));
  async function load() { cache = await native('book_list'); cache.entries = sorted(cache.entries || []); loadedFor = state?.profile?.address || ''; return cache; }
  function cached() { return cache; }
  async function ensure() { if (loadedFor !== (state?.profile?.address || '')) try { await load(); } catch { } return cache; }
  const groupInSend = () => { try { return localStorage.getItem('send.contactCategoriesVisible') !== '0'; } catch { return true; } };
  const nameFor = (address) => cache.entries.find(e => e.address === address)?.name;

  /** iOS SaveWalletAddressView. */
  async function save(address, contactId = '', back) {
    await load().catch(() => {});
    const existing = cache.entries.find(e => e.address === address);
    const name = element('input', { id: 'book-name', maxlength: '60', value: existing?.name || (contactId ? (contact(contactId)?.name || '') : ''), 'aria-label': 'Name', placeholder: 'Name' });
    const favorite = element('input', { type: 'checkbox', id: 'book-favorite' }); favorite.checked = !!existing?.favorite;
    const iconSelect = element('select', { id: 'book-icon', 'aria-label': 'Contact icon' }, ...CONTACT_ICONS.map(([v, t]) => element('option', { value: v, text: t })));
    iconSelect.value = existing?.icon || 'initials';
    const category = element('select', { id: 'book-category', 'aria-label': 'Category' }, element('option', { value: '', text: 'None' }), ...cache.categories.map(c => element('option', { value: c.name, text: c.name })));
    category.value = existing?.category || '';
    const preview = element('span', { class: 'book-preview' });
    const paintPreview = () => preview.replaceChildren(contactIconView(name.value || '?', iconSelect.value, 44));
    name.addEventListener('input', paintPreview); iconSelect.addEventListener('change', paintPreview); paintPreview();
    const error = element('p', { class: 'danger', hidden: true });
    const go = element('button', { class: 'primary-button', id: 'book-save', text: 'Save address', onclick: async () => {
      go.disabled = true; go.textContent = 'Saving…'; error.hidden = true;
      try { cache = await native('book_save', { address, name: name.value.trim(), favorite: favorite.checked, icon: iconSelect.value, category: category.value, contact_id: existing?.contact_id || contactId || '' }); cache.entries = sorted(cache.entries); toast('Address saved.'); (back || (() => $('modal').close()))(); }
      catch (failed) { error.textContent = explainError(failed.message); error.hidden = false; go.disabled = false; go.textContent = 'Save address'; }
    } });
    const row = (label, control) => element('label', { class: 'settings-line book-line' }, element('span', { text: label }), control);
    modal('Save address', [
      settingsSection('Full receiving address', element('p', { class: 'book-address', text: address })),
      settingsSection('', element('div', { class: 'book-name-row' }, preview, name), row('Favorite', favorite), row('Contact icon', iconSelect), row('Category', category),
        settingsRow({ icon: 'tag', title: 'Manage categories', onclick: () => categories(() => save(address, contactId, back)) })),
      element('p', { class: 'card-note', text: 'Saved locally for this wallet. A saved name does not verify ownership or create a chat contact. Check the full address through a trusted channel.' }),
      error, go, element('button', { class: 'outline-button', text: 'Cancel', onclick: () => (back || (() => $('modal').close()))() })], 'ADDRESS BOOK');
    setTimeout(() => name.focus(), 50);
  }

  /** iOS ContactCategoryEditor. */
  async function categories(back) {
    await load().catch(() => {});
    const show = element('input', { type: 'checkbox', id: 'book-group-send' }); show.checked = groupInSend();
    show.addEventListener('change', () => { try { localStorage.setItem('send.contactCategoriesVisible', show.checked ? '1' : '0'); } catch { } });
    const name = element('input', { id: 'category-name', maxlength: '40', placeholder: 'Name', 'aria-label': 'Category name' });
    const iconSelect = element('select', { id: 'category-icon', 'aria-label': 'Icon' }, ...CONTACT_ICONS.map(([v, t]) => element('option', { value: v, text: t })));
    const error = element('p', { class: 'danger', hidden: true });
    const saveButton = element('button', { class: 'primary-button', id: 'category-save', text: 'Save category', onclick: () => run(saveButton, async () => {
      if (!name.value.trim()) throw new Error('Enter a category name and icon.');
      cache = await native('category_save', { name: name.value.trim(), icon: iconSelect.value }); cache.entries = sorted(cache.entries); categories(back);
    }) });
    modal('Contact categories', [
      element('label', { class: 'settings-line book-line' }, element('span', { text: 'Group contacts in Quick Send' }), show),
      settingsSection('Categories', ...cache.categories.map(c => element('div', { class: 'book-row' }, contactIconView(c.name, c.icon, 36), element('strong', { text: c.name }),
        element('button', { class: 'text-button', text: 'Edit icon', onclick: () => { name.value = c.name; iconSelect.value = c.icon; name.focus(); } }))),
        cache.categories.length ? '' : element('p', { class: 'card-note', text: 'Create a category to organize your contacts.' })),
      settingsSection('New category', name, iconSelect, saveButton), error,
      back ? element('button', { class: 'outline-button', text: 'Done', onclick: back }) : ''], 'ADDRESS BOOK');
  }

  /** iOS WalletAddressBookView. With `select`, each row has Use (Send). */
  async function show(select) {
    await load().catch((e) => toast(explainError(e.message)));
    let query = '';
    const search = element('input', { type: 'search', id: 'book-search', placeholder: 'Name or address', 'aria-label': 'Search name or address' });
    const list = element('div', { class: 'book-list' });
    const reopen = () => show(select);
    function paint() {
      const q = query.trim().toLowerCase(), hit = (n, a) => !q || n.toLowerCase().includes(q) || a.toLowerCase().includes(q);
      const contacts = Favorites.sort((state?.contacts || []).filter(c => c.address && hit(c.name, c.address)));
      const saved = cache.entries.filter(e => hit(e.name, e.address));
      list.replaceChildren(
        settingsSection('Connected contacts', ...contacts.map(c => element('div', { class: 'book-row', 'data-address': c.address },
          WarriorAvatar.node(c.id, c.name), element('span', { class: 'row-text' }, element('strong', { text: c.name }), element('small', { text: shortAddr(c.address) })),
          element('button', { class: 'text-button', text: 'Edit', onclick: () => save(c.address, c.id, reopen) }),
          select ? element('button', { class: 'text-button use', text: 'Use', onclick: () => select(c.address) }) : '')),
          contacts.length ? '' : element('p', { class: 'card-note', text: 'Connected chat contacts appear here.' })),
        settingsSection('Saved addresses', ...saved.map(e => element('div', { class: 'book-row', 'data-address': e.address },
          e.favorite ? element('span', { class: 'favorite-mark', 'aria-label': 'Favorite' }, icon('star')) : '',
          contactIconView(e.name, e.icon), element('span', { class: 'row-text' }, element('strong', { text: e.name }), element('small', { text: shortAddr(e.address) + (e.category ? ` · ${e.category}` : '') })),
          element('button', { class: 'text-button', text: 'Edit', onclick: () => save(e.address, e.contact_id || '', reopen) }),
          select ? element('button', { class: 'text-button use', text: 'Use', onclick: () => select(e.address) }) : '',
          element('button', { class: 'text-button danger', text: 'Remove', 'aria-label': `Remove ${e.name}`, onclick: () => confirmRemove(e) }))),
          cache.entries.length ? '' : element('p', { class: 'card-note', text: 'Save an address from history or paste one below.' })));
    }
    function confirmRemove(e) {
      modal('Remove saved address?', [element('p', { text: `${e.name} · ${shortAddr(e.address)}` }),
        element('button', { class: 'primary-button danger-fill', text: 'Remove', onclick: () => run(null, async () => { cache = await native('book_remove', { address: e.address }); cache.entries = sorted(cache.entries); reopen(); }) }),
        element('button', { class: 'outline-button', text: 'Cancel', onclick: reopen })], 'ADDRESS BOOK');
    }
    search.addEventListener('input', () => { query = search.value; paint(); });
    const address = element('input', { id: 'book-add-address', placeholder: 'Parano1d address', autocapitalize: 'off', spellcheck: 'false', 'aria-label': 'Parano1d address' });
    const add = element('button', { class: 'outline-button', id: 'book-add', text: 'Name and save', disabled: true, onclick: () => save(address.value.trim(), '', reopen) });
    address.addEventListener('input', () => { add.disabled = !/^o1[0-9a-z]{20,126}$/.test(address.value.trim()); });
    paint();
    modal('Address book', [search, list,
      settingsSection('Add address', address, add),
      settingsSection('', settingsRow({ icon: 'tag', title: 'Contact categories', onclick: () => categories(reopen) }))], 'ADDRESS BOOK');
  }
  return { load, ensure, cached, save, show, categories, nameFor, groupInSend };
})();

/* ---------------- Connected contacts (iOS ConnectedContactsView) ---------------- */
function showConnectedContacts(select) {
  let favoritesOnly = false, query = '';
  const list = element('div', { class: 'contacts-list' });
  const search = element('input', { type: 'search', id: 'contacts-search', placeholder: 'Search name, address or identity', 'aria-label': 'Search name, address or identity' });
  const seg = (value, label) => element('button', { class: favoritesOnly === value ? 'active' : '', 'aria-pressed': String(favoritesOnly === value), onclick: () => { favoritesOnly = value; paint(); } }, value ? icon('star') : '', label);
  const segs = element('div', { class: 'segmented contacts-filter', role: 'group', 'aria-label': 'Contacts filter' });
  function paint() {
    segs.replaceChildren(seg(false, 'All contacts'), seg(true, 'Favorites'));
    const fav = Favorites.read(), q = query.trim().toLowerCase();
    const all = state?.contacts || [];
    const shown = Favorites.sort(all.filter(c => (!favoritesOnly || fav.has(c.id)) && (!q || c.name.toLowerCase().includes(q) || String(c.address || '').toLowerCase().includes(q) || c.id.includes(q))));
    list.replaceChildren(...shown.map(c => element('div', { class: 'contact-line', 'data-id': c.id },
      element('button', { class: 'contact-open', onclick: () => select ? select(c) : showContact(c) }, WarriorAvatar.node(c.id, c.name),
        element('span', { class: 'row-text' }, element('strong', { text: c.name }), element('small', { text: 'Connected by invitation' })), element('span', { class: 'settings-chevron', 'aria-hidden': 'true', text: '›' })),
      Favorites.button(c, paint))));
    if (all.length && !shown.length) list.append(element('p', { class: 'card-note', text: favoritesOnly ? 'No matching favorites. Tap a contact’s star in All contacts to add one.' : 'No matching contacts.' }),
      favoritesOnly ? element('button', { class: 'outline-button', text: 'Show all contacts', onclick: () => { favoritesOnly = false; paint(); } }) : '');
    if (!all.length) list.append(element('p', { class: 'card-note', text: 'Connect a friend’s signed wallet invitation to select them without typing an address.' }));
  }
  search.addEventListener('input', () => { query = search.value; paint(); });
  paint();
  modal(select ? 'Choose a contact' : 'Connected contacts', [
    select ? '' : settingsHero('users', 'Connect privately', 'Exchange signed invitations with a friend. Each person adds the other’s invitation on their own phone.'),
    select ? '' : element('div', { class: 'modal-actions' }, element('button', { class: 'outline-button', text: 'Share my invitation', onclick: showInvite })),
    segs, search, list], 'CONTACTS');
}

/* ---------------- NFT gallery (iOS CollectionsView) ---------------- */
function showNftGallery(initial = '') {
  let selected = initial, search = '';
  let grid = true; try { grid = localStorage.getItem('nft.gallery.grid') !== '0'; } catch { }
  const body = element('div', { class: 'gallery-body' });
  const input = element('input', { type: 'search', id: 'gallery-search', 'aria-label': 'Search', autocapitalize: 'off', spellcheck: 'false' });
  const norm = (v) => String(v || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().split(/\s+/).filter(Boolean);
  const matches = (q, name, id) => { const n = norm(name).join(' '), i = String(id).toLowerCase(); return norm(q).every(t => n.includes(t) || i.includes(t)); };
  function paint() {
    const view = assetView();
    input.placeholder = selected ? 'NFT number' : 'Collection name or ID';
    const nodes = [];
    const head = element('div', { class: 'gallery-head' },
      element('div', {}, selected ? element('button', { class: 'text-button', id: 'gallery-back', text: '‹ Collections', onclick: () => { selected = ''; search = ''; input.value = ''; paint(); } }) : element('strong', { text: 'Your collections' }),
        element('small', { text: view?.height ? `Verified inventory · block ${Number(view.height).toLocaleString()}` : assetState.loading ? 'Loading inventory…' : 'Inventory not synchronized' })),
      element('div', { class: 'segmented', role: 'group', 'aria-label': 'Layout' },
        element('button', { class: grid ? 'active' : '', 'aria-pressed': String(grid), 'aria-label': 'Grid', onclick: () => { grid = true; try { localStorage.setItem('nft.gallery.grid', '1'); } catch { } paint(); } }, icon('grid')),
        element('button', { class: grid ? '' : 'active', 'aria-pressed': String(!grid), 'aria-label': 'List', onclick: () => { grid = false; try { localStorage.setItem('nft.gallery.grid', '0'); } catch { } paint(); } }, icon('layers'))));
    nodes.push(head);
    if (!view) {
      nodes.push(element('div', { class: 'home-card empty-page' }, element('strong', { text: 'Set up tokens and NFTs on this phone' }), element('p', { text: 'Open the NFTs tab to verify your holdings first.' }),
        element('button', { class: 'outline-button', text: 'Open NFTs', onclick: () => { $('modal').close(); selectTab('nfts'); } })));
      if (!assetState.data && !assetState.loading) loadAssets().then(() => { if (body.isConnected) paint(); });
    } else if (!selected) {
      const name = (c) => mediaFor(c.metadata_hash)?.name || 'Collection';
      const ids = view.collections.filter(c => c.items.length && matches(search, name(c), c.collection_id)).sort((a, b) => name(a).localeCompare(name(b)));
      nodes.push(element('div', { class: `gallery-grid${grid ? '' : ' list'}` }, ...ids.map(c => {
        const sample = c.items[0]; const media = mediaFor(sample?.metadata_hash);
        const listed = c.items.filter(i => i.listed).length, owned = c.items.length - listed;
        return element('button', { class: 'gallery-card', 'data-collection': c.collection_id, onclick: () => { selected = c.collection_id; search = ''; input.value = ''; paint(); } },
          media?.thumbnail ? element('span', { class: 'nft-art framed' }, element('img', { class: 'nft-pic', src: media.thumbnail, alt: '' })) : element('span', { class: 'nft-art placeholder' }),
          element('span', { class: 'row-text' }, element('strong', { text: name(c) }), element('small', { text: `${owned} owned${listed ? ` · ${listed} listed` : ''}` }), element('small', { class: 'gallery-id', text: middle(c.collection_id, 8) })));
      })));
      if (!ids.length) nodes.push(element('p', { class: 'card-note', text: search ? 'No collections match.' : 'No NFTs in this wallet yet.' }));
    } else {
      const c = view.collections.find(x => x.collection_id === selected);
      const collectionName = mediaFor(c?.metadata_hash)?.name || 'Collection';
      const items = (c?.items || []).filter(i => !search || String(i.serial).includes(search.trim()) || matches(search, collectionName, selected));
      nodes.push(element('h2', { class: 'gallery-title', text: collectionName }));
      nodes.push(element('div', { class: `nft-grid${grid ? '' : ' list'}` }, ...items.map(n => assetTile(n, () => showNft(n, collectionName)))));
      if (!items.length) nodes.push(element('p', { class: 'card-note', text: 'No NFTs match.' }));
    }
    body.replaceChildren(...nodes);
    if (view && externalMedia() && repaints < 6 && view.collections.some(c => c.items.some(i => !assetState.media.has(i.metadata_hash)))) { repaints++; setTimeout(() => { if (body.isConnected) paint(); }, 1200); }
  }
  let repaints = 0;
  input.addEventListener('input', () => { search = input.value; paint(); });
  paint();
  modal('NFT gallery', [input, body], 'NFTS');
}

/* ---------------- A contact's NFTs (iOS ContactNFTGallery) ---------------- */
function showContactNfts(chosen) {
  const panel = element('div', { class: 'contact-nfts' });
  const choose = element('button', { class: 'outline-button', id: 'contact-nfts-choose', text: chosen ? `Change contact · ${chosen.name}` : 'Choose connected contact',
    onclick: () => showConnectedContacts((c) => showContactNfts(c)) });
  const nodes = [choose];
  if (chosen) nodes.push(element('p', { class: 'book-address', text: chosen.address || '' }));
  nodes.push(element('p', { class: 'card-note', text: 'Reads the saved address’s NFT holdings from this phone’s verified State. This is not proof that the contact controls the address.' }), panel);
  modal('Contact collection', nodes, 'NFTS');
  if (!chosen) return;
  if (!chosen.address) { panel.replaceChildren(element('p', { class: 'danger', text: 'This contact has no receiving address.' })); return; }
  let repaints = 0;
  const load = () => {
    panel.replaceChildren(element('p', { class: 'card-note', text: 'Loading collection…' }));
    native('contact_holdings', { address: chosen.address }).then((snapshot) => {
      if (!panel.isConnected) return;
      const items = (snapshot.items || []).filter(i => i.type === 'nft' && i.owner === chosen.address);
      const names = new Map((snapshot.collections || []).map(c => [c.collection_id, c.metadata_hash]));
      const out = [element('p', { class: 'card-note', id: 'contact-nfts-height', text: `Holdings snapshot · block ${Number(snapshot.height || 0).toLocaleString()}` })];
      const tip = Number(state?.chain?.height || 0);
      if (tip && snapshot.height && tip - snapshot.height > 10) out.push(element('p', { class: 'card-note orange', text: 'This snapshot is behind the current chain; ownership may have changed.' }));
      for (const item of items) {
        const collectionName = mediaFor(names.get(item.collection_id))?.name || 'Collection';
        const media = mediaFor(item.metadata_hash);
        out.push(element('button', { class: 'contact-nft-row', onclick: () => showNft({ ...item }, collectionName) },
          media?.thumbnail ? element('span', { class: 'nft-art framed' }, element('img', { class: 'nft-pic', src: media.thumbnail, alt: '' })) : element('span', { class: 'nft-art placeholder' }),
          element('span', { class: 'row-text' }, element('strong', { text: `NFT #${item.serial}` }), element('small', { text: collectionName }), element('small', { text: 'Holding' }))));
      }
      if (!items.length) out.push(element('p', { class: 'card-note', text: 'No unlisted NFTs were found for this address at the installed snapshot.' }));
      panel.replaceChildren(...out);
      // Names and thumbnails arrive as artwork is read; repaint once they're in.
      const pending = externalMedia() && items.some(i => !assetState.media.has(i.metadata_hash));
      if (pending && repaints++ < 4) setTimeout(() => { if (panel.isConnected) load(); }, 1500);
    }).catch((error) => {
      if (!panel.isConnected) return;
      panel.replaceChildren(element('p', { class: 'danger', text: explainError(error.message) }), element('button', { class: 'outline-button', text: 'Retry', onclick: load }));
    });
  };
  load();
}
