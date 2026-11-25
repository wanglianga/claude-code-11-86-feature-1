/* 区段依从者效应 CACE/LATE 标签页（原生 JS，依赖 app.js 的全局 api/fmt/badge/EXP） */
let compScenario = 'PRIMARY';
let compBundle = null;
let compSegFilter = 'ALL';
let compEligFilter = 'ALL';

const COMP_SCENARIOS = [
  { k: 'PRIMARY', t: '主场景 · 可估计', cls: 'green' },
  { k: 'DEFIER_CASE', t: '反例 · 反向依从者', cls: 'red' },
  { k: 'WEAK_CASE', t: '弱工具', cls: 'amber' },
  { k: 'OVERLAP_CASE', t: '主分层 · 无重叠', cls: 'purple' }
];

const PTYPE_META = {
  COMPLIER: { t: '依从者 C', cls: 'blue' },
  ALWAYS_TAKER: { t: '始终执行 AT', cls: 'teal' },
  NEVER_TAKER: { t: '绝不执行 NT', cls: 'gray' },
  DEFIER: { t: '反向依从 D', cls: 'red' }
};
const STATE_META = {
  FULL_EXECUTED: { t: '完整执行', cls: 'green' },
  PARTIAL: { t: '部分执行', cls: 'amber' },
  NOT_EXECUTED: { t: '完整不执行', cls: 'gray' }
};

function compInjectStyle() {
  if (document.getElementById('comp-styles')) return;
  const css = `
  .comp-scenario .filter-chip.active{box-shadow:0 0 0 2px rgba(108,75,214,.25)}
  .comp-est-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}
  @media(max-width:1100px){.comp-est-grid{grid-template-columns:1fr}}
  .comp-est{border-radius:14px;padding:16px 18px;border:1px solid var(--line);background:var(--panel)}
  .comp-est.ok{border-color:#bfe6cf;background:linear-gradient(180deg,#f4fcf7,#fff)}
  .comp-est.no{border-color:#f0d8a8;background:linear-gradient(180deg,#fffaf0,#fff)}
  .comp-est h3{margin:0 0 6px}
  .comp-big{font-size:34px;font-weight:800;letter-spacing:-.5px}
  .comp-big.neg{color:var(--blue,#2456d6)}
  .comp-ci{font-family:ui-monospace,Menlo,Consolas,monospace;color:var(--ink-2);font-size:13px}
  .comp-gate{display:flex;flex-direction:column;gap:6px}
  .comp-gate .gate-dot{font-weight:700}
  .comp-stack{display:flex;height:18px;border-radius:999px;overflow:hidden;background:#eef1f6;margin:8px 0 4px}
  .comp-stack div{height:100%}
  .comp-legend{display:flex;flex-wrap:wrap;gap:10px;font-size:12.5px;color:var(--ink-2)}
  .comp-legend i{display:inline-block;width:10px;height:10px;border-radius:3px;margin-right:5px}
  .graph-cat{border:1px solid var(--line);border-radius:12px;padding:10px 12px;background:#fbfcfe}
  .graph-cat h4{margin:0 0 8px;font-size:13.5px}
  .graph-reason{display:flex;align-items:center;gap:8px;flex-wrap:wrap;border:1px solid var(--line);
    background:#fff;border-radius:10px;padding:7px 10px;margin:6px 0;cursor:default}
  .graph-reason .cnt{font-weight:800;font-family:ui-monospace,monospace}
  .graph-arrow{color:#9fb0c6;text-align:center;margin:-2px 0}
  .comp-timeline{list-style:none;margin:10px 0 0;padding:0 0 0 18px;border-left:2px solid var(--line)}
  .comp-timeline li{position:relative;padding:6px 0 10px 12px}
  .comp-timeline li::before{content:'';position:absolute;left:-24px;top:11px;width:10px;height:10px;border-radius:50%;background:#b9c7db;border:2px solid #fff;box-shadow:0 0 0 1px var(--line)}
  .comp-timeline li.OK::before{background:var(--green)}
  .comp-timeline li.WARN::before{background:var(--amber)}
  .comp-timeline li.DEVIATION::before{background:var(--red)}
  .comp-timeline .cp{font-family:ui-monospace,monospace;font-size:11.5px;color:var(--purple)}
  .comp-kvmini{font-size:12.5px;color:var(--ink-2)}
  tr.comp-row:hover{background:#f6f9ff}
  .comp-refusal-code{font-family:ui-monospace,monospace;font-size:12px;background:var(--red-soft);
    color:#8f2020;border-radius:6px;padding:2px 7px;display:inline-block;margin:2px 4px 2px 0}
  `;
  const st = document.createElement('style');
  st.id = 'comp-styles';
  st.textContent = css;
  document.head.appendChild(st);
}

window.compSwitchScenario = async function (k) {
  compScenario = k;
  compSegFilter = 'ALL';
  compEligFilter = 'ALL';
  document.querySelectorAll('#comp-scn .filter-chip').forEach(b =>
    b.classList.toggle('active', b.dataset.k === k));
  await loadCompBundle();
  loadCompEligibility();
  loadCompSegments();
};

window.compSegFilter = async function (f) {
  compSegFilter = f;
  document.querySelectorAll('#comp-segfilter .filter-chip').forEach(b =>
    b.classList.toggle('active', b.dataset.f === f));
  await loadCompSegments();
};

window.compEligFilter = async function (f) {
  compEligFilter = f;
  document.querySelectorAll('#comp-eligfilter .filter-chip').forEach(b =>
    b.classList.toggle('active', b.dataset.f === f));
  await loadCompEligibility();
};

RENDER.compliance = async function () {
  compInjectStyle();
  const el = $('#tab-compliance');
  el.innerHTML = `
    <div class="toolbar comp-scenario" style="margin-top:0">
      <div class="pill-row" id="comp-scn">
        ${COMP_SCENARIOS.map(s => `<button class="filter-chip ${s.k === compScenario ? 'active' : ''}" data-k="${s.k}" onclick="compSwitchScenario('${s.k}')">${s.t}</button>`).join('')}
      </div>
      <span class="small muted">同一套门控逻辑；切换场景可验证弱工具 / 单调性反例 / 无重叠时的“拒绝估计”</span>
    </div>
    <div id="comp-root"><div class="card">加载依从者分析中…（首次会运行 400 次聚类 bootstrap）</div></div>`;
  await loadCompBundle();
  loadCompEligibility();
  loadCompSegments();
};

async function loadCompBundle() {
  try {
    compBundle = await api(`/experiments/${EXP.id}/compliance?scenario=${compScenario}`);
    renderCompBundle();
  } catch (e) {
    $('#comp-root').innerHTML = `<div class="banner red">依从者分析加载失败：${e.message}</div>`;
  }
}

function ynBadge(ok, okText, badText) {
  return ok ? badge('✓ ' + okText, 'green') : badge('✕ ' + badText, 'red');
}

function renderCompBundle() {
  const b = compBundle;
  if (!b) return;
  const g = b.gates || {};
  const c = b.counts || {};
  const w = b.wald || {};
  const p = b.principal_stratification || {};
  const mono = b.monotonicity || {};

  const gateCard = (title, ok, value, hint) => `
    <div class="card stat ${ok ? 'green' : 'red'}">
      <div class="k">${title}</div>
      <div class="gate-dot" style="font-size:15px;margin:4px 0">${ynBadge(ok, '通过', '拒绝/风险')}</div>
      <div class="v mono" style="font-size:22px">${value}</div>
      <div class="hint">${hint || ''}</div>
    </div>`;

  const waldEst = w.estimable ? estimateHero('Wald 随机分流工具变量 IV', w, 'blue',
    `第一阶段 ΔD=${fmt(w.first_stage_d, 3)} · 稳健 F=${fmt(w.first_stage_f_cluster_robust, 1)} · ${w.n_segments} 区段 / ${w.n_randomization_units} 随机单元`,
    w.method) : refusedHero('Wald 随机分流工具变量 IV', w.refusal_reason, w.method);

  const psEst = p.estimable ? estimateHero('主分层依从模型加权', {
    estimate: p.estimate, ci_low: p.ci_low, ci_high: p.ci_high, se: p.se_bootstrap
  }, 'purple',
    `预测依从者 ${p.share_complier_predicted != null ? pct(p.share_complier_predicted, 1) : '—'} · bootstrap SE=${fmt(p.se_bootstrap, 1)} · ${p.bootstrap_valid_replicates}/${p.bootstrap_replicates} 次有效 · 重叠 ${p.overlap ? '是' : '否'}`,
    '基线拥堵 + 司机约束 + 车辆约束预测依从类型，按依从者规模加权')
    : refusedHero('主分层依从模型加权', p.refusal_reason,
      '用基线拥堵/司机/车辆约束预测 P(依从者|X) 后加权；依赖模型正确与协变量重叠');

  $('#comp-root').innerHTML = `
    <div class="banner blue" style="margin:2px 0 12px">
      <span class="big">🎯</span><div>
        <b>唯一目标估计量：随机分流诱导的依从者区段平均效应（CACE/LATE）。</b>
        <div class="small" style="margin-top:3px">${b.estimand.population}</div>
        <div class="small">分析单元：${b.estimand.unit_of_analysis}</div>
      </div>
    </div>

    <div class="grid cards-4 comp-gate">
      ${gateCard('单调性（无反向依从者）', g.monotonicity_holds,
        `DEFIER ${g.defier_count}`, `可观测反向层 ${g.observable_reversal_strata} · 率差 ${fmt(mono.first_stage_rate_diff, 3)}`)}
      ${gateCard('强工具（第一阶段 F≥10）', g.instrument_strong,
        `F=${fmt(g.first_stage_f, 1)}`, `第一阶段 ${fmt(g.first_stage, 3)}（阈值 ${b.thresholds.min_first_stage_delta}）`)}
      ${gateCard('主分层协变量重叠', g.overlap,
        g.overlap ? 'OVERLAP' : 'NO OVERLAP', `缺臂协变量层 ${g.overlap_bins_missing_arm}`)}
      ${gateCard('依从者支持规模', g.complier_support_ok,
        fmt(g.complier_mass, 0), `预测依从者加权规模 ≥ ${b.thresholds.min_complier_mass}`)}
    </div>

    <div class="banner ${b.dual_estimable ? 'green' : 'amber'}" style="margin:12px 0">
      <span class="big">${b.dual_estimable ? '✅' : '⛔'}</span><div>
        <b>${b.dual_estimable ? '两种方法均满足门控，可报告同一依从者人群的平均效应与 95% 区间' : '至少一条估计路径触发拒绝门控：按规则不报告被拒估计量的点估计/区间'}</b>
        <div class="small" style="margin-top:4px">${(b.refusal_reasons || []).map(r => `<span class="comp-refusal-code">${r}</span>`).join('')}</div>
      </div>
    </div>

    <div class="comp-est-grid">${waldEst}${psEst}</div>

    <div class="banner amber" style="margin-top:12px">
      <span class="big">⚠️</span><div>
        <b>解释纪律：</b>${b.not_all_fully_executed_warning}
        <div class="small" style="margin-top:3px">${b.same_population_statement}</div>
        <div class="small" style="margin-top:3px">AS_TREATED 观察性均值差（仅对照，非因果）= <b>${fmt(b.as_treated_association_for_contrast, 1)} 秒</b>；与上方 CACE 的差异源于未测执行动机混杂。</div>
      </div>
    </div>

    <div class="card" style="margin-top:14px">
      <h3>🧪 门控/拒绝场景矩阵（同一套代码对四类数据）</h3>
      <p class="section-desc small">点击任意行切换场景。主场景可估计；其余分别精确触发单调性、弱工具、无重叠拒绝。</p>
      <div class="table-wrap"><table class="data"><thead><tr>
        <th>场景</th><th>单调性</th><th class="num">DEFIER</th><th class="num">第一阶段</th><th class="num">F</th><th>强工具</th><th>重叠</th><th>Wald</th><th>主分层</th><th>拒绝原因</th>
      </tr></thead><tbody>
      ${(b.scenario_diagnostics || []).map(r => `<tr class="comp-row clickable" onclick="compSwitchScenario('${r.scenario}')">
        <td><b>${r.scenario}</b><div class="small muted">${r.scenario_label || ''}</div></td>
        <td>${r.monotonicity_holds ? badge('成立', 'green') : badge('反例', 'red')}</td>
        <td class="num">${r.defier_count}</td>
        <td class="num mono">${fmt(r.first_stage, 3)}</td>
        <td class="num mono">${fmt(r.first_stage_f, 1)}</td>
        <td>${r.instrument_strong ? badge('强', 'green') : badge('弱', 'amber')}</td>
        <td>${r.overlap ? badge('有', 'green') : badge('无', 'red')}</td>
        <td>${r.wald_estimable ? badge('报告 ' + fmt(r.wald_estimate_if_reported, 1), 'blue') : badge('拒绝', 'red')}</td>
        <td>${r.principal_estimable ? badge('报告 ' + fmt(r.principal_estimate_if_reported, 1), 'purple') : badge('拒绝', 'amber')}</td>
        <td class="small">${(r.refusal || []).filter(x => x !== 'NONE').map(x => `<div class="muted">${x}</div>`).join('') || '—'}</td>
      </tr>`).join('')}
      </tbody></table></div>
    </div>

    <div class="grid cards-2" style="margin-top:14px">
      <div class="card">
        <h3>🧭 单调性诊断（无“分入处理反而更不执行”的反向依从者）</h3>
        <div class="banner ${mono.holds ? 'green' : 'red'}" style="margin:6px 0">${mono.holds ? '✅' : '⛔'} ${mono.verdict || ''}</div>
        <table class="data"><thead><tr><th>随机臂</th><th class="num">区段 n</th><th class="num">完整执行</th><th class="num">完整执行率</th></tr></thead><tbody>
          ${mono.table ? armRateRow('Z=1 随机处理/分流诱导', mono.table.z1_treatment_assigned) + armRateRow('Z=0 随机对照', mono.table.z0_control_assigned) : ''}
        </tbody></table>
        ${(mono.observable_reversals || []).length ? `<h4 style="margin-top:12px">可观测负条件第一阶段层</h4>
          <div class="table-wrap"><table class="data"><thead><tr><th>层(司机)</th><th class="num">n Z1/Z0</th><th class="num">执行率 Z1</th><th class="num">执行率 Z0</th><th class="num">Δ</th></tr></thead><tbody>
          ${mono.observable_reversals.map(r => `<tr><td><code>${r.stratum}</code></td><td class="num">${r.nZ1}/${r.nZ0}</td><td class="num">${fmt(r.rateZ1, 2)}</td><td class="num">${fmt(r.rateZ0, 2)}</td><td class="num expected-bad">${fmt(r.delta, 2)}</td></tr>`).join('')}
          </tbody></table></div>` : '<p class="small muted" style="margin-top:8px">未发现样本充足且显著为负的条件第一阶段层。</p>'}
        ${(mono.defier_segment_codes || []).length ? `<div class="small" style="margin-top:8px"><b>显式 DEFIER 区段（DGP 真值，前 50）：</b><br>${mono.defier_segment_codes.map(c => `<code class="comp-refusal-code" style="background:var(--red-soft)">${c}</code>`).join(' ')}</div>` : ''}
        <p class="small muted" style="margin-top:8px">${mono.interpretation || ''}</p>
      </div>

      <div class="card">
        <h3>🧩 主分层组成诊断（基线拥堵 / 司机 / 车辆约束）</h3>
        ${compositionBlock(p)}
        <details style="margin-top:10px"><summary class="small clickable">查看 ${(p.bins || []).length} 个协变量分层 Wald 与权重</summary>
          <div class="table-wrap" style="margin-top:8px"><table class="data"><thead><tr><th>层</th><th class="num">n</th><th class="num">Z1/Z0</th><th class="num">依从份额</th><th class="num">第一阶段</th><th class="num">层 Wald</th><th class="num">依从质量</th></tr></thead><tbody>
          ${(p.bins || []).map(x => `<tr><td>${x.bin}</td><td class="num">${x.n}</td><td class="num small">${x.nZ1}/${x.nZ0}</td><td class="num">${fmt(x.complierShare, 2)}</td><td class="num">${fmt(x.fs, 2)}</td><td class="num mono">${Number.isFinite(x.wald) ? fmt(x.wald, 1) : '—'}</td><td class="num small">${fmt(x.mass, 0)}</td></tr>`).join('')}
          </tbody></table></div>
        </details>
        <p class="small muted" style="margin-top:8px">${p.model_dependence || ''}</p>
      </div>
    </div>

    <div class="card" style="margin-top:14px">
      <h3>🕸️ 偏差原因图（类别 → 原因 → 区段执行状态；中途改线已按区段切分）</h3>
      <div id="comp-graph">${graphBlock(b.deviation_graph)}</div>
    </div>

    <div class="card" style="margin-top:14px">
      <h3>📒 分流资格账</h3>
      <div id="comp-elig-summary" class="small">加载中…</div>
      <div class="toolbar" style="margin:10px 0">
        <div class="pill-row" id="comp-eligfilter">
          ${['ALL', 'ELIGIBLE', 'INELIGIBLE'].map(f => `<button class="filter-chip ${compEligFilter === f ? 'active' : ''}" data-f="${f}" onclick="compEligFilter('${f}')">${({ ALL: '全部', ELIGIBLE: '合格', INELIGIBLE: '无资格' })[f]}</button>`).join('')}
        </div>
      </div>
      <div id="comp-elig-table"></div>
    </div>

    <div class="card" style="margin-top:14px">
      <h3>🛤️ 区段执行轨迹账（点击任意区段查看有序轨迹事件）</h3>
      <div class="toolbar" style="margin:8px 0">
        <div class="pill-row" id="comp-segfilter">
          ${['ALL', 'FULL_EXECUTED', 'PARTIAL', 'NOT_EXECUTED'].map(f => `<button class="filter-chip ${compSegFilter === f ? 'active' : ''}" data-f="${f}" onclick="compSegFilter('${f}')">${f === 'ALL' ? '全部' : STATE_META[f].t}</button>`).join('')}
        </div>
      </div>
      <div id="comp-seg-table"></div>
    </div>`;
}

function estimateHero(title, e, tone, sub, method) {
  const neg = (e.estimate || 0) < 0;
  return `<div class="comp-est ok">
    <h3>${title} ${badge('可估计', 'green')}</h3>
    <div class="comp-big ${neg ? 'neg' : ''}">${fmt(e.estimate, 1)}<span style="font-size:15px;font-weight:700"> 秒</span></div>
    <div class="comp-ci">95% CI [${fmt(e.ci_low, 1)}, ${fmt(e.ci_high, 1)}]${e.se != null ? ' · SE ' + fmt(e.se, 1) : ''}</div>
    <div class="comp-kvmini" style="margin-top:6px">${sub || ''}</div>
    <div class="small muted" style="margin-top:6px">${method || ''}</div>
  </div>`;
}

function refusedHero(title, reason, method) {
  return `<div class="comp-est no">
    <h3>${title} ${badge('拒绝估计', 'red')}</h3>
    <div class="comp-big" style="color:var(--amber);font-size:24px;padding:6px 0">ESTIMATION REFUSED</div>
    <div><span class="comp-refusal-code">${reason || '未满足识别门控'}</span></div>
    <div class="small muted" style="margin-top:8px">${method || ''}</div>
    <div class="small" style="margin-top:6px">按题目纪律：工具弱 / 存在单调性反例 / 模型无重叠时，不输出该方法的点估计与区间。</div>
  </div>`;
}

function armRateRow(name, cell) {
  if (!cell) return '';
  return `<tr><td>${name}</td><td class="num">${cell.n}</td><td class="num">${cell.fully_executed}</td><td class="num mono"><b>${pct(cell.rate, 1)}</b></td></tr>`;
}

function compositionBlock(p) {
  const comp = p.composition || {};
  const segs = [
    { k: 'complier', label: '依从者', v: comp.complier, color: 'var(--brand,#2f6fed)' },
    { k: 'always_taker', label: '始终执行', v: comp.always_taker, color: 'var(--teal)' },
    { k: 'never_taker', label: '绝不执行', v: comp.never_taker, color: '#9fb2c9' }
  ];
  const tot = segs.reduce((s, x) => s + (Number(x.v) || 0), 0) || 1;
  const dim = (title, obj) => {
    if (!obj) return '';
    return `<div style="margin-top:8px"><div class="small muted">${title}：预测依从者概率</div>
      <div class="pill-row" style="gap:6px;margin-top:3px">
      ${Object.entries(obj).map(([k, v]) => `<span class="badge gray">${k} <b class="mono">${pct(v, 0)}</b></span>`).join('')}
      </div></div>`;
  };
  return `
    <div class="comp-stack">${segs.map(s => `<div style="width:${(Number(s.v) || 0) / tot * 100}%;background:${s.color}" title="${s.label} ${pct(s.v, 1)}"></div>`).join('')}</div>
    <div class="comp-legend">${segs.map(s => `<span><i style="background:${s.color}"></i>${s.label} <b>${pct(s.v, 1)}</b></span>`).join('')}</div>
    <div class="small muted" style="margin-top:6px">负依从评分份额（模型口径下疑似反向）：${pct(comp.negative_score_share, 2)} · ESS=${fmt(p.effective_sample_size, 1)}</div>
    ${dim('按基线拥堵', p.composition_by_baseline_congestion)}
    ${dim('按司机约束', p.composition_by_driver_constraint)}
    ${dim('按车辆约束', p.composition_by_vehicle_constraint)}`;
}

function graphBlock(g) {
  if (!g || !g.reasons) return '<span class="muted small">无偏差图数据</span>';
  const catLabel = {};
  (g.taxonomy || []).forEach(t => { catLabel[t.category] = t.category_label; });
  const byCat = {};
  g.reasons.forEach(r => { (byCat[r.category] = byCat[r.category] || []).push(r); });
  return `<div class="grid cards-3">${Object.entries(byCat).map(([cat, reasons]) => `
    <div class="graph-cat">
      <h4>${catLabel[cat] || cat} <span class="muted">· ${reasons.reduce((s, r) => s + r.n, 0)}</span></h4>
      ${reasons.map(r => `
        <div class="graph-reason" title="${(r.examples || []).join(', ')}">
          <span>${r.label}</span>
          <span class="cnt" style="color:var(--purple)">${r.n}</span>
          ${(r.execution_states || []).map(s => {
            const meta = STATE_META[s.state] || { t: s.label || s.state, cls: 'gray' };
            return badge(`${meta.t} ${s.n}`, meta.cls);
          }).join('')}
        </div>`).join('<div class="graph-arrow">↓</div>')}
    </div>`).join('')}</div>
  <p class="small muted" style="margin-top:8px">图含 ${g.nodes.length} 个节点 / ${g.links.length} 条加权边；悬停原因块可看样例区段号。</p>`;
}

/* ---------------- 资格账 ---------------- */
async function loadCompEligibility() {
  const sumEl = $('#comp-elig-summary');
  const tblEl = $('#comp-elig-table');
  if (!sumEl || !tblEl) return;
  try {
    const status = compEligFilter === 'ALL' ? '' : `&status=${compEligFilter}`;
    const d = await api(`/experiments/${EXP.id}/compliance/eligibility?scenario=${compScenario}&limit=300${status}`);
    const s = d.summary || {};
    sumEl.innerHTML = `候选 <b>${s.total}</b> · 合格 <b class="expected-ok">${s.eligible}</b> · 无资格 <b class="expected-bad">${s.ineligible}</b>
      ${(s.ineligible_reasons || []).length ? ' · 无资格原因：' + s.ineligible_reasons.map(r => `${r.ineligible_reason}(${r.n})`).join('，') : ''}`;
    const rows = (d.rows || []).slice(0, 120);
    tblEl.innerHTML = `<div class="table-wrap" style="max-height:420px;overflow:auto"><table class="data">
      <thead><tr><th>区段</th><th>线路/日期</th><th>状态</th><th>无资格原因</th><th>分配</th><th class="num">拥堵</th><th class="num">司机约束</th><th class="num">车辆约束</th></tr></thead>
      <tbody>${rows.map(r => `<tr class="comp-row">
        <td><code>${r.segment_code}</code></td>
        <td class="small">${r.route_code || ''} ${r.service_date ? String(r.service_date).slice(0, 10) : ''} <span class="muted">${r.period_band || ''}</span></td>
        <td>${r.eligibility_status === 'ELIGIBLE' ? badge('合格', 'green') : badge('无资格', 'red')}</td>
        <td class="small">${r.ineligible_reason || '—'}</td>
        <td>${r.assignment_arm ? armBadge(r.assignment_arm) : '—'}</td>
        <td class="num mono small">${fmt(r.baseline_congestion, 2)}</td>
        <td class="num mono small">${fmt(r.driver_constraint, 2)}</td>
        <td class="num mono small">${fmt(r.vehicle_constraint, 2)}</td>
      </tr>`).join('') || '<tr><td colspan="8" class="muted" style="padding:18px;text-align:center">无记录</td></tr>'}</tbody></table></div>
      ${d.rows && d.rows.length > 120 ? '<div class="small muted" style="padding:6px">仅渲染前 120 行，完整可经 API 获取（limit 最大 1000）。</div>' : ''}`;
  } catch (e) {
    sumEl.innerHTML = `<span class="expected-bad">资格账加载失败：${e.message}</span>`;
  }
}

/* ---------------- 区段轨迹账 ---------------- */
async function loadCompSegments() {
  const el = $('#comp-seg-table');
  if (!el) return;
  try {
    const st = compSegFilter === 'ALL' ? '' : `&executionState=${compSegFilter}`;
    const d = await api(`/experiments/${EXP.id}/compliance/segments?scenario=${compScenario}&limit=200${st}`);
    const rows = d.rows || [];
    el.innerHTML = `<div class="small muted" style="margin-bottom:6px">场景共 ${d.total} 个区段，当前返回 ${d.returned}（点击行查看轨迹）。</div>
      <div class="table-wrap" style="max-height:520px;overflow:auto"><table class="data">
      <thead><tr><th>区段</th><th>旅程/班次</th><th>线路</th><th>切分</th><th>类型</th><th>分配</th><th>主分层</th><th>执行状态</th><th class="num">执行度</th><th>偏差</th><th>司机</th><th class="num">等候(s)</th></tr></thead>
      <tbody>${rows.map(r => {
        const pt = PTYPE_META[r.principal_type] || { t: r.principal_type || '—', cls: 'gray' };
        const sm = STATE_META[r.execution_state] || { t: r.execution_state || '—', cls: 'gray' };
        const split = r.segment_count > 1 ? `${r.segment_index}/${r.segment_count}` : '1/1';
        const flags = [r.mid_route_change ? badge('中途改线', 'purple') : '', r.short_turn ? badge('折返', 'amber') : '', r.overtime ? badge('加班', 'teal') : ''].join(' ');
        return `<tr class="comp-row clickable" onclick="openSegTraj('${r.segment_code}')">
          <td><code>${r.segment_code}</code></td>
          <td class="small">${r.journey_code || ''}<div class="muted">${r.trip_code || ''}</div></td>
          <td>${r.route_code || ''}</td>
          <td class="small">${split}<div>${flags}</div></td>
          <td class="small">${r.segment_kind || ''}</td>
          <td>${armBadge(r.assignment_arm)}</td>
          <td>${r.principal_type ? badge(pt.t, pt.cls) : '—'}</td>
          <td>${r.execution_state ? badge(sm.t, sm.cls) : '—'}</td>
          <td class="num mono">${r.adherence_rate != null ? fmt(r.adherence_rate, 2) : '—'}</td>
          <td class="small">${r.deviation_code && r.deviation_code !== 'NONE' ? r.deviation_code : '—'}</td>
          <td class="small mono">${r.driver_id || ''}</td>
          <td class="num mono">${r.wait_seconds != null ? fmt(r.wait_seconds, 0) : '—'}</td>
        </tr>`;
      }).join('') || '<tr><td colspan="12" class="muted" style="padding:18px;text-align:center">无区段</td></tr>'}</tbody></table></div>`;
  } catch (e) {
    el.innerHTML = `<span class="expected-bad">区段账加载失败：${e.message}</span>`;
  }
}

window.openSegTraj = async function (code) {
  try {
    const d = await api(`/experiments/${EXP.id}/compliance/trajectories?segmentCode=${encodeURIComponent(code)}`);
    const evs = d.events || [];
    $('#modal').innerHTML = `
      <button class="btn sm close" onclick="closeModal()">关闭</button>
      <h2>区段轨迹 <code>${code}</code></h2>
      <div class="small muted">中途改线/折返/加班按切点拆为多个区段，共享同一随机分流分配 Z；以下为有序执行轨迹事件。</div>
      <ul class="comp-timeline">
        ${evs.map(e => `<li class="${e.status || 'INFO'}">
          <div class="cp">${e.seq}. ${e.checkpoint} · ${e.status || ''}</div>
          <b>${e.label || ''}</b>
          ${e.detail ? `<div class="small muted">${e.detail}</div>` : ''}
        </li>`).join('') || '<li>无轨迹事件</li>'}
      </ul>`;
    $('#modal-back').classList.add('open');
  } catch (e) {
    toast('轨迹加载失败：' + e.message, true);
  }
};
