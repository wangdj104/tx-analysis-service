(() => {
  const model = window.HealthDemo;
  let state = model.createState(), range = 7, toastTimer, returnFocus;
  const content = document.getElementById('content');
  const pages = { care: '家庭照护', overview: '健康概览', medication: '用药管理', records: '医疗记录', guide: '演示指南' };
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
    return person().tasks.map(task => `<article class="task ${task.done ? 'done' : ''}"><span class="time">${task.time}</span><span class="task-mark" aria-hidden="true">${task.done ? '✓' : '◷'}</span><div class="task-copy"><b>${task.title}</b><small>${task.detail}</small></div>${task.done ? '<span class="tag">已完成</span>' : `<button class="button small secondary" data-action="${task.type === 'vital' ? 'record' : 'complete'}" data-id="${task.id}">${task.type === 'medication' ? '标记已服' : task.type === 'vital' ? '立即记录' : '完成'}</button>`}</article>`).join('');
  }

  function chart() {
    const rows = person().records.slice(-range), x = i => 48 + i * 610 / Math.max(1, rows.length - 1);
    const low = Math.floor(Math.min(...rows.map(record => record.diastolic)) / 20) * 20;
    const high = Math.ceil(Math.max(...rows.map(record => record.systolic)) / 20) * 20 + 20;
    const y = value => 208 - (value - low) / (high - low) * 175;
    const ticks = Array.from({ length: 4 }, (_, i) => Math.round(low + (high - low) * i / 3));
    const line = key => rows.map((record, i) => `${x(i).toFixed(1)},${y(record[key]).toFixed(1)}`).join(' ');
    return `<div class="chart-legend"><span>● 收缩压</span><span>● 舒张压</span><small>单位：mmHg · 虚构数据</small></div><svg class="chart" viewBox="0 0 700 240" role="img" aria-label="最近 ${rows.length} 次血压趋势，最新读数为 ${rows.at(-1).systolic}/${rows.at(-1).diastolic} mmHg。"><g class="grid-lines">${ticks.map(value => `<line x1="48" y1="${y(value)}" x2="658" y2="${y(value)}"/><text x="8" y="${y(value) + 4}">${value}</text>`).join('')}</g><polyline class="line-primary" points="${line('systolic')}"/><polyline class="line-secondary" points="${line('diastolic')}"/>${rows.map((record, i) => `<circle cx="${x(i)}" cy="${y(record.systolic)}" r="3.5" fill="#267266"><title>${record.date} ${record.time}：${record.systolic}/${record.diastolic}</title></circle>`).join('')}<text class="axis-label" x="48" y="236">${rows[0].date.slice(5)}</text><text class="axis-label" x="658" y="236" text-anchor="end">${rows.at(-1).date.slice(5)}</text></svg>`;
  }

  function rangeControls() {
    return `<div class="segmented" aria-label="趋势范围"><button data-action="range" data-days="7" aria-pressed="${range === 7}">最近 7 次</button><button data-action="range" data-days="30" aria-pressed="${range === 30}">最近 30 次</button></div>`;
  }

  function stats() {
    const selected = person(), latest = selected.records.at(-1), done = selected.tasks.filter(task => task.done).length;
    return `<section class="stats" aria-label="今日概览"><article><span>今日照护任务</span><strong>${done}<small> / ${selected.tasks.length}</small></strong><p>已完成 ${Math.round(done / selected.tasks.length * 100)}%</p></article><article><span>最近血压</span><strong>${latest.systolic}<small> / ${latest.diastolic}</small></strong><p>mmHg · ${latest.date} ${latest.time}</p></article><article><span>健康读数</span><strong>${selected.records.length}<small> 条</small></strong><p>连续、可回顾的记录</p></article><article><span>下次复诊</span><strong>周五<small> 09:00</small></strong><p>演示预约 · 请携带近期报告</p></article></section>`;
  }

  function care() {
    const selected = person(), remaining = selected.tasks.filter(task => !task.done).length;
    return heading('家庭照护', '与家人一起，用心照护', '在一个清晰、平静的工作台中管理排班、医嘱和家庭交接。') +
      `<section class="welcome"><div><span class="tag">${selected.relation} · ${selected.age} 岁 · 演示档案</span><h2>早上好，${selected.name}</h2><p>${remaining ? `还有 ${remaining} 项照护任务待完成，请一步一步来。` : '今日照护计划已全部完成，做得很好。'}</p></div>${button('＋ 记录血压', 'record')}</section>${stats()}` +
      `<div class="columns"><section class="panel"><header class="panel-heading"><div><span class="eyebrow">今天</span><h2>照护日程</h2></div><span class="tag">${selected.tasks.length} 项任务</span></header>${taskList()}</section><section class="panel"><header class="panel-heading"><div><span class="eyebrow">家庭留言</span><h2>最新交接</h2></div></header><div class="note"><span class="avatar light">家</span><div><b>王小雨 <small>今天 09:20</small></b><p>周五复诊的材料已放在文档夹中，请补充准备向医生咨询的问题。</p><span class="tag">虚构留言 · 仅供演示</span></div></div><a class="art-link" href="#records"><img src="assets/records-care-small.webp" width="116" height="77" alt=""><div><b>集中管理报告</b><small>查看记录和结果 →</small></div></a></section></div><section class="panel"><header class="panel-heading"><div><span class="eyebrow">健康日记</span><h2>近期血压</h2></div>${rangeControls()}</header>${chart()}</section>`;
  }

  function overview() {
    return heading('健康概览', `${person().name}的健康概况`, '将日常读数转化为更容易理解的趋势。', 'health-journal-small.webp') + stats() +
      `<section class="panel"><header class="panel-heading"><div><span class="eyebrow">生命体征</span><h2>血压趋势</h2></div>${rangeControls()}</header>${chart()}</section><section class="panel"><header class="panel-heading"><div><h2>近期读数</h2><p>新增的演示读数会同步更新表格和趋势图。</p></div><div class="actions">${button('导出 CSV', 'export', true)}${button('＋ 记录血压', 'record')}</div></header><div class="table-wrap"><table><thead><tr><th>日期</th><th>时间</th><th>收缩压</th><th>舒张压</th><th>数据来源</th></tr></thead><tbody>${person().records.slice(-8).reverse().map(record => `<tr><td>${record.date}</td><td>${record.time}</td><td>${record.systolic} mmHg</td><td>${record.diastolic} mmHg</td><td><span class="tag neutral">演示数据</span></td></tr>`).join('')}</tbody></table></div></section>`;
  }

  function medication() {
    return heading('用药管理', '妥善管理每一次用药', '集中查看药品、服药安排和剩余库存。', 'medication-care-small.webp') +
      `<div class="notice">本演示中的药品名称和剂量均为虚构内容，不构成诊疗建议。</div><section class="panel"><header class="panel-heading"><div><h2>药品库存</h2><p>完成用药任务后，对应库存会同步更新。</p></div></header><div class="drug-grid">${person().stocks.map((stock, i) => `<article class="drug"><div class="drug-icon" aria-hidden="true">✚</div><div><span class="tag ${stock < 10 ? 'warm' : ''}">${stock < 10 ? '库存偏低' : '演示药品'}</span><h3>演示药品 ${i ? 'B' : 'A'}</h3><p>虚构规格 · 每次 1 片</p><strong>${stock} <small>片剩余</small></strong></div></article>`).join('')}</div></section><section class="panel"><header class="panel-heading"><h2>今日用药与照护</h2><span class="tag">该家庭成员的独立记录</span></header>${taskList()}</section><section class="info-card"><h3>完整应用还提供什么？</h3><p>完整应用支持处方版本管理、周期提醒、照护者确认、补货记录和逾期随访通知。本演示仅展示本地交互，不会发送真实通知。</p></section>`;
  }

  function records() {
    const reports = ['血常规检查', '生化检查', '门诊随访小结'];
    return heading('健康档案', '让每一份报告随手可查', '有序整理健康记录，让复诊准备更简单、更从容。', 'records-care-small.webp') +
      `<section class="panel"><header class="panel-heading"><div><h2>${person().name}的健康档案</h2><p>打开任一示例，查看结构化结果。</p></div><span class="tag">3 份虚构报告</span></header><div class="report-grid">${reports.map((name, i) => `<button class="report-card" data-action="detail" data-id="${i}"><span class="report-icon" aria-hidden="true">▤</span><span class="tag neutral">${i === 2 ? '就诊文书' : '检验报告'}</span><h3>${name}</h3><p>${person().records.at(-1 - i * 4).date} · 演示医疗中心</p><span class="report-open">查看记录 →</span></button>`).join('')}</div></section><section class="info-card"><h3>从单份报告到连续健康档案</h3><p>完整应用支持图片与 PDF 存储、可选 OCR、结果核验和趋势分析。本演示不接收文件，也不会连接 AI 服务。</p></section>`;
  }

  function guide() {
    return heading('产品导览', '用两分钟了解产品', '无需账号和后端服务，打开页面即可体验。') +
      `<section class="panel"><h2>从这里开始</h2><ol class="guide-steps"><li><b>选择家庭成员</b><p>在张爱华和李建国之间切换，每个人都有独立的任务、库存和读数。</p></li><li><b>完成用药确认</b><p>打开“家庭照护”，选择“标记为已服用”，任务和药品库存会同步更新。</p></li><li><b>新增血压读数</b><p>选择“记录血压”并保存，然后在“健康概览”中查看更新后的图表。</p></li><li><b>查看并导出记录</b><p>查看示例病历，或将当前家庭成员的读数导出为 CSV。</p></li></ol><div class="actions"><a class="button" href="#care">开始体验 →</a>${button('重置演示数据', 'reset', true)}</div></section><section class="panel"><h2>演示范围</h2><div class="table-wrap"><table><thead><tr><th>功能</th><th>静态演示版</th><th>完整应用</th></tr></thead><tbody><tr><td>家庭成员切换、照护任务、血压记录</td><td>在浏览器中模拟</td><td>登录后持久化存储</td></tr><tr><td>药品库存、趋势和健康档案</td><td>虚构数据和示例</td><td>完整业务记录</td></tr><tr><td>通知、OCR、AI、家庭邀请</td><td>未启用</td><td>按配置运行</td></tr><tr><td>数据持久化</td><td>刷新或重置后清除</td><td>持久化并支持备份恢复</td></tr></tbody></table></div><p class="muted">演示版不包含身份认证、数据库、分析跟踪器或外部网络请求。请勿输入真实健康信息。部署方法和完整使用说明请参阅项目 README。</p></section>`;
  }

  const renderers = { care, overview, medication, records, guide };
  function render() {
    const page = activePage();
    document.title = `${pages[page]} · 家庭健康管理演示`;
    document.getElementById('breadcrumb').textContent = pages[page];
    document.querySelectorAll('[data-page]').forEach(link => { if (link.dataset.page === page) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current'); });
    content.innerHTML = renderers[page]();
  }
  function openDialog(dialog) { returnFocus = document.activeElement; dialog.showModal(); }
  function reset() { state = model.createState(); range = 7; document.getElementById('patient').value = '1'; render(); toast('演示数据已重置。'); }

  document.getElementById('patient').addEventListener('change', event => { state.patientId = Number(event.target.value); render(); toast(`已切换至${person().name}。`); });
  document.getElementById('reset').addEventListener('click', reset);
  document.addEventListener('click', event => {
    if (event.target.closest('.skip')) { event.preventDefault(); content.focus(); return; }
    const close = event.target.closest('[data-close]'); if (close) { close.closest('dialog').close(); return; }
    const target = event.target.closest('[data-action]'); if (!target) return;
    const action = target.dataset.action;
    if (action === 'reset') reset();
    if (action === 'range') { range = Number(target.dataset.days); render(); content.querySelector(`[data-days="${range}"]`)?.focus({ preventScroll: true }); }
    if (action === 'complete') { model.complete(state, Number(target.dataset.id)); render(); content.focus({ preventScroll: true }); toast('确认完成，任务和库存已更新。'); }
    if (action === 'record') { document.getElementById('form-error').textContent = ''; document.getElementById('vital-form').reset(); openDialog(document.getElementById('record-dialog')); }
    if (action === 'detail') {
      const titles = ['血常规检查', '生化检查', '门诊随访小结'], index = Number(target.dataset.id);
      document.getElementById('detail-title').textContent = titles[index];
      document.getElementById('record-detail').innerHTML = `<p class="muted">${escape(person().name)} · ${person().records.at(-1 - index * 4).date} · 演示医疗中心</p><div class="notice">本报告为展示记录布局的虚构内容，不可用于临床诊疗。</div><div class="table-wrap"><table><thead><tr><th>检验项目</th><th>示例结果</th><th>单位</th></tr></thead><tbody><tr><td>血红蛋白</td><td>126</td><td>g/L</td></tr><tr><td>白蛋白</td><td>41</td><td>g/L</td></tr><tr><td>空腹血糖</td><td>5.6</td><td>mmol/L</td></tr></tbody></table></div>`;
      openDialog(document.getElementById('detail-dialog'));
    }
    if (action === 'export') {
      const url = URL.createObjectURL(new Blob([model.csv(state)], { type: 'text/csv;charset=utf-8' }));
      const link = document.createElement('a'); link.href = url; link.download = '家庭健康演示-血压记录.csv'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); toast('演示 CSV 已生成。');
    }
  });
  document.querySelectorAll('dialog').forEach(dialog => dialog.addEventListener('close', () => { if (returnFocus?.isConnected) returnFocus.focus(); else content.focus({ preventScroll: true }); }));
  document.getElementById('vital-form').addEventListener('submit', event => {
    event.preventDefault(); const form = new FormData(event.target);
    try { model.addVital(state, Number(form.get('systolic')), Number(form.get('diastolic'))); render(); document.getElementById('record-dialog').close(); toast('读数已保存，趋势图和今日任务已更新。'); }
    catch (error) { document.getElementById('form-error').textContent = error.message; }
  });
  window.addEventListener('hashchange', () => { render(); content.focus({ preventScroll: true }); window.scrollTo(0, 0); });
  render();
})();
