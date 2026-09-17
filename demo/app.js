(() => {
  const model = window.HealthDemo;
  let state = model.createState(), range = 7, toastTimer, returnFocus;
  const content = document.getElementById('content');
  const pages = { care: 'Family Care', overview: 'Health Overview', medication: 'Medications', records: 'Medical Records', guide: 'Demo Guide' };
  const escape = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
  const person = () => model.current(state);
  const activePage = () => Object.hasOwn(pages, location.hash.slice(1)) ? location.hash.slice(1) : 'care';
  const button = (label, action, secondary = false) => `<button class="button ${secondary ? 'secondary' : ''}" data-action="${action}">${label}</button>`;

  function toast(message) {
    const target = document.getElementById('toast');
    target.textContent = message; target.hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => { target.hidden = true; }, 3500);
  }

  function heading(kicker, title, subtitle, art) {
    return `<header class="page-heading"><div><span class="eyebrow">${kicker}</span><h1>${title}</h1><p>${subtitle}</p></div><img class="heading-art" src="assets/${art || 'care-moments-small.webp'}" alt="" width="180" height="120"></header>`;
  }

  function taskList() {
    return person().tasks.map(task => `<article class="task ${task.done ? 'done' : ''}"><span class="time">${task.time}</span><span class="task-mark" aria-hidden="true">${task.done ? '✓' : '◷'}</span><div class="task-copy"><b>${task.title}</b><small>${task.detail}</small></div>${task.done ? '<span class="tag">Completed</span>' : `<button class="button small secondary" data-action="${task.type === 'vital' ? 'record' : 'complete'}" data-id="${task.id}">${task.type === 'medication' ? 'Mark taken' : task.type === 'vital' ? 'Record now' : 'Complete'}</button>`}</article>`).join('');
  }

  function chart() {
    const rows = person().records.slice(-range), x = i => 48 + i * 610 / Math.max(1, rows.length - 1);
    const low = Math.floor(Math.min(...rows.map(record => record.diastolic)) / 20) * 20;
    const high = Math.ceil(Math.max(...rows.map(record => record.systolic)) / 20) * 20 + 20;
    const y = value => 208 - (value - low) / (high - low) * 175;
    const ticks = Array.from({ length: 4 }, (_, i) => Math.round(low + (high - low) * i / 3));
    const line = key => rows.map((record, i) => `${x(i).toFixed(1)},${y(record[key]).toFixed(1)}`).join(' ');
    return `<div class="chart-legend"><span>● Systolic</span><span>● Diastolic</span><small>Unit: mmHg · fictional data</small></div><svg class="chart" viewBox="0 0 700 240" role="img" aria-label="Blood pressure trend for the latest ${rows.length} readings. Latest reading: ${rows.at(-1).systolic} over ${rows.at(-1).diastolic} mmHg."><g class="grid-lines">${ticks.map(value => `<line x1="48" y1="${y(value)}" x2="658" y2="${y(value)}"/><text x="8" y="${y(value) + 4}">${value}</text>`).join('')}</g><polyline class="line-primary" points="${line('systolic')}"/><polyline class="line-secondary" points="${line('diastolic')}"/>${rows.map((record, i) => `<circle cx="${x(i)}" cy="${y(record.systolic)}" r="3.5" fill="#267266"><title>${record.date} ${record.time}: ${record.systolic}/${record.diastolic}</title></circle>`).join('')}<text class="axis-label" x="48" y="236">${rows[0].date.slice(5)}</text><text class="axis-label" x="658" y="236" text-anchor="end">${rows.at(-1).date.slice(5)}</text></svg>`;
  }

  function rangeControls() {
    return `<div class="segmented" aria-label="Trend range"><button data-action="range" data-days="7" aria-pressed="${range === 7}">Last 7</button><button data-action="range" data-days="30" aria-pressed="${range === 30}">Last 30</button></div>`;
  }

  function stats() {
    const selected = person(), latest = selected.records.at(-1), done = selected.tasks.filter(task => task.done).length;
    return `<section class="stats" aria-label="Today's overview"><article><span>Care tasks today</span><strong>${done}<small> / ${selected.tasks.length}</small></strong><p>${Math.round(done / selected.tasks.length * 100)}% completed</p></article><article><span>Latest blood pressure</span><strong>${latest.systolic}<small> / ${latest.diastolic}</small></strong><p>mmHg · ${latest.date} ${latest.time}</p></article><article><span>Health readings</span><strong>${selected.records.length}<small> entries</small></strong><p>A continuous, reviewable history</p></article><article><span>Next follow-up</span><strong>Fri<small> 09:00</small></strong><p>Demo appointment · bring recent reports</p></article></section>`;
  }

  function care() {
    const selected = person(), remaining = selected.tasks.filter(task => !task.done).length;
    return heading('FAMILY CARE', 'Care for a loved one, together', 'Keep schedules, prescriptions, and family handovers in one calm workspace.') +
      `<section class="welcome"><div><span class="tag">${selected.relation} · ${selected.age} years · demo profile</span><h2>Good morning, ${selected.name}</h2><p>${remaining ? `${remaining} care ${remaining === 1 ? 'task is' : 'tasks are'} still waiting. Take them one step at a time.` : "Today's care plan is complete. Great work."}</p></div>${button('＋ Record blood pressure', 'record')}</section>${stats()}` +
      `<div class="columns"><section class="panel"><header class="panel-heading"><div><span class="eyebrow">TODAY</span><h2>Care schedule</h2></div><span class="tag">${selected.tasks.length} tasks</span></header>${taskList()}</section><section class="panel"><header class="panel-heading"><div><span class="eyebrow">FAMILY NOTES</span><h2>Latest handover</h2></div></header><div class="note"><span class="avatar light">FC</span><div><b>Olivia Carter <small>Today 09:20</small></b><p>The paperwork for Friday's appointment is in the document folder. Please add any questions for the clinician.</p><span class="tag">Fictional note · demo only</span></div></div><a class="art-link" href="#records"><img src="assets/records-care-small.webp" width="116" height="77" alt=""><div><b>Keep reports together</b><small>Review records and results →</small></div></a></section></div><section class="panel"><header class="panel-heading"><div><span class="eyebrow">HEALTH JOURNAL</span><h2>Recent blood pressure</h2></div>${rangeControls()}</header>${chart()}</section>`;
  }

  function overview() {
    return heading('HEALTH OVERVIEW', `${person().name}'s health at a glance`, 'Turn everyday readings into trends that are easier to understand.', 'health-journal-small.webp') + stats() +
      `<section class="panel"><header class="panel-heading"><div><span class="eyebrow">VITAL SIGNS</span><h2>Blood pressure trend</h2></div>${rangeControls()}</header>${chart()}</section><section class="panel"><header class="panel-heading"><div><h2>Recent readings</h2><p>New demo entries update both this table and the trend chart.</p></div><div class="actions">${button('Export CSV', 'export', true)}${button('＋ Record blood pressure', 'record')}</div></header><div class="table-wrap"><table><thead><tr><th>Date</th><th>Time</th><th>Systolic</th><th>Diastolic</th><th>Source</th></tr></thead><tbody>${person().records.slice(-8).reverse().map(record => `<tr><td>${record.date}</td><td>${record.time}</td><td>${record.systolic} mmHg</td><td>${record.diastolic} mmHg</td><td><span class="tag neutral">Demo entry</span></td></tr>`).join('')}</tbody></table></div></section>`;
  }

  function medication() {
    return heading('MEDICATIONS', 'Keep each dose on track', 'See medications, schedules, and remaining stock in one place.', 'medication-care-small.webp') +
      `<div class="notice">Medication names and doses in this demo are fictional and are not treatment advice.</div><section class="panel"><header class="panel-heading"><div><h2>Medication inventory</h2><p>Complete a medication task to see the related stock update.</p></div></header><div class="drug-grid">${person().stocks.map((stock, i) => `<article class="drug"><div class="drug-icon" aria-hidden="true">✚</div><div><span class="tag ${stock < 10 ? 'warm' : ''}">${stock < 10 ? 'Low stock' : 'Demo medication'}</span><h3>Demo Medication ${i ? 'B' : 'A'}</h3><p>Fictional strength · 1 tablet per dose</p><strong>${stock} <small>tablets left</small></strong></div></article>`).join('')}</div></section><section class="panel"><header class="panel-heading"><h2>Today's medication and care</h2><span class="tag">Separate record for this family member</span></header>${taskList()}</section><section class="info-card"><h3>What does the full application add?</h3><p>Prescription versioning, recurring reminders, caregiver check-ins, restock history, and overdue follow-up notifications. The demo shows local interactions only and never sends a real notification.</p></section>`;
  }

  function records() {
    const reports = ['Complete Blood Count', 'Metabolic Panel', 'Outpatient Follow-up Summary'];
    return heading('HEALTH RECORDS', 'Keep every report within reach', 'Organized records make follow-up visits simpler and less stressful.', 'records-care-small.webp') +
      `<section class="panel"><header class="panel-heading"><div><h2>${person().name}'s records</h2><p>Open any example to review structured results.</p></div><span class="tag">3 fictional reports</span></header><div class="report-grid">${reports.map((name, i) => `<button class="report-card" data-action="detail" data-id="${i}"><span class="report-icon" aria-hidden="true">▤</span><span class="tag neutral">${i === 2 ? 'Visit document' : 'Lab report'}</span><h3>${name}</h3><p>${person().records.at(-1 - i * 4).date} · Demo Medical Center</p><span class="report-open">View record →</span></button>`).join('')}</div></section><section class="info-card"><h3>From a single report to a continuous health record</h3><p>The full application supports image and PDF storage, optional OCR, result verification, and trend analysis. This demo neither accepts files nor contacts an AI service.</p></section>`;
  }

  function guide() {
    return heading('EXPLORE', 'Take a two-minute product tour', 'No account or backend is required. Just open the page and explore.') +
      `<section class="panel"><h2>Start here</h2><ol class="guide-steps"><li><b>Choose a family member</b><p>Switch between Emma and Daniel. Each has separate tasks, inventory, and readings.</p></li><li><b>Complete a medication check-in</b><p>Open Family Care and select “Mark taken.” The task and medication stock update together.</p></li><li><b>Add a blood pressure reading</b><p>Select “Record blood pressure,” save the entry, and review the updated chart in Health Overview.</p></li><li><b>Open and export records</b><p>Review a sample medical record or export the selected family member's readings as CSV.</p></li></ol><div class="actions"><a class="button" href="#care">Start exploring →</a>${button('Reset demo data', 'reset', true)}</div></section><section class="panel"><h2>Demo scope</h2><div class="table-wrap"><table><thead><tr><th>Capability</th><th>Static demo</th><th>Full application</th></tr></thead><tbody><tr><td>Family switching, care tasks, blood pressure</td><td>Simulated in the browser</td><td>Stored after sign-in</td></tr><tr><td>Medication inventory, trends, records</td><td>Fictional data and examples</td><td>Complete business records</td></tr><tr><td>Notifications, OCR, AI, family invitations</td><td>Not enabled</td><td>Runs with your configuration</td></tr><tr><td>Data persistence</td><td>Cleared on refresh or reset</td><td>Persistent with backup and restore</td></tr></tbody></table></div><p class="muted">The demo has no authentication, database, analytics tracker, or external network request. Do not enter real health information. See the project README for deployment and the full user guide.</p></section>`;
  }

  const renderers = { care, overview, medication, records, guide };
  function render() {
    const page = activePage();
    document.title = `${pages[page]} · Clarity Health Demo`;
    document.getElementById('breadcrumb').textContent = pages[page];
    document.querySelectorAll('[data-page]').forEach(link => { if (link.dataset.page === page) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current'); });
    content.innerHTML = renderers[page]();
  }
  function openDialog(dialog) { returnFocus = document.activeElement; dialog.showModal(); }
  function reset() { state = model.createState(); range = 7; document.getElementById('patient').value = '1'; render(); toast('Demo data has been reset.'); }

  document.getElementById('patient').addEventListener('change', event => { state.patientId = Number(event.target.value); render(); toast(`Switched to ${person().name}.`); });
  document.getElementById('reset').addEventListener('click', reset);
  document.addEventListener('click', event => {
    if (event.target.closest('.skip')) { event.preventDefault(); content.focus(); return; }
    const close = event.target.closest('[data-close]'); if (close) { close.closest('dialog').close(); return; }
    const target = event.target.closest('[data-action]'); if (!target) return;
    const action = target.dataset.action;
    if (action === 'reset') reset();
    if (action === 'range') { range = Number(target.dataset.days); render(); content.querySelector(`[data-days="${range}"]`)?.focus({ preventScroll: true }); }
    if (action === 'complete') { model.complete(state, Number(target.dataset.id)); render(); content.focus({ preventScroll: true }); toast('Check-in complete. The task and inventory are updated.'); }
    if (action === 'record') { document.getElementById('form-error').textContent = ''; document.getElementById('vital-form').reset(); openDialog(document.getElementById('record-dialog')); }
    if (action === 'detail') {
      const titles = ['Complete Blood Count', 'Metabolic Panel', 'Outpatient Follow-up Summary'], index = Number(target.dataset.id);
      document.getElementById('detail-title').textContent = titles[index];
      document.getElementById('record-detail').innerHTML = `<p class="muted">${escape(person().name)} · ${person().records.at(-1 - index * 4).date} · Demo Medical Center</p><div class="notice">Fictional report for demonstrating the record layout only. Not for clinical use.</div><div class="table-wrap"><table><thead><tr><th>Test</th><th>Example result</th><th>Unit</th></tr></thead><tbody><tr><td>Hemoglobin</td><td>126</td><td>g/L</td></tr><tr><td>Albumin</td><td>41</td><td>g/L</td></tr><tr><td>Fasting glucose</td><td>5.6</td><td>mmol/L</td></tr></tbody></table></div>`;
      openDialog(document.getElementById('detail-dialog'));
    }
    if (action === 'export') {
      const url = URL.createObjectURL(new Blob([model.csv(state)], { type: 'text/csv;charset=utf-8' }));
      const link = document.createElement('a'); link.href = url; link.download = 'clarity-health-demo-blood-pressure.csv'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); toast('Demo CSV generated.');
    }
  });
  document.querySelectorAll('dialog').forEach(dialog => dialog.addEventListener('close', () => { if (returnFocus?.isConnected) returnFocus.focus(); else content.focus({ preventScroll: true }); }));
  document.getElementById('vital-form').addEventListener('submit', event => {
    event.preventDefault(); const form = new FormData(event.target);
    try { model.addVital(state, Number(form.get('systolic')), Number(form.get('diastolic'))); render(); document.getElementById('record-dialog').close(); toast("Reading saved. The trend and today's task are updated."); }
    catch (error) { document.getElementById('form-error').textContent = error.message; }
  });
  window.addEventListener('hashchange', () => { render(); content.focus({ preventScroll: true }); window.scrollTo(0, 0); });
  render();
})();
