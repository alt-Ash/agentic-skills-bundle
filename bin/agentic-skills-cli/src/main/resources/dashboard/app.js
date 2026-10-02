// Agentic Skills usage dashboard. Plain browser JS, no build step.
// Every value from the API is rendered with textContent — never innerHTML — because commands,
// errors and paths in the data are untrusted text.
(() => {
  'use strict';

  const view = document.getElementById('view');
  const tabsEl = document.getElementById('tabs');
  const form = document.getElementById('filters');
  const foot = document.getElementById('foot');
  let charts = [];
  let current = 'overview';

  // ─── helpers ─────────────────────────────────────────────────────────────

  function el(tag, attrs, ...kids) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (k === 'class') node.className = v;
      else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
      else if (v !== null && v !== undefined) node.setAttribute(k, v);
    }
    for (const kid of kids.flat()) {
      if (kid === null || kid === undefined || kid === false) continue;
      node.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
    }
    return node;
  }

  const fmt = (n) => (n === null || n === undefined) ? '–' : Number(n).toLocaleString();
  const compact = (n) => (n === null || n === undefined) ? '–'
    : Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(n);
  const pct = (r) => (r === null || r === undefined) ? '–' : (r * 100).toFixed(r < 0.1 ? 1 : 0) + '%';

  function duration(a, b) {
    if (!a || !b) return '–';
    const ms = Date.parse(b) - Date.parse(a);
    if (!(ms >= 0)) return '–';
    const m = Math.round(ms / 60000);
    return m < 60 ? m + ' min' : (m / 60).toFixed(1) + ' h';
  }

  const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

  function query() {
    const p = new URLSearchParams();
    for (const [k, v] of new FormData(form)) if (v) p.set(k, v);
    return p.toString();
  }

  async function api(path) {
    const q = query();
    const res = await fetch('/api/' + path + (q && !path.includes('?') ? '?' + q : ''), { cache: 'no-store' });
    const body = await res.json();
    if (!res.ok) throw new Error(body.error || res.statusText);
    return body;
  }

  function card(label, value, sub) {
    return el('div', { class: 'card' }, el('div', { class: 'v' }, value), el('div', { class: 'l' }, label),
      sub ? el('div', { class: 'l' }, sub) : null);
  }

  function table(columns, rows, opts = {}) {
    if (!rows.length) return el('div', { class: 'empty' }, opts.empty || 'No data for these filters.');
    const head = el('tr', {}, columns.map((c) => el('th', { class: c.num ? 'n' : '' }, c.label)));
    const body = rows.map((r) => {
      const tr = el('tr', opts.onRow ? { class: 'click', tabindex: '0',
        onclick: () => opts.onRow(r), onkeydown: (e) => { if (e.key === 'Enter') opts.onRow(r); } } : {},
      columns.map((c) => el('td', { class: (c.num ? 'n ' : '') + (c.mono ? 'mono' : '') }, c.fmt ? c.fmt(r[c.key], r) : (r[c.key] ?? '–'))));
      return tr;
    });
    return el('div', { class: 'scroll' }, el('table', {}, el('thead', {}, head), el('tbody', {}, body)));
  }

  function chartBox(title, hint) {
    const canvas = el('canvas');
    const box = el('section', {}, el('h2', {}, title), hint ? el('div', { class: 'muted' }, hint) : null,
      el('div', { class: 'chart' }, canvas));
    return { box, canvas };
  }

  function makeChart(canvas, config) {
    const text = css('--muted');
    const grid = css('--border');
    config.options = Object.assign({ responsive: true, maintainAspectRatio: false, animation: false }, config.options || {});
    config.options.plugins = Object.assign({ legend: { labels: { color: text } } }, config.options.plugins || {});
    config.options.scales = config.options.scales || {};
    for (const s of Object.values(config.options.scales)) {
      s.ticks = Object.assign({ color: text }, s.ticks || {});
      s.grid = Object.assign({ color: grid }, s.grid || {});
    }
    const c = new Chart(canvas, config);
    charts.push(c);
    return c;
  }

  // ─── views ───────────────────────────────────────────────────────────────

  async function overview() {
    const [s, series, models, insights] = await Promise.all([api('summary'), api('timeseries'), api('models'), api('insights')]);
    const out = [];

    out.push(el('section', {}, el('h2', {}, 'Insights'),
      insights.length ? insights.map((i) => el('div', { class: 'insight ' + i.severity }, el('b', {}, i.title), i.detail))
        : el('div', { class: 'muted' }, 'Nothing stands out for these filters.')));

    out.push(el('div', { class: 'cards' },
      card('Sessions', fmt(s.sessions)),
      card('Prompts', fmt(s.prompts)),
      card('Tool calls', fmt(s.tool_calls), fmt(s.tool_failures) + ' failed (' + pct(s.failure_rate) + ')'),
      card('Avg context / call', compact(s.avg_context), 'tokens (input + cached)'),
      card('Peak context', compact(s.peak_context), 'tokens'),
      card('Tokens processed', compact(s.tokens_processed), 'sum of per-call context, approximate'),
      card('Lines changed', '+' + fmt(s.lines_added) + ' / −' + fmt(s.lines_deleted), 'from session summaries'),
      card('Guard blocks', fmt(s.guard_blocks))));

    const daily = chartBox('Activity per day', 'Prompts, tool calls and failures');
    const tokens = chartBox('Tokens processed per day', 'Sum of per-call context size — a proxy for load, not billing');
    out.push(el('div', { class: 'grid2' }, daily.box, tokens.box));
    const modelBox = chartBox('Tool calls by model');
    out.push(modelBox.box);
    view.replaceChildren(...out);

    const days = series.map((r) => r.day);
    makeChart(daily.canvas, { type: 'bar', data: { labels: days, datasets: [
      { label: 'Prompts', data: series.map((r) => r.prompts), backgroundColor: css('--accent') },
      { label: 'Tool calls', data: series.map((r) => r.tool_calls), backgroundColor: css('--accent3') },
      { label: 'Failures', data: series.map((r) => r.tool_failures), backgroundColor: css('--accent2') }] } });
    makeChart(tokens.canvas, { type: 'line', data: { labels: days, datasets: [
      { label: 'Tokens processed', data: series.map((r) => r.tokens_processed), borderColor: css('--accent'), backgroundColor: css('--accent'), tension: 0.2 }] } });
    makeChart(modelBox.canvas, { type: 'bar', data: { labels: models.map((m) => m.model), datasets: [
      { label: 'Tool calls', data: models.map((m) => m.tool_calls), backgroundColor: css('--accent') }] },
      options: { indexAxis: 'y' } });
  }

  async function tools() {
    const [t, c] = await Promise.all([api('tools'), api('commands')]);
    const chart = chartBox('Calls and failures by tool');
    view.replaceChildren(
      chart.box,
      el('section', {}, el('h2', {}, 'Tools'), table([
        { label: 'Tool', key: 'tool' }, { label: 'Calls', key: 'calls', num: true, fmt: fmt },
        { label: 'Failures', key: 'failures', num: true, fmt: fmt },
        { label: 'Failure rate', key: 'failure_rate', num: true, fmt: pct },
        { label: 'Avg ms', key: 'avg_duration_ms', num: true, fmt: (v) => v == null ? '–' : Math.round(v) }], t.tools)),
      el('section', {}, el('h2', {}, 'Top errors'), table([
        { label: 'Tool', key: 'tool' }, { label: 'Error', key: 'error', mono: true },
        { label: 'Count', key: 'count', num: true, fmt: fmt }], t.errors, { empty: 'No failures recorded.' })),
      el('section', {}, el('h2', {}, 'Bash commands (first word)'), table([
        { label: 'Command', key: 'command', mono: true }, { label: 'Count', key: 'count', num: true, fmt: fmt },
        { label: 'Failures', key: 'failures', num: true, fmt: fmt }], t.bash, { empty: 'No Bash commands recorded.' })),
      el('section', {}, el('h2', {}, 'Slash commands'), table([
        { label: 'Command', key: 'command', mono: true }, { label: 'Uses', key: 'count', num: true, fmt: fmt },
        { label: 'Sessions', key: 'sessions', num: true, fmt: fmt }], c, { empty: 'No slash commands used.' })));
    const top = t.tools.slice(0, 12);
    makeChart(chart.canvas, { type: 'bar', data: { labels: top.map((r) => r.tool), datasets: [
      { label: 'Calls', data: top.map((r) => r.calls), backgroundColor: css('--accent') },
      { label: 'Failures', data: top.map((r) => r.failures), backgroundColor: css('--accent2') }] },
      options: { scales: { x: { stacked: false }, y: { beginAtZero: true } } } });
  }

  async function sessions() {
    const rows = await api('sessions');
    view.replaceChildren(el('section', {}, el('h2', {}, 'Sessions'),
      el('div', { class: 'muted' }, 'Click a session to see its event stream.'),
      table([
        { label: 'Started', key: 'started_at', fmt: (v) => v ? v.replace('T', ' ').slice(0, 16) : '–' },
        { label: 'Project', key: 'project' },
        { label: 'Length', key: 'ended_at', fmt: (v, r) => duration(r.started_at, v) },
        { label: 'Prompts', key: 'prompts', num: true, fmt: fmt },
        { label: 'Tool calls', key: 'tool_calls', num: true, fmt: fmt },
        { label: 'Failures', key: 'failures', num: true, fmt: fmt },
        { label: 'Peak context', key: 'peak_context', num: true, fmt: compact },
        { label: 'Lines +/−', key: 'lines_added', num: true, fmt: (v, r) => '+' + fmt(v) + ' / −' + fmt(r.lines_deleted) }],
      rows, { onRow: (r) => sessionDetail(r) })));
  }

  async function sessionDetail(row) {
    const events = await api('sessions/' + encodeURIComponent(row.session_id) + '?x=1');
    view.replaceChildren(
      el('button', { onclick: () => select('sessions') }, '← Sessions'),
      el('section', {}, el('h2', {}, (row.project || 'session') + ' · ' + (row.started_at || '')),
        table([
          { label: 'Time', key: 'ts', fmt: (v) => v.replace('T', ' ').slice(11, 19) },
          { label: 'Event', key: 'event' },
          { label: 'Tool', key: 'tool' },
          { label: 'Detail', key: 'command', mono: true,
            fmt: (v, r) => r.guard_rule ? 'BLOCKED: ' + r.guard_rule + ' — ' + (v || '') : (r.error || v || r.slash_command || '') },
          { label: 'Context', key: 'context_tokens', num: true, fmt: (v) => v ? compact(v) : '–' }], events)));
  }

  async function guard() {
    const g = await api('guard');
    view.replaceChildren(
      el('section', {}, el('h2', {}, 'Blocks by rule'), table([
        { label: 'Rule', key: 'rule' }, { label: 'Tool', key: 'tool' },
        { label: 'Blocks', key: 'blocks', num: true, fmt: fmt }, { label: 'Last', key: 'last', fmt: (v) => v.replace('T', ' ').slice(0, 16) }],
      g.byRule, { empty: 'No guard blocks recorded. (The guard hook is opt-in.)' })),
      el('section', {}, el('h2', {}, 'Recent blocks'), table([
        { label: 'When', key: 'ts', fmt: (v) => v.replace('T', ' ').slice(0, 19) }, { label: 'Project', key: 'project' },
        { label: 'Rule', key: 'rule' }, { label: 'Tool', key: 'tool' }, { label: 'Blocked', key: 'subject', mono: true }],
      g.recent, { empty: 'Nothing blocked.' })));
  }

  async function git() {
    const [files, s] = await Promise.all([api('git'), api('summary')]);
    view.replaceChildren(
      el('div', { class: 'cards' }, card('Lines added', '+' + fmt(s.lines_added)), card('Lines deleted', '−' + fmt(s.lines_deleted)),
        card('Sessions', fmt(s.sessions))),
      el('section', {}, el('h2', {}, 'Most-changed files'),
        el('div', { class: 'muted' }, 'Files added or modified across session summaries — churn hot spots.'),
        table([{ label: 'File', key: 'path', mono: true }, { label: 'Changes', key: 'changes', num: true, fmt: fmt },
          { label: 'Sessions', key: 'sessions', num: true, fmt: fmt }], files, { empty: 'No git changes recorded.' })));
  }

  const TABS = { overview, tools, sessions, guard, git };
  const TITLES = { overview: 'Overview', tools: 'Tools & commands', sessions: 'Sessions', guard: 'Guard', git: 'Git impact' };

  async function select(name) {
    current = name;
    for (const b of tabsEl.children) b.setAttribute('aria-selected', String(b.dataset.tab === name));
    charts.forEach((c) => c.destroy());
    charts = [];
    view.replaceChildren(el('div', { class: 'empty' }, 'Loading…'));
    try {
      await TABS[name]();
    } catch (e) {
      view.replaceChildren(el('div', { class: 'empty bad' }, 'Could not load: ' + e.message));
    }
  }

  async function init() {
    for (const [k, title] of Object.entries(TITLES)) {
      tabsEl.append(el('button', { role: 'tab', 'data-tab': k, 'aria-selected': 'false', onclick: () => select(k) }, title));
    }
    try {
      const f = await api('filters');
      for (const p of f.projects) form.elements.project.append(el('option', { value: p }, p));
      for (const m of f.models) form.elements.model.append(el('option', { value: m }, m));
      const h = f.health;
      foot.textContent = fmt(h.totalEvents) + ' events stored · read-only view of your local usage database'
        + (h.droppedEvents ? ' · ' + fmt(h.droppedEvents) + ' dropped' : '');
    } catch (e) {
      view.replaceChildren(el('div', { class: 'empty bad' }, 'Could not reach the usage database: ' + e.message));
      return;
    }
    form.addEventListener('change', () => select(current));
    document.getElementById('reset').addEventListener('click', () => { form.reset(); select(current); });
    select('overview');
  }

  init();
})();
