#!/usr/bin/env bash
# 灰度实验服务关键不变量验证。
# 用法：./verify.sh [BASE_URL]
# 依赖：bash、curl、python3（仅用标准库）
set -u
BASE="${1:-${BASE_URL:-http://127.0.0.1:8080}}"
PYBIN="$(command -v python3 || true)"
if [ -z "$PYBIN" ] && [ -x /opt/python-3.12/bin/python3 ]; then PYBIN=/opt/python-3.12/bin/python3; fi
[ -n "$PYBIN" ] || { echo "未找到 python3"; exit 1; }

echo "==> 等待服务健康：$BASE"
for i in $(seq 1 60); do
  if curl -fsS --max-time 3 "$BASE/api/health" 2>/dev/null | grep -q '"db":"UP"'; then
    echo "    health UP"; break
  fi
  sleep 2
  if [ "$i" = "60" ]; then echo "服务未就绪"; exit 1; fi
done

BASE="$BASE" "$PYBIN" - <<'PY'
import base64, json, os, sys, urllib.request, urllib.error

BASE = os.environ["BASE"].rstrip("/")
fails, passes = [], []

def req(method, path, user="analyst", pwd="analyst123", body=None, raw=False):
    r = urllib.request.Request(BASE + path, method=method)
    token = base64.b64encode(f"{user}:{pwd}".encode()).decode()
    r.add_header("Authorization", "Basic " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, data=data, timeout=20) as resp:
            txt = resp.read().decode()
            return resp.status, (txt if raw else json.loads(txt or "{}"))
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, json.loads(txt)
        except Exception:
            return e.code, txt

def check(name, cond, detail=""):
    (passes if cond else fails).append(name)
    print(("  ✅ " if cond else "  ❌ ") + name + ("" if cond or not detail else f"  -> {detail}"))

# 1. 实验存在
st, exp = req("GET", "/api/experiments/by-code/PILOT-R123-202606")
check("样例实验已登记", st == 200 and exp.get("id"), str(exp)[:200])
eid = exp["id"]

# 2. 两套分配严格 50%
for design, total in [("TRIP_RANDOMIZED", 720), ("CLUSTER_WEEK", 8)]:
    st, a = req("GET", f"/api/experiments/{eid}/assignments?design={design}")
    check(f"{design} 总数={total}", a.get("total") == total, str(a.get("total")))
    check(f"{design} 处理/对照各半", a.get("treatment") == total // 2
          and a.get("control") == total // 2
          and abs(a.get("treatmentShare", 0) - 0.5) < 1e-9,
          f"{a.get('treatment')}/{a.get('control')} share={a.get('treatmentShare')}")

# 3. 命名旅程完整路径标记
expected = {
    "J-DEMO-DIVERT-01": ("CROSS_MIXED", "TREATMENT", True, True),
    "J-DEMO-PURER2-01": ("FULL_CONTROL", "CONTROL", False, False),
    "J-DEMO-CROSS-01":  ("CROSS_MIXED", "CONTROL", True, False),
    "J-DEMO-FULLT-01":  ("FULL_TREATMENT", "TREATMENT", False, False),
    "J-DEMO-FULLC-01":  ("FULL_CONTROL", "CONTROL", False, False),
    "J-DEMO-NONEXEC-01": ("FULL_CONTROL", "TREATMENT", False, False),
    "J-DEMO-INCOMPLETE-01": ("UNKNOWN", None, False, False),
    "J-DEMO-MIDCHANGE-01": ("UNKNOWN", None, False, False),
    "J-DEMO-STORM-01": ("UNKNOWN", None, False, False),
}
for code, (path, arm, cross, diverted) in expected.items():
    st, d = req("GET", f"/api/experiments/{eid}/journeys/{code}")
    led = d.get("ledger") or {}
    ok = st == 200 and led.get("path_label") == path
    if arm:
        ok &= led.get("assigned_arm_trip") == arm
    if cross:
        ok &= bool(led.get("cross_line"))
    if diverted:
        ok &= bool(led.get("diverted"))
    check(f"{code} => {path}" + (f"/{arm}" if arm else ""), ok,
          f"got {led.get('path_label')} arm={led.get('assigned_arm_trip')} "
          f"cross={led.get('cross_line')} divert={led.get('diverted')}")

# 4. 交叉暴露不混入纯净对照
st, fc = req("GET", f"/api/experiments/{eid}/exposure?pathLabel=FULL_CONTROL&limit=500")
rows = fc.get("rows", [])
bad = [r["journey_code"] for r in rows if r.get("cross_line")]
check("FULL_CONTROL 中没有任何 cross_line=true 旅程", not bad, str(bad[:5]))
check("CROSS_MIXED 数量 > 0",
      any(c["path_label"] == "CROSS_MIXED" and c["n"] > 50 for c in fc.get("counts", [])))

# 5. 分析结果：两设计 ITT + RI + CACE
st, an = req("GET", f"/api/experiments/{eid}/analysis")
res = an.get("results", [])
def find(**kw):
    for r in res:
        if all(r.get(k) == v for k, v in kw.items()):
            return r
    return None
itt_t = find(design_type="TRIP_RANDOMIZED", estimator_code="ITT",
             metric_code="wait_seconds", scope="COMMON_SUPPORT")
itt_c = find(design_type="CLUSTER_WEEK", estimator_code="ITT",
             metric_code="wait_seconds", scope="COMMON_SUPPORT")
check("班次方案 ITT(候车) 存在且为负效应", bool(itt_t) and itt_t["estimate"] < 0, str(itt_t))
check("簇周方案 ITT(候车) 存在且为负、效应更大",
      bool(itt_c) and itt_c["estimate"] < itt_t["estimate"], str(itt_c and itt_c["estimate"]))
check("簇周方案 95% 区间宽于班次方案",
      (itt_c["ci_high"] - itt_c["ci_low"]) > (itt_t["ci_high"] - itt_t["ci_low"]),
      f"cluster width={itt_c['ci_high']-itt_c['ci_low']:.1f} vs "
      f"trip width={itt_t['ci_high']-itt_t['ci_low']:.1f}")
ri = find(design_type="CLUSTER_WEEK", estimator_code="RI_PERMUTATION")
check("簇周随机化推断 RI p 值存在（16 置换）", bool(ri) and 0 < ri["p_value"] <= 1)
cace = find(estimator_code="CACE_WALD")
check("CACE/Wald 估计存在", bool(cace) and cace["estimate"] < 0)
crossr = find(estimator_code="CROSS_RATE")
check("跨线暴露率带 Wilson 区间", bool(crossr) and crossr["ci_low"] < crossr["estimate"] < crossr["ci_high"])

# 6. 共同支持缺口存在（暴雨层不能外推）
st, sup = req("GET", f"/api/experiments/{eid}/support")
check("识别出无共同支持的缺口层", int(sup["gaps"]["gap_strata_trip"]) > 0
      or int(sup["gaps"]["gap_strata_cluster"]) > 0, str(sup["gaps"]))

# 7. 护栏：投诉与滞留超限
st, gg = req("GET", f"/api/experiments/{eid}/guardrails")
latest = {g["rule_code"]: g for g in gg.get("latest", [])}
check("投诉护栏超限", latest.get("COMPLAINT_RATE_MAX", {}).get("breached") is True)
check("滞留/停运安全护栏超限", latest.get("STRANDING_RATE_MAX", {}).get("breached") is True)
cmp_rule = latest.get("COMPLAINT_RATE_MAX", {})
cmp_detail = cmp_rule.get("detail", {})
if isinstance(cmp_detail, str):
    cmp_detail = json.loads(cmp_detail)
check("投诉按 service_event_time 关联且存在滞后提交",
      cmp_detail.get("timeBasis", "").startswith("service_event_time")
      and int(cmp_detail.get("complaintsLoggedOnLaterDate", 0)) > 0)

# 8. 权限：analyst 不能执行扩围结论
st, _ = req("POST", f"/api/experiments/{eid}/expansion/evaluate", user="analyst")
check("analyst 调用扩围评估被 403 拒绝", st == 403, f"status={st}")

# 9. approver 评估 -> BLOCKED；人工 GO 被拒
st, dec = req("POST", f"/api/experiments/{eid}/expansion/evaluate", user="approver", pwd="approver123")
check("approver 评估后结论为 BLOCKED", st == 200 and dec.get("decision") == "BLOCKED",
      f"status={st} decision={dec.get('decision') if isinstance(dec,dict) else dec}")
st, body = req("POST", f"/api/experiments/{eid}/expansion/decide",
               user="approver", pwd="approver123",
               body={"decision": "GO", "reason": "try override"})
check("护栏超限时人工 GO 被服务端拒绝(409)", st == 409, f"status={st}")

# 10. OpenAPI / Swagger / 前端
for path in ["/v3/api-docs", "/swagger-ui/index.html", "/index.html", "/login.html", "/app.js"]:
    try:
        with urllib.request.urlopen(BASE + path, timeout=10) as r:
            ok = r.status == 200
    except Exception as e:
        ok = False
    check(f"可访问 {path}", ok)

# 11. 区段依从者 CACE/LATE 特性：资格账/轨迹/偏差图/单调性/双估计/三拒绝门控
st, rep = req("GET", f"/api/experiments/{eid}/compliance/report?scenario=PRIMARY")
g = rep.get("gates", {}) if isinstance(rep, dict) else {}
w = rep.get("wald", {}) if isinstance(rep, dict) else {}
ps = rep.get("principal_stratification", {}) if isinstance(rep, dict) else {}
check("依从者报告 dual_estimable=true", st == 200 and rep.get("dual_estimable") is True, str(rep)[:200])
check("主场景门控：单调/强工具/重叠/支持全通过",
      all([g.get("monotonicity_holds"), g.get("instrument_strong"),
           g.get("overlap"), g.get("complier_support_ok")]), str(g))
check("Wald CACE 为负且 95% 区间完整",
      w.get("estimable") is True and isinstance(w.get("estimate"), (int, float)) and w["estimate"] < 0
      and w.get("ci_low") is not None and w["ci_low"] < w["estimate"] < w["ci_high"], str(w)[:220])
check("主分层 CACE 为负且 bootstrap 区间完整",
      ps.get("estimable") is True and isinstance(ps.get("estimate"), (int, float)) and ps["estimate"] < 0
      and ps.get("ci_low") is not None and ps["ci_low"] < ps["estimate"] < ps["ci_high"], str(ps)[:220])
check("两法同一依从者人群且禁止称所有完整执行区段效应",
      "依从者" in rep.get("same_population_statement", "")
      and "所有完整执行区段" in rep.get("not_all_fully_executed_warning", ""), "")

api_paths = [
    "/compliance", "/compliance/report", "/compliance/wald",
    "/compliance/principal-stratification", "/compliance/monotonicity",
    "/compliance/deviation-graph", "/compliance/eligibility",
    "/compliance/segments", "/compliance/trajectories",
    "/complier-report/report?scenario=PRIMARY", "/cace/monotonicity?scenario=PRIMARY",
]
for p in api_paths:
    st, _ = req("GET", f"/api/experiments/{eid}{p}")
    check(f"依从者 API 200 {p.split('?')[0]}", st == 200, f"status={st}")

st, elig = req("GET", f"/api/experiments/{eid}/compliance/eligibility?scenario=PRIMARY&limit=1000")
es = elig.get("summary", {}) if isinstance(elig, dict) else {}
check("分流资格账含无资格候选与原因",
      st == 200 and int(es.get("ineligible", 0)) > 0 and len(es.get("ineligible_reasons", [])) > 0, str(es)[:200])

st, segs = req("GET", f"/api/experiments/{eid}/compliance/segments?scenario=PRIMARY&limit=500")
srows = segs.get("rows", []) if isinstance(segs, dict) else []
split = [r for r in srows if int(r.get("segment_count") or 1) > 1]
check("区段账存在中途改线/折返/加班的多区段切分", st == 200 and len(split) > 5, f"split={len(split)}")
code = split[0]["segment_code"] if split else (srows[0]["segment_code"] if srows else None)
st, traj = req("GET", f"/api/experiments/{eid}/compliance/trajectories?segmentCode={code}")
cps = {e.get("checkpoint") for e in traj.get("events", [])} if isinstance(traj, dict) else set()
check("区段轨迹有序且含分配/规则动作/结果检查点",
      st == 200 and {"ASSIGNMENT", "RULE_ACTION", "OUTCOME"} <= cps
      and len(traj.get("events", [])) >= 4, str(sorted(cps)))

st, graph = req("GET", f"/api/experiments/{eid}/compliance/deviation-graph?scenario=PRIMARY")
gr = graph.get("reasons", []) if isinstance(graph, dict) else []
check("偏差原因图 nodes/links/reasons 且样例为数组",
      st == 200 and len(graph.get("nodes", [])) > 5 and len(graph.get("links", [])) > 5
      and len(gr) >= 5 and all(isinstance(x.get("examples"), list) for x in gr), "")

st, mono = req("GET", f"/api/experiments/{eid}/compliance/monotonicity?scenario=PRIMARY")
check("主场景单调性成立且无 DEFIER",
      st == 200 and mono.get("holds") is True and int(mono.get("defier_count", -1)) == 0, str(mono)[:160])

st, dfr = req("GET", f"/api/experiments/{eid}/compliance/report?scenario=DEFIER_CASE")
check("反例场景：检出 DEFIER/反向层并拒绝 Wald( MONOTONICITY )",
      isinstance(dfr, dict) and dfr.get("dual_estimable") is False
      and dfr["gates"].get("monotonicity_holds") is False
      and int(dfr["gates"].get("defier_count", 0)) > 0
      and dfr["wald"].get("estimable") is False
      and any("MONOTONICITY" in x for x in dfr.get("refusal_reasons", [])), str(dfr.get("refusal_reasons"))[:200])

st, wk = req("GET", f"/api/experiments/{eid}/compliance/report?scenario=WEAK_CASE")
check("弱工具场景：F<10 且拒绝 Wald( WEAK_INSTRUMENT )",
      isinstance(wk, dict) and wk.get("dual_estimable") is False
      and wk["gates"].get("instrument_strong") is False
      and wk["wald"].get("estimable") is False
      and any("WEAK_INSTRUMENT" in x for x in wk.get("refusal_reasons", [])), str(wk.get("refusal_reasons"))[:200])

st, ov = req("GET", f"/api/experiments/{eid}/compliance/report?scenario=OVERLAP_CASE")
check("无重叠场景：缺臂且拒绝主分层( NO_OVERLAP )",
      isinstance(ov, dict) and ov["gates"].get("overlap") is False
      and ov["principal_stratification"].get("estimable") is False
      and "NO_OVERLAP" in str(ov["principal_stratification"].get("refusal_reason", "")),
      str(ov.get("principal_stratification", {}).get("refusal_reason"))[:200])

try:
    with urllib.request.urlopen(BASE + "/compliance.js", timeout=10) as r:
        compjs_ok = r.status == 200 and "RENDER.compliance" in r.read().decode("utf-8", "ignore")
except Exception:
    compjs_ok = False
check("/compliance.js 可访问且含依从者渲染模块", compjs_ok)

st, rb = req("POST", f"/api/experiments/{eid}/compliance/rebuild", user="analyst", pwd="analyst123")
check("analyst 可重建依从者分析(200)", st == 200 and isinstance(rb, dict) and rb.get("ok") is True, f"status={st}")

print(f"\n通过 {len(passes)} 项，失败 {len(fails)} 项")
if fails:
    print("失败项：" + "; ".join(fails))
    sys.exit(1)
print("ALL_VERIFY_CHECKS_PASSED")
PY