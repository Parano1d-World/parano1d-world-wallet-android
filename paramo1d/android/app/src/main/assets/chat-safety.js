'use strict';
// Chat safety, matching iOS ChatSafety / PublicChatSafety / PublicChatReport:
// one-time rules agreement, on-device blocking, deterministic screening of known
// abusive phrases, public posting limits, and user-sent email reports to support.
// Nothing here is uploaded; blocking and screening run only on this phone.
const ChatSafety = (() => {
  const RECIPIENT = 'support@koajent.com';
  const REASONS = ['Harassment or threats', 'Scam or impersonation', 'Hateful or sexual content', 'Spam', 'Other'];
  const PHRASES = ['kill yourself', 'i will kill you', 'i am going to kill you', 'you should kill yourself', 'i will rape you', 'send nudes', 'suck my dick'];
  const RULES = [
    ['No threats, harassment or hate', 'Abusive messages can be reported and their senders blocked.'],
    ['No scams or impersonation', 'Never ask for or share recovery keys, passwords or private information.'],
    ['No sexual or violent content', 'Messages with known abusive phrases are hidden until you choose to view them.'],
    ['Report concerns', `Reports go to ${RECIPIENT}. We review them and act on accounts that break these rules.`]];
  const revealed = new Set();
  // Owner-signed moderation list (verified on the phone; same feed and rules as iOS). Public room only.
  const REASON_LABELS = { harassment: 'Harassment', threats: 'Threats', 'hateful-content': 'Hateful content', 'sexual-content': 'Sexually explicit content', scam: 'Scam or impersonation', spam: 'Spam', privacy: 'Exposure of private information' };
  function restriction(messageId, identity) {
    return (state?.moderation?.decisions || []).find(d => (d.kind === 'identity' && d.target === identity) || (d.kind === 'message' && d.target === messageId)) || null;
  }
  let lastModerationAsk = 0;
  function refreshModeration() {
    if (!state?.moderation?.enabled || Date.now() - lastModerationAsk < 60000) return;
    lastModerationAsk = Date.now();
    native('moderation_refresh').then(next => { state = next; render(); }).catch(() => {});
  }
  const sentPublic = [];
  const safety = () => state?.chat_safety || { blocked: [], order: [], rules_accepted: false };

  function normalized(text) {
    return String(text || '').normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean).join(' ');
  }
  function contentReason(text) {
    const padded = ` ${normalized(text)} `;
    return PHRASES.some(p => padded.includes(` ${p} `)) ? 'Potentially abusive or sexually explicit text' : null;
  }
  function outgoingReason(scope, text) {
    const reason = contentReason(text) || contentReason(state?.profile?.name);
    if (reason) return `${reason}. Edit it before sending.`;
    if (!scope.startsWith('g:')) return null;
    const own = restriction('', state?.profile?.id);
    if (own) return `This identity can't post in #parano1d (${REASON_LABELS[own.reason] || 'Community rules'}). Appeals: ${RECIPIENT}`;
    const now = Date.now(), recent = sentPublic.filter(e => now - e.time < 60000);
    if (recent.length >= 5) return 'Public posting limit reached: five messages per minute. Please wait.';
    if (recent.some(e => e.text === normalized(text))) return 'This repeats a public message sent in the last minute. Please wait or change it.';
    return null;
  }
  function recordSent(scope, text) {
    if (!scope.startsWith('g:')) return;
    sentPublic.push({ text: normalized(text), time: Date.now() });
    while (sentPublic.length > 6) sentPublic.shift();
  }
  const isBlocked = id => !!id && safety().blocked.includes(id);
  async function save(change) {
    state = await native('chat_safety_set', change); render();
  }
  async function block(id) { if (!isBlocked(id)) await save({ blocked: [...safety().blocked, id] }); }
  async function unblock(id) { await save({ blocked: safety().blocked.filter(x => x !== id) }); }
  function visible(messages) {
    const own = state?.profile?.id;
    return messages.filter(m => m.sender?.id === own || !isBlocked(m.sender?.id));
  }
  /** Hidden-until-tapped wrapper for screened text; returns null when the message is fine. */
  function screen(item) {
    if (item.chat?.startsWith('g:')) {
      const decision = restriction(item.id, item.sender?.id);
      if (decision) return element('div', { class: 'screened-message moderated' }, element('strong', { text: 'Removed by Parano1d moderation' }),
        element('small', { text: `${REASON_LABELS[decision.reason] || 'Community rules'}. Appeals: ${RECIPIENT}` }));
    }
    if (item.sender?.id === state?.profile?.id || revealed.has(item.id) || item.message?.type !== 'text') return null;
    const reason = contentReason(item.message.text) || contentReason(item.sender?.name);
    if (!reason) return null;
    return element('button', { class: 'screened-message', onclick: event => { event.stopPropagation(); revealed.add(item.id); render(); } },
      element('strong', { text: 'Message hidden' }), element('small', { text: `${reason}. Tap to view.` }));
  }

  // ------------------------------------------------------------ rules gate (cannot be dismissed without agreeing)
  function requireRules() {
    if (!state?.profile || safety().rules_accepted || $('modal').open && $('modal').dataset.kind === 'CHAT RULES') return !!safety().rules_accepted;
    const agree = element('button', { class: 'primary-button', text: 'I agree', onclick: () => run(agree, async () => { await save({ rules_accepted: true }); $('modal').close(); }) });
    modal('Chat rules', [
      element('p', { text: 'Private chats are end-to-end encrypted between connected contacts. To keep chat safe, you agree to these rules.' }),
      ...RULES.map(([title, detail]) => element('div', { class: 'rule-row' }, element('span', { class: 'rule-dot' }), element('span', {}, element('strong', { text: title }), element('small', { text: detail })))),
      element('p', { class: 'card-note', text: 'There is no tolerance for objectionable content or abusive users.' }), agree], 'CHAT RULES');
    $('modal').classList.add('locked-modal');
    return false;
  }

  function showRules() {
    modal('Chat rules', [...RULES.map(([title, detail]) => element('div', { class: 'rule-row' }, element('span', { class: 'rule-dot' }), element('span', {}, element('strong', { text: title }), element('small', { text: detail })))),
      element('p', { class: 'card-note', text: 'There is no tolerance for objectionable content or abusive users.' })], 'CHAT RULES');
  }

  // ------------------------------------------------------------ report (email the person sends themselves)
  function report(item, room) {
    const isPrivate = room !== '#parano1d', target = item.sender?.name ? `${item.sender.name} (${String(item.sender.id).slice(0, 12)}…)` : 'message';
    const subject = (isPrivate ? 'Parano1d private chat — report ' : 'Parano1d public chat — report ') + target;
    const text = item.message?.type === 'text' ? ChatText.parse(item.message.text).text : `[${item.message?.type || 'message'}]`;
    const body = reason => [isPrivate ? 'Private chat report' : 'Public chat report', `Room: ${room}`, `Target: ${target}`, `Reason: ${reason}`,
      `Message ID: ${item.id}`, `Sender identity: ${item.sender?.id}`, `Display name (unverified): ${item.sender?.name}`, `Message timestamp: ${new Date(item.created_ms).toISOString()}`, '',
      'Selected message shared by the reporter (user-provided evidence; independently verify):', '--- BEGIN MESSAGE ---', text, '--- END MESSAGE ---', '',
      'Please review this report. It is not proof of misconduct.'].join('\n');
    const select = element('select', { 'aria-label': 'Reason' }, ...REASONS.map(r => element('option', { value: r, text: r })));
    const preview = element('pre', { class: 'report-preview' });
    const outcome = element('p', { class: 'card-note', role: 'status' });
    const update = () => { preview.textContent = `${subject}\n\n${body(select.value)}`; };
    select.addEventListener('change', update); update();
    const email = element('button', { class: 'primary-button', text: 'Continue to email', onclick: () => run(email, async () => {
      await native('report_email', { subject, body: body(select.value) }); outcome.textContent = 'Your email app is open. The report is sent only if you send it there.'; }) });
    const copyReport = element('button', { class: 'outline-button', text: 'Copy report', onclick: () => { copy(`${subject}\n\n${body(select.value)}`); outcome.textContent = 'Report copied. Send it using your email app; it has not been submitted.'; } });
    const own = item.sender?.id === state?.profile?.id;
    const blockButton = element('button', { class: 'outline-button danger', text: isBlocked(item.sender?.id) ? 'User blocked on this device' : 'Block this user', onclick: () => run(blockButton, async () => {
      await block(item.sender.id); blockButton.textContent = 'User blocked on this device'; blockButton.disabled = true;
      outcome.textContent = 'User blocked on this device. You can unblock them in Chat options. No report was sent by blocking.'; }) });
    blockButton.disabled = own || isBlocked(item.sender?.id);
    modal(`Report ${isPrivate ? 'private' : 'public'} chat`, [
      element('label', { class: 'field-label' }, 'Reason', select),
      element('p', { class: 'card-note', text: `To: ${RECIPIENT}. You choose whether to send it. Your email account identifies you to support. Only the message below is added; no other conversation, wallet key or history is attached.` }),
      preview, email, copyReport, outcome, own ? element('span') : blockButton,
      element('p', { class: 'card-note', text: 'Reporting does not automatically remove a post or block its author. Support must review it.' })], 'REPORT');
  }

  // ------------------------------------------------------------ blocked list and chat order
  function blockedList() {
    const ids = safety().blocked;
    const rows = ids.map(id => {
      const peer = contact(id);
      const undo = element('button', { class: 'outline-button', text: 'Unblock', onclick: () => run(undo, async () => { await unblock(id); blockedList(); }) });
      return element('div', { class: 'review-row' }, element('span', {}, element('strong', { text: peer?.name || 'Contact' }), element('small', { class: 'code', text: `${id.slice(0, 16)}…` })), undo);
    });
    modal('Blocked contacts', [...(rows.length ? rows : [element('p', { text: 'No blocked contacts.' })]),
      element('p', { class: 'card-note', text: 'Messages from blocked contacts are not shown and you cannot message them. Blocking is stored only on this device.' })], 'CHAT SAFETY');
  }
  function ordered(conversations) {
    const order = safety().order, rank = new Map(order.map((k, i) => [k, i]));
    return conversations.slice().sort((a, b) => (rank.has(a.key) ? rank.get(a.key) : 1e9) - (rank.has(b.key) ? rank.get(b.key) : 1e9));
  }
  async function move(keys, key, delta) {
    const list = keys.slice(), at = list.indexOf(key), to = Math.max(0, Math.min(list.length - 1, at + delta));
    if (at < 0 || at === to) return;
    list.splice(to, 0, ...list.splice(at, 1));
    await save({ order: list });
  }
  /** Long-press and drag a conversation to reorder (iOS 165 "drag to reorder chats"). */
  function draggable(listNode) {
    let drag = null, timer = 0;
    const items = () => [...listNode.querySelectorAll('[data-chat-key]')];
    listNode.addEventListener('pointerdown', event => {
      const item = event.target.closest('[data-chat-key]'); if (!item || event.button !== 0) return;
      const start = event.clientY;
      timer = setTimeout(() => { drag = { item, start, pointer: event.pointerId }; item.classList.add('dragging'); try { item.setPointerCapture(event.pointerId); } catch {} navigator.vibrate?.(15); }, 420);
      const cancel = () => clearTimeout(timer);
      item.addEventListener('pointerup', cancel, { once: true }); item.addEventListener('pointercancel', cancel, { once: true });
      item.addEventListener('pointermove', e => { if (!drag && Math.abs(e.clientY - start) > 8) cancel(); }, { passive: true });
    });
    listNode.addEventListener('pointermove', event => {
      if (!drag) return; event.preventDefault();
      drag.item.style.transform = `translateY(${event.clientY - drag.start}px)`;
    });
    const finish = async event => {
      if (!drag) return;
      const { item, start } = drag; drag = null;
      const dy = event.clientY - start, all = items(), height = item.getBoundingClientRect().height || 72;
      item.classList.remove('dragging'); item.style.transform = '';
      const shift = Math.round(dy / height);
      if (shift) { event.preventDefault(); suppressClick(item); await run(null, () => move(all.map(n => n.dataset.chatKey), item.dataset.chatKey, shift)); }
    };
    listNode.addEventListener('pointerup', finish); listNode.addEventListener('pointercancel', finish);
  }
  function suppressClick(node) { node.addEventListener('click', e => { e.stopPropagation(); e.preventDefault(); }, { capture: true, once: true }); }

  return { refreshModeration, restriction, showRules, contentReason, outgoingReason, recordSent, isBlocked, block, unblock, visible, screen, requireRules, report, blockedList, ordered, move, draggable };
})();
