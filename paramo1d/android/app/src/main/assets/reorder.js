'use strict';
// iOS 200 / App Store 1.0.0 (24): drag-to-reorder lists for wallets, accounts and mining computers
// (SwiftUI List with .onMove, and .onDelete for computers). Drag a row by its handle; the handle also
// answers the arrow keys, and Move up / Move down are offered to screen readers.
const ReorderList = (() => {
  /**
   * open({ title, eyebrow, note, items: [{ id, title, subtitle, icon }], mode: 'save' | 'live',
   *        onSave(ids), onDelete(item) })
   * mode 'save' (wallets): Cancel and Save; nothing changes until Save.
   * mode 'live' (accounts, computers): each move is saved at once; Done closes.
   */
  function open(options) {
    let items = options.items.slice();
    const list = element('ol', { class: 'reorder-list', 'aria-label': options.title });
    const save = async () => { if (options.mode === 'live') await options.onSave(items.map((x) => x.id)); };
    function move(from, to) {
      if (to < 0 || to >= items.length || from === to) return false;
      const [item] = items.splice(from, 1); items.splice(to, 0, item); return true;
    }
    function row(item, index) {
      const handle = element('button', { class: 'reorder-handle', 'aria-label': `Move ${item.title}. Drag, or use the arrow keys.`, 'data-id': item.id }, icon('menu'));
      const li = element('li', { class: 'reorder-row', 'data-id': item.id },
        options.onDelete ? element('button', { class: 'reorder-delete', 'aria-label': `Delete ${item.title}`, onclick: () => options.onDelete(item) }, element('span', { 'aria-hidden': 'true', text: '−' })) : '',
        item.icon ? settingsIcon(item.icon) : '',
        element('span', { class: 'row-text' }, element('strong', { text: item.title }), item.subtitle ? element('small', { class: 'addr', text: item.subtitle }) : ''),
        handle,
        element('span', { class: 'sr-only' },
          index > 0 ? element('button', { text: `Move ${item.title} up`, onclick: () => { if (move(index, index - 1)) commit(item.id); } }) : '',
          index + 1 < items.length ? element('button', { text: `Move ${item.title} down`, onclick: () => { if (move(index, index + 1)) commit(item.id); } }) : ''));
      handle.addEventListener('keydown', (e) => {
        const by = e.key === 'ArrowUp' ? -1 : e.key === 'ArrowDown' ? 1 : 0; if (!by) return;
        e.preventDefault(); const at = items.findIndex((x) => x.id === item.id); if (move(at, at + by)) commit(item.id);
      });
      handle.addEventListener('pointerdown', (e) => drag(e, li, item));
      return li;
    }
    async function commit(focusId) {
      paint();
      list.querySelector(`.reorder-handle[data-id="${CSS.escape(focusId)}"]`)?.focus();
      try { await save(); } catch (error) { toast(explainError(error.message)); }
    }
    function paint() { list.replaceChildren(...items.map(row)); }
    function drag(e, li, item) {
      e.preventDefault();
      const handle = e.currentTarget; handle.setPointerCapture?.(e.pointerId);
      const startY = e.clientY, start = items.findIndex((x) => x.id === item.id);
      const rows = [...list.children], heights = rows.map((r) => r.getBoundingClientRect());
      let target = start;
      li.classList.add('dragging');
      const onMove = (ev) => {
        const dy = ev.clientY - startY; li.style.transform = `translateY(${dy}px)`;
        const mid = heights[start].top + heights[start].height / 2 + dy;
        target = start; heights.forEach((r, i) => { if (i < start && mid <= r.top + r.height / 2) target = Math.min(target, i); if (i > start && mid >= r.top + r.height / 2) target = Math.max(target, i); });
        const size = heights[start].height + 8;
        rows.forEach((r, i) => { if (r === li) return; r.style.transform = (i >= target && i < start) ? `translateY(${size}px)` : (i <= target && i > start) ? `translateY(${-size}px)` : ''; });
      };
      const onUp = () => {
        handle.removeEventListener('pointermove', onMove); handle.removeEventListener('pointerup', onUp); handle.removeEventListener('pointercancel', onUp);
        rows.forEach((r) => { r.style.transform = ''; }); li.classList.remove('dragging');
        if (move(start, target)) commit(item.id); else paint();
      };
      handle.addEventListener('pointermove', onMove); handle.addEventListener('pointerup', onUp); handle.addEventListener('pointercancel', onUp);
    }
    paint();
    const buttons = options.mode === 'save'
      ? [element('button', { class: 'primary-button', id: 'reorder-save', text: 'Save', onclick: (ev) => run(ev.currentTarget, async () => { await options.onSave(items.map((x) => x.id)); }) }),
         element('button', { class: 'outline-button', id: 'reorder-cancel', text: 'Cancel', onclick: () => (options.onCancel ? options.onCancel() : $('modal').close()) })]
      : [element('button', { class: 'primary-button', id: 'reorder-done', text: 'Done', onclick: () => (options.onDone ? options.onDone() : $('modal').close()) })];
    modal(options.title, [options.note ? element('p', { class: 'card-note', text: options.note }) : '', list, ...buttons], options.eyebrow || 'REORDER');
    return { refresh(next) { items = next.slice(); paint(); } };
  }
  return { open };
})();
