'use strict';
// Android runs the page with DOM storage off. Small page settings (send layout, favourite contacts, avatars, the
// Mining tab, gallery layout …) are kept by the app instead, in an encrypted file (UiStore.java). This gives the page
// a localStorage with the same methods, read once at start and written through on every change.
(() => {
  let existing = null;
  try { existing = window.localStorage; } catch { existing = null; }
  if (existing) return;
  const bridge = window.Paramo1d;
  if (!bridge || typeof bridge.uiStoreAll !== 'function') return;
  let data = {};
  try { const parsed = JSON.parse(bridge.uiStoreAll() || '{}'); if (parsed && typeof parsed === 'object') data = parsed; } catch { data = {}; }
  const own = (key) => Object.prototype.hasOwnProperty.call(data, key);
  const store = {
    getItem(key) { key = String(key); return own(key) ? String(data[key]) : null; },
    setItem(key, value) { key = String(key); value = String(value); data[key] = value; try { bridge.uiStoreSet(key, value); } catch { } },
    removeItem(key) { key = String(key); if (!own(key)) return; delete data[key]; try { bridge.uiStoreRemove(key); } catch { } },
    clear() { for (const key of Object.keys(data)) this.removeItem(key); },
    key(index) { return Object.keys(data)[index] ?? null; },
    get length() { return Object.keys(data).length; },
  };
  try { Object.defineProperty(window, 'localStorage', { value: store, configurable: true, enumerable: true }); } catch { }
})();
