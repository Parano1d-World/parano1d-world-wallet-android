'use strict';

// Message requests and older messages (iOS parity):
// - 177b Message requests: messages from people who aren't contacts wait here. No notification, no
//   watch, no unread badge. Text only; payments stay closed. Names are as the sender claims.
// - R1 Older messages: long conversations keep their oldest messages on this phone, viewable here.
window.ChatRequests = (() => {
  function senders() { return state?.requests || []; }
  function plural(n, word) { return `${n} ${word}${n === 1 ? '' : 's'}`; }
  function when(ms) { return `${new Date(ms).toLocaleDateString('en', { day: 'numeric', month: 'short' })} ${time(ms)}`; }
  function close() { if ($('modal').open) $('modal').close(); }

  /** A quiet row at the top of Chats: "Message requests (n)", n = number of senders. */
  function row() {
    const n = senders().length;
    if (!n || !state?.profile) return null;
    return element('button', { class: 'conversation-row requests-row', onclick: () => openList() },
      element('span', { class: 'avatar requests-avatar' }, icon('inbox')),
      element('span', { class: 'row-text' }, element('strong', { text: `Message requests (${n})` }), element('p', { text: 'From people who aren’t your contacts' })));
  }

  function openList() {
    const rows = senders().map((r) => element('button', { class: 'conversation-row', onclick: () => void openSender(r) },
      WarriorAvatar.node(r.id, r.name),
      element('span', { class: 'row-text' }, element('strong', { text: r.name }), element('p', { text: `Unverified · ${short(r.id)} · ${plural(r.count, 'message')}` })),
      element('time', { text: time(r.last_ms) })));
    modal('Message requests', [
      element('p', { class: 'request-note', text: 'Names are as the sender claims and are not verified. Requests never notify you or reach your watch.' }),
      ...(rows.length ? rows : [element('p', { text: 'No message requests.' })]),
    ], 'REQUESTS');
  }

  async function openSender(r) {
    let detail;
    try { detail = await native('requests_open', { sender: r.id }); } catch (error) { toast(explainError(error.message)); return; }
    const items = detail.messages.map((m) => element('div', { class: 'request-message' },
      element('time', { text: when(m.created_ms) }),
      m.hidden ? element('p', { class: 'request-hidden', text: m.hidden }) : element('p', { text: m.text })));
    const after = async (message) => { render(); if (senders().length) openList(); else close(); toast(message); };
    modal(r.name, [
      element('p', { class: 'request-note', text: `Unverified · ${short(r.id)}` }),
      ...items,
      element('p', { class: 'request-note', text: 'To chat, add this person from their own contact invitation (QR code or link). Their messages then move into Chats.' }),
      element('div', { class: 'request-actions' },
        element('button', { class: 'outline-button', text: 'Delete', onclick: (event) => run(event.currentTarget, async () => { state = await native('requests_delete', { sender: r.id }); await after('Requests deleted.'); }) }),
        element('button', { class: 'outline-button danger', text: 'Block and delete', onclick: (event) => run(event.currentTarget, async () => { state = await native('requests_block', { sender: r.id }); await after('Blocked. New messages from them are dropped.'); }) })),
    ], 'REQUESTS');
  }

  function olderCount(key) { return Number(state?.older_counts?.[key] || 0); }

  async function openOlder(key) {
    let result;
    try { result = await native('chat_older', { chat: key }); } catch (error) { toast(explainError(error.message)); return; }
    const list = result.messages.slice().sort((a, b) => a.created_ms - b.created_ms || a.id.localeCompare(b.id));
    const items = list.map((m) => element('div', { class: 'request-message' },
      element('time', { text: `${m.sender?.name || ''} · ${when(m.created_ms)}` }),
      element('p', { text: ChatUI.preview(m.message) }),
      element('button', { class: 'outline-button older-delete', text: 'Delete', 'aria-label': 'Delete this older message', onclick: (event) => run(event.currentTarget, async () => {
        state = await native('chat_older_delete', { id: m.id }); render();
        if (olderCount(key)) await openOlder(key); else close();
      }) })));
    modal(`Older messages (${list.length})`, [
      element('p', { class: 'request-note', text: 'Long conversations keep their oldest messages here, on this phone. Nothing is deleted automatically.' }),
      ...(items.length ? items : [element('p', { text: 'No older messages.' })]),
    ], 'OLDER');
  }

  return { row, openList, olderCount, openOlder };
})();
