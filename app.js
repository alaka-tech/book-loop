const $ = s => document.querySelector(s), app = $('#app');
const S = { t: localStorage.t || '', role: localStorage.role || '', status: localStorage.status || '', view: 'browse', id: 0, pid: 0, soon: 0 };
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const money = b => b.type === 'SELL' ? '₹' + b.price : b.type === 'BORROW' ? 'Free to borrow' : 'Exchange';
let tt; const toast = m => { const t = $('#toast'); t.textContent = m; t.className = 'show'; clearTimeout(tt); tt = setTimeout(() => t.className = '', 3200) };

async function api(p, m = 'GET', b) {
  const r = await fetch('/api/' + p, { method: m, headers: { 'Content-Type': 'application/json', 'X-Token': S.t }, body: b ? JSON.stringify(b) : undefined });
  const d = await r.json().catch(() => ({}));
  if (!r.ok) { if (r.status === 401 && S.t) logout(); toast(d.message || 'Something went wrong'); throw new Error(d.message) }
  return d;
}
// Resize an image file to a small JPEG data-URL before upload
const img = (f, w) => new Promise(res => {
  if (!f) return res(null);
  const r = new FileReader();
  r.onload = () => { const i = new Image(); i.onload = () => { const k = Math.min(1, w / i.width), c = document.createElement('canvas'); c.width = i.width * k; c.height = i.height * k; c.getContext('2d').drawImage(i, 0, 0, c.width, c.height); res(c.toDataURL('image/jpeg', .72)) }; i.src = r.result };
  r.readAsDataURL(f);
});
const left = d => Math.ceil((new Date(d + 'T00:00:00') - new Date().setHours(0, 0, 0, 0)) / 864e5);
const form = f => Object.fromEntries(new FormData(f));
const logout = () => { localStorage.clear(); S.t = S.role = S.status = ''; render() };
const go = v => { S.view = v; if (v === 'profile') S.pid = S.id; render() };

async function render() {
  if (S.t) { try { const u = await api('me'); S.status = u.status; S.role = u.role; S.id = u.id; S.soon = (await api('borrowings')).filter(w => !w.returned && w.borrower_id == S.id && left(w.due_date) >= 0 && left(w.due_date) <= 2).length } catch { return } }
  $('#banner').textContent = S.t && S.status !== 'VERIFIED' && S.role !== 'ADMIN'
    ? (S.status === 'REJECTED' ? 'Your college ID was rejected. Contact the admin.' : 'Your college ID is awaiting verification. You can browse now and list or request books once approved.') : '';
  $('#banner').textContent = [$('#banner').textContent, S.soon ? `⏰ Reminder: ${S.soon} borrowed book(s) due within 2 days. Please return on time.` : ''].filter(Boolean).join('  ');
  const items = [['browse', 'Browse'], ['sell', 'List a book'], ['activity', 'My activity'], ['alerts', 'Alerts'], ['profile', 'My profile']];
  if (S.role === 'ADMIN') items.push(['admin', 'Admin']);
  $('#nav').innerHTML = S.t ? items.map(([k, v]) => `<button data-go="${k}" class="${S.view === k ? 'on' : ''}">${v}</button>`).join('') + '<button data-a="logout">Log out</button>' : '';
  (S.t ? V[S.view] : V.auth)();
}

const V = {};
V.auth = (mode = 'login') => {
  app.innerHTML = `<h1>Books that stay in the college.</h1><p class="sub">Buy, sell, swap or borrow academic books with verified students.</p>
  <div class="tabs"><button class="btn ${mode == 'login' ? '' : 'alt'}" data-a="tab" data-m="login">Log in</button><button class="btn ${mode == 'login' ? 'alt' : ''}" data-a="tab" data-m="register">Create account</button></div>
  ${mode == 'login' ? `<form class="stack" id="f"><label>College email<input name="email" type="email" required></label><label>Password<input name="password" type="password" required></label><button class="btn">Log in</button></form>`
    : `<form class="stack" id="f"><label>Full name<input name="name" required></label><label>College email<input name="email" type="email" required></label><label>Phone<input name="phone" required></label>
    <label>Department<input name="department" required></label><label>Semester<select name="semester">${[1, 2, 3, 4, 5, 6, 7, 8].map(n => `<option>${n}</option>`).join('')}</select></label>
    <label>Password<input name="password" type="password" minlength="6" required></label><label>College ID card photo<input name="idfile" type="file" accept="image/*" required></label><button class="btn">Create account</button></form>`}`;
  $('#f').onsubmit = async e => {
    e.preventDefault(); const d = form(e.target);
    if (mode == 'login') { const r = await api('login', 'POST', d); localStorage.t = S.t = r.token; localStorage.role = r.role; S.view = 'browse'; render() }
    else { d.idCard = await img(e.target.idfile.files[0], 900); delete d.idfile; await api('register', 'POST', d); toast('Account created. An admin will verify your ID.'); V.auth('login') }
  };
};

V.browse = async () => {
  app.innerHTML = `<h1>Find a book</h1><p class="sub">Only books available right now are shown.</p>
  <form id="f" class="filters"><input name="q" placeholder="Search title or author"><input name="subject" placeholder="Subject"><input name="department" placeholder="Department">
  <select name="semester"><option value="">Any semester</option>${[1, 2, 3, 4, 5, 6, 7, 8].map(n => `<option>${n}</option>`).join('')}</select>
  <select name="type"><option value="">Buy, swap or borrow</option><option value="SELL">Buy</option><option value="EXCHANGE">Exchange</option><option value="BORROW">Borrow</option></select>
  <input name="maxPrice" type="number" min="0" placeholder="Max price"><button class="btn">Search</button></form><div id="g" class="grid"></div>`;
  const f = $('#f'), load = async () => {
    const p = new URLSearchParams(new FormData(f)); for (const [k, v] of [...p]) if (!v) p.delete(k);
    const bs = await api('books?' + p);
    $('#g').innerHTML = bs.length ? bs.map(b => `<article class="book ${b.type}"><div class="cover" ${b.cover ? `style="background-image:url('${b.cover}')"` : ''}>${b.cover ? '' : esc(b.title)}</div>
      <div class="in"><h3>${esc(b.title)}</h3><span class="meta">${esc(b.author)}</span><span class="meta">${esc(b.subject)} · Sem ${b.semester} · ${esc(b.department)} · ${esc(b.book_condition)}</span>
      <span class="tag">${money(b)}</span><span class="meta">by <a href="#" data-a="prof" data-id="${b.owner_id}">${esc(b.owner)}</a></span>
      <div class="row"><button class="btn sm" data-a="req" data-id="${b.id}">${b.type == 'SELL' ? 'Request to buy' : b.type == 'BORROW' ? 'Request to borrow' : 'Request exchange'}</button><button class="btn sm alt" data-a="report" data-id="${b.id}">Report</button></div></div></article>`).join('')
      : '<p class="empty">No books match. Try fewer filters, or list one yourself.</p>';
  };
  f.onsubmit = e => { e.preventDefault(); load() }; load();
};

V.sell = () => {
  app.innerHTML = `<h1>List a book</h1><p class="sub">Sell it, swap it, or lend it to a classmate.</p>
  <form class="stack" id="f"><label>Title<input name="title" required></label><label>Author<input name="author" required></label><label>Subject<input name="subject" required></label>
  <label>Department<input name="department" required></label><label>Semester<select name="semester">${[1, 2, 3, 4, 5, 6, 7, 8].map(n => `<option>${n}</option>`).join('')}</select></label>
  <label>Condition<select name="book_condition"><option>NEW</option><option selected>GOOD</option><option>FAIR</option><option>WORN</option></select></label>
  <label>I want to<select name="type" id="ty"><option value="SELL">Sell</option><option value="EXCHANGE">Exchange</option><option value="BORROW">Lend</option></select></label>
  <label id="pr">Price (₹)<input name="price" type="number" min="0" value="0"></label><label>Cover photo<input name="coverfile" type="file" accept="image/*"></label><button class="btn">Publish listing</button></form>`;
  $('#ty').onchange = e => $('#pr').style.display = e.target.value == 'SELL' ? '' : 'none';
  $('#f').onsubmit = async e => { e.preventDefault(); const d = form(e.target); d.cover = await img(e.target.coverfile.files[0], 400); delete d.coverfile; await api('books', 'POST', d); toast('Listing published'); go('browse') };
};

V.activity = async () => {
  const [rq, bw, mine] = await Promise.all([api('requests'), api('borrowings'), api('books/mine')]);
  const reqRow = (r, inc) => `<div class="item"><span><b>${esc(r.title)}</b> · ${r.type.toLowerCase()} · ${inc ? 'from' : 'to'} <a href="#" data-a="prof" data-id="${r.other_id}">${esc(r.other)}</a> · <i>${r.status.toLowerCase()}</i></span><span>
    ${inc && r.status == 'PENDING' ? `<button class="btn sm" data-a="resp" data-ok="1" data-id="${r.id}" data-type="${r.type}">Accept</button> <button class="btn sm alt" data-a="resp" data-ok="0" data-id="${r.id}">Decline</button>` : ''}
    ${r.status == 'ACCEPTED' ? `<button class="btn sm alt" data-a="prof" data-id="${r.other_id}">Review ${esc(r.other)}</button>` : ''}</span></div>`;
  const empty = '<p class="empty">Nothing here yet.</p>';
  app.innerHTML = `<h1>My activity</h1><h2>Requests for my books</h2><div class="list">${rq.incoming.map(r => reqRow(r, 1)).join('') || empty}</div>
  <h2>My requests</h2><div class="list">${rq.outgoing.map(r => reqRow(r, 0)).join('') || empty}</div>
  <h2>Borrowings</h2><div class="list">${bw.map(w => `<div class="item"><span><b>${esc(w.title)}</b> · ${esc(w.owner)} → ${esc(w.borrower)} · due ${w.due_date} ${w.returned ? '· returned' : w.overdue ? '<span class="late">· overdue</span>' : left(w.due_date) <= 2 ? `<span class="late">· due in ${left(w.due_date)} day(s)</span>` : ''}</span>
    ${!w.returned && S.role !== undefined ? `<button class="btn sm" data-a="ret" data-id="${w.id}">Mark returned</button>` : ''}</div>`).join('') || empty}</div>
  <h2>My listings</h2><div class="list">${mine.map(b => `<div class="item"><span><b>${esc(b.title)}</b> · ${b.type.toLowerCase()} · ${b.availability.toLowerCase()}</span>${b.availability == 'AVAILABLE' ? `<button class="btn sm bad" data-a="rm" data-id="${b.id}">Remove</button>` : ''}</div>`).join('') || empty}</div>`;
};

V.alerts = async () => {
  const n = await api('notifications');
  app.innerHTML = `<h1>Alerts</h1><div class="list">${n.map(x => `<div class="item ${x.is_read ? '' : 'unread'}"><span>${esc(x.message)}</span><small>${new Date(x.created).toLocaleDateString()}</small></div>`).join('') || '<p class="empty">No alerts yet.</p>'}</div>`;
};

V.admin = async () => {
  const [st, pd, rp] = await Promise.all([api('admin/stats'), api('admin/pending'), api('admin/reports')]);
  app.innerHTML = `<h1>Admin</h1><div class="stats"><div><b>${st.students}</b>students</div><div><b>${st.books}</b>listings</div><div><b>${st.completed}</b>completed deals</div><div><b>${st.overdue}</b>overdue returns</div></div>
  <h2>ID verification</h2><div class="list">${pd.map(s => `<div class="item"><span><b>${esc(s.name)}</b> · ${esc(s.email)} · ${esc(s.department)} sem ${s.semester}<br><img class="idcard" src="${esc(s.id_card)}" alt="ID card"></span>
    <span><button class="btn sm" data-a="vf" data-ok="1" data-id="${s.id}">Approve</button> <button class="btn sm bad" data-a="vf" data-ok="0" data-id="${s.id}">Reject</button></span></div>`).join('') || '<p class="empty">No pending verifications.</p>'}</div>
  <h2>Reported listings</h2><div class="list">${rp.map(r => `<div class="item"><span><b>${esc(r.title)}</b> · ${esc(r.reason)} · by ${esc(r.reporter)}</span>
    <span><button class="btn sm bad" data-a="rs" data-rm="1" data-id="${r.id}">Remove listing</button> <button class="btn sm alt" data-a="rs" data-rm="0" data-id="${r.id}">Dismiss</button></span></div>`).join('') || '<p class="empty">No open reports.</p>'}</div>`;
};

V.profile = async () => {
  const p = await api('profile/' + S.pid), me = p.id == S.id, st = n => '★'.repeat(Math.round(n)) + '☆'.repeat(5 - Math.round(n));
  const sems = n => [1, 2, 3, 4, 5, 6, 7, 8].map(k => `<option ${k == n ? 'selected' : ''}>${k}</option>`).join('');
  app.innerHTML = `<div class="prof"><div class="avatar">${esc(p.name[0])}</div><div><h1>${esc(p.name)}</h1><p class="sub">${esc(p.department)} · Semester ${p.semester} · ${p.status == 'VERIFIED' ? 'Verified student' : 'Not verified yet'}${p.phone ? ' · ' + esc(p.phone) : ''}</p></div></div>
  <div class="stats"><div><b class="star">${p.count ? st(p.avg) : '–'}</b>${p.count ? p.avg + ' average from ' + p.count + ' review(s)' : 'No reviews yet'}</div><div><b>${p.listings}</b>books available</div><div><b>${p.deals}</b>completed deals</div></div>
  ${me ? `<h2>Edit my profile</h2><form class="stack" id="pf"><label>Name<input name="name" value="${esc(p.name)}" required></label><label>Phone<input name="phone" value="${esc(p.phone)}" required></label><label>Department<input name="department" value="${esc(p.department)}" required></label><label>Semester<select name="semester">${sems(p.semester)}</select></label><button class="btn">Save profile</button></form>`
  : p.deal ? `<h2>${p.mine ? 'Update your review of ' : 'Review '}${esc(p.name)}</h2><form class="stack" id="rf"><label>Rating<select name="rating">${[5, 4, 3, 2, 1].map(n => `<option value="${n}" ${p.mine && p.mine.rating == n ? 'selected' : ''}>${n} – ${['', 'Poor', 'Fair', 'Good', 'Very good', 'Excellent'][n]}</option>`).join('')}</select></label><label>Feedback<textarea name="feedback" rows="3" placeholder="How was the handover? Was the book as described?">${esc(p.mine ? p.mine.feedback : '')}</textarea></label><button class="btn">Save review</button></form>`
  : '<p class="sub">You can review this student after a completed deal with them.</p>'}
  <h2>Reviews</h2><div class="list">${p.reviews.map(v => `<div class="item"><span><b class="star">${st(v.rating)}</b> ${esc(v.feedback)}<br><small>${esc(v.reviewer)} · ${new Date(v.created).toLocaleDateString()}</small></span></div>`).join('') || '<p class="empty">No reviews yet.</p>'}</div>`;
  if (me) $('#pf').onsubmit = async e => { e.preventDefault(); await api('profile', 'PUT', form(e.target)); toast('Profile saved'); render() };
  else if (p.deal) $('#rf').onsubmit = async e => { e.preventDefault(); const d = form(e.target); await api('reviews', 'POST', { userId: p.id, rating: +d.rating, feedback: d.feedback }); toast('Review saved'); render() };
};

const A = {
  logout, tab: d => V.auth(d.m),
  req: async d => { await api('requests', 'POST', { bookId: +d.id }); toast('Request sent to the owner') },
  report: async d => { const reason = prompt('What is wrong with this listing?'); if (reason) { await api('reports', 'POST', { bookId: +d.id, reason }); toast('Report sent to the admin') } },
  resp: async d => { const days = d.ok == '1' && d.type == 'BORROW' ? +prompt('Lend for how many days?', '14') : null; await api(`requests/${d.id}/respond`, 'POST', { accept: d.ok == '1', days }); render() },
  ret: async d => { await api(`borrowings/${d.id}/return`, 'POST'); render() },
  rm: async d => { await api('books/' + d.id, 'DELETE'); render() },
  prof: d => { S.pid = +d.id; S.view = 'profile'; render() },
  vf: async d => { await api('admin/verify/' + d.id, 'POST', { ok: d.ok == '1' }); render() },
  rs: async d => { await api(`admin/reports/${d.id}/resolve`, 'POST', { remove: d.rm == '1' }); render() },
};
document.addEventListener('click', e => {
  const g = e.target.closest('[data-go]'); if (g) return go(g.dataset.go);
  const a = e.target.closest('[data-a]'); if (a) { e.preventDefault(); A[a.dataset.a](a.dataset) }
});
render();