# 公交网络灰度实验服务（R1/R2/R3 虚构样例）

用 **Java 21（运行/构建于 JDK 25）+ Spring Boot 3.5 + PostgreSQL** 实现的可复现灰度实验后端与评审控制台。
目标不是比较试点前后平均值，而是在预登记的 **四周、约 50% 合格服务接受新规则** 设计下，估计**乘客每次完整旅程**的端到端候车时间与拥挤暴露变化；旅程包含首乘与换乘，未完成刷卡或中途改线一律保留为 `UNKNOWN`。

系统覆盖题目要求的完整闭环：

1. **实验登记冻结**：合格线路/班次、处理规则、随机种子、分配概率、目标旅程人群、主要/安全指标、试点周期，一次登记并计算指纹。
2. **固定网络图**：连接关系只来自**实验前**刷卡换乘比例（R1↔R2=40%，R3 基本不换乘），用并查集得到冻结簇，不使用试点结果临时拆并。
3. **两套随机方案**：
   - 线路—具体班次（日期级，线路×日期×时段分层，**720 个单元，严格 360/360**）；
   - 高换乘线路成簇后的簇—周方案（2 簇 × 4 周 = **8 个单元，严格 4/4**，每周两簇一中一控）。
4. **暴露账逐段关联**：计划分配 → GPS/AVL 实际运行 → 短线折返/停运/延误偏离 → 每段与完整旅程路径暴露。
5. **共同支持器**：时段 × OD 区间 × 天气 × 服务可达性，检查两臂加权样本；不满足只报覆盖缺口，不外推。
6. **估计器**：乘客人数加权 Hajek **ITT**、按随机化单元聚类的 CR1 区间、CACE/Wald-IV、跨线暴露率 Wilson 区间、簇方案 2⁴ 随机化推断（RI）。
7. **独立护栏**：投诉率、满载班次占比、滞留/未完成旅程占比；任一硬护栏超限即 `STOP_EXPANSION`，扩围结论 `BLOCKED`，服务端同时拒绝人工改判 `GO`。
8. **外部事件按事件时间关联**：暴雨停运、降雨、施工、临时改线均带事件窗口；投诉同时保存 `complaint_time` 与 `service_event_time`，统计只用后者。
9. **界面**：随机分配、暴露路径、共同支持、客流结果、**区段依从者 CACE**、护栏、离线审阅、扩围证据等 **11 个标签页**；另含 Swagger UI / OpenAPI 3。
10. **区段级依从者效应（本题新增）**：把临时改线/加班班次**按切点切为区段**，建立**分流资格账、区段执行轨迹、偏差原因图、单调性诊断**，并用 **Wald-IV** 与**主分层依从模型**两种方法估计**同一个“随机分流诱导依从者区段”人群**的候车效应及区间；弱工具、单调性反例（反向依从者）或模型无重叠时**拒绝估计**。

---

## 一、最快启动：Docker Compose（一键）

```bash
docker compose up --build
```

- 应用：<http://localhost:8080>
- Swagger UI：<http://localhost:8080/swagger-ui.html>
- Compose 内 PostgreSQL 18：库/用户/密码均为 `ccdev`；为避免与宿主机已装 PostgreSQL 的 5432 冲突，宿主机映射为 **5433**（容器内仍是 5432）。
- 首次启动会自动执行 `schema.sql`（幂等建表）并写入确定性虚构样例。

> 说明：本交付环境内没有 `docker` 可执行程序，因此 Dockerfile/Compose 已按标准多阶段构建编写，但**镜像构建/Compose 启动未在本环境实际执行**；下列本地方式已被实际构建、运行与验证。

---

## 二、本地开发与启动（本容器已预装运行时）

默认 PATH 已指向预装版本；如需显式固定：

```bash
export JAVA_HOME=/opt/jdk-25
export PATH=$JAVA_HOME/bin:/opt/maven/bin:/opt/postgresql-18/bin:$PATH
```

### 1. 准备 PostgreSQL（已运行可跳过）

本容器自带 PostgreSQL 18，且已存在可连账号：

```bash
psql "postgresql://ccdev:ccdev@127.0.0.1:5432/ccdev" -c 'select 1;'
```

若在全新机器上：用 PostgreSQL 18 建库建用户即可，或直接使用上面的 Docker Compose。连接参数可用环境变量覆盖：

| 变量 | 默认值 |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/ccdev` |
| `DB_USER` / `DB_PASSWORD` | `ccdev` / `ccdev` |
| `SERVER_PORT` | `8080` |

> `ccdev/ccdev` 仅为隔离开发库，不是生产配置。

### 2. 开发模式启动

```bash
mvn spring-boot:run
```

或打包后运行：

```bash
mvn -DskipTests package
java -jar target/transit-pilot.jar
```

启动后访问 <http://127.0.0.1:8080>，会自动建表并播种样例（幂等：检测到 `PILOT-R123-202606` 已存在则跳过）。

### 3. 完全重置样例

```bash
psql "postgresql://ccdev:ccdev@127.0.0.1:5432/ccdev" \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
# 然后重启应用；Docker 方式可用 docker compose down -v && docker compose up --build
```

---

## 三、测试账号

表单登录页 <http://127.0.0.1:8080/login.html>，也可直接对 API 使用 HTTP Basic：

| 账号 | 密码 | 角色 | 权限 |
| --- | --- | --- | --- |
| `viewer` | `viewer123` | VIEWER | 全部 GET / 离线审阅 |
| `analyst` | `analyst123` | ANALYST | 额外可：登记实验、重算流水线、评估护栏 |
| `approver` | `approver123` | APPROVER | 额外可：形成/签署扩围结论（GO/HOLD/BLOCKED） |

例：

```bash
curl -u analyst:analyst123 http://127.0.0.1:8080/api/experiments
```

---

## 四、本题验证方式（含我实际执行过的结论）

### 1. 单元测试（实际执行 ✅）

```bash
mvn test
```

结果（最后一次实际运行）：

```
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖：50% 平衡分配与确定性哈希、R1/R2 聚类/R3 独立、全部命名路径分类
（分流/同车普通对照/对照转乘处理/全程 T/全程 C/不执行/两类 UNKNOWN）、
聚类 ITT 公式、Wilson 区间与 RI 枚举；以及新增 `ComplianceStatsTest` 的 5 个用例：
已知常数 CACE 的 Wald 精确恢复与强工具 F、单调性通过/检出 DEFIER 与司机层完全反向、
弱工具第一阶段 <0.1 且 F<10、主分层加权恢复效应且重叠、高风险层缺对照时以 `NO_OVERLAP` 拒绝。

### 2. 端到端不变量脚本（实际执行 ✅）

先启动应用，再运行：

```bash
./verify.sh                 # 默认 http://127.0.0.1:8080
```

脚本仅依赖 `bash/curl/python3`（标准库），实际结果：

```
通过 60 项，失败 0 项
ALL_VERIFY_CHECKS_PASSED
```

核验内容包括：

- 两套分配总数与严格 50%（720=360/360；8=4/4）；
- 9 个 `J-DEMO-*` 命名旅程的完整路径、分配臂、cross/diverted 标记；
- `FULL_CONTROL` 中不存在任何 `cross_line=true`（交叉暴露不混入未暴露对照）；
- 两设计 ITT/CACE/RI/跨线率区间存在，且簇方案区间更宽；
- 共同支持缺口存在；投诉、滞留两条护栏超限；
- 投诉确有滞后提交且按 `service_event_time` 关联；
- analyst 不能调用扩围评估（403）；approver 得到 `BLOCKED`；护栏超限时人工 `GO` 被拒（409）；
- `/v3/api-docs`、`/swagger-ui/index.html`、前端页面与 `app.js` 均可访问；
- **区段依从者特性（新增 26 项断言，总计 60）**：主场景 `dual_estimable=true` 且单调/强工具/重叠/支持四门全过；
  Wald 与主分层 CACE 均为负且满足 `ci_low < estimate < ci_high`；返回“同一依从者人群、不得称为所有完整执行区段效应”的口径声明；
  11 个 `/compliance...`（含 `/complier-report`、`/cace` 别名）端点 200；资格账存在无资格候选与原因；
  区段账存在多区段切分；轨迹含 `ASSIGNMENT/RULE_ACTION/OUTCOME` 检查点；偏差图 nodes/links/reasons 与数组样例；
  三个拒绝场景分别命中 `MONOTONICITY_DEFIERS`（26 个 DEFIER + DRV-077 的 0↔1 反向层）、
  `WEAK_INSTRUMENT`（F≈6.4）、`NO_OVERLAP`（高约束层缺对照）；`/compliance.js` 可访问；analyst 可 POST 重建。

### 3. 前端渲染冒烟（实际执行 ✅）

本环境没有浏览器，我用临时目录中的 `jsdom` 对**真实运行的后端**加载 `index.html + app.js + compliance.js`：
**11 个标签页全部成功渲染**、命名旅程弹窗输出逐段判定追踪、`CROSS_MIXED` 过滤可交互；
新增依从者页渲染出 2 个估计卡与约 200 行区段账，点击区段弹出 6 个有序轨迹事件，
并依次切换 DEFIER/WEAK/OVERLAP 场景确认对应拒绝卡与 `MONOTONICITY/WEAK_INSTRUMENT/NO_OVERLAP` 标记，
结果 `UI_SMOKE_OK`，无运行时错误。该冒烟脚本在 `/tmp/uismoke/smoke.cjs`（临时目录，不属于交付物）。

### 4. 我没有执行的验证

- 没有实际运行 `docker compose up --build`（该容器内无 `docker` 命令）；
  Docker 路径仅完成了文件编写与 YAML 自检，未声称已实际构建镜像。

---

## 五、虚构样例剧本与复现数字

固定种子 `TSP-PILOT-2026-06-01`，数据由 `SeedService` 用固定 `Random(20260601)` 与
`SHA-256(seed::unitKey)` 确定性生成；任何人重置库后重跑应得到一致数字。

- 网络：R1/R2 共享 `HUB-CENTRAL`，实验前边 R1→R2=0.40、R2→R1=0.38；R3 边比例 ≤0.02。
  冻结簇：`C-R1R2`、`C-R3`。
- 规模：**2477** 条旅程（含 9 个命名样例、22 条批量分流、46 条暴雨、约 2400 条普通合成）、
  **720** 个日期级计划班次及 GPS 实际运行、**45** 条投诉、**4** 个外部事件。
- 关键事件：
  - `2026-06-02`：一个 R1 处理 AM 班次实际**短线折返**，22+1 条分流旅程改乘 R2 对照 AM1 班次（LF=1.17）；
    同车普通 R2 乘客 `J-DEMO-PURER2-01` 仍是纯净对照。
  - R1/PM3 的处理实例上司机多次不执行新规则（ITT 保留其处理臂，实际路径落为 `FULL_CONTROL`）。
  - `2026-06-15` 暴雨红色预警，R1/R2 早高峰停运：18 未完成刷卡 + 12 中途改线 + 16 可达性受损完成旅程。
  - 另有 `06-08` 降雨、`06-11` R3 施工、`06-19` R2 临时改线。投诉提交时间刻意滞后服务 1–6 天。

最近一次确定性流水线的主要结果（共同支持域，候车秒，负=改善）：

| 设计 | 估计量 | 估计 | 95% 区间 / p | 旅程数/单元 |
| --- | --- | --- | --- | --- |
| 班次随机 | ITT 候车 | **−66.7** | [−90.7, −42.8] | 2255 / 584 |
| 班次随机 | CACE/Wald | −86.1 | [−114.1, −58.1] | 2255 |
| 班次随机 | ITT 拥挤暴露 | −0.1 | [−1.4, 1.2]（不显著） | 2255 / 584 |
| 簇—周随机 | ITT 候车 | **−194.3** | [−235.1, −153.5]（CR1, df=7，偏乐观） | 2067 / 8 |
| 簇—周随机 | RI 置换 p | — | **p=0.177**（2⁴=16 置换；双侧最小可达 (1+2)/17≈0.176） | 2067 |
| 跨线暴露 | Wilson 率 | **18.3%** | [16.8%, 19.9%] | 2391 |

解读：簇内统一处理内部化了 R1/R2 换乘溢出，点估计更大；但只有 2 个簇、8 个簇—周单元，
渐近 CR1 区间偏窄且在少簇下反保守性不足，**随机化推断 p=0.177** 才是更诚实的不确定性证据——
这正是题目要求的“簇方案污染更小但可用随机化单元少、置信/分辨更弱”。

护栏最新值（独立于候车改善）：

| 护栏 | 观测 | 冻结阈值 | 结论 |
| --- | --- | --- | --- |
| 投诉率（/万乘客人次，按服务时间） | ≈145.9 | 45 | **BREACHED** |
| 滞留/未完成或候车 >1200s 占比 | 4.12% | 3% | **BREACHED** |
| LF≥1.0 满载班次占比 | 3.23% | 5% | OK（分流 R2 班次进入 Top 满载明细） |

因此扩围证据页与全局横幅均为 **BLOCKED**：即使平均候车显著改善，投诉与安全护栏超限仍独立阻止扩围。

---

## 六、区段依从者效应：资格账 · 轨迹 · 偏差图 · 单调性 · 双估计

这是本题的唯一因果目标。分析单元不是整条旅程，而是**区段（segment）**：临时改线/短线折返/加班的班次
按运行切点拆成多个区段，多个区段共享父班次的同一个随机分流分配 `Z`（工具变量），不另行随机。

### 1. 估计谁：随机分流诱导的依从者（CACE/LATE）

- `Z`：原**随机分流分配**（1=随机分入处理/分流诱导，0=随机对照），严格 50/50、平衡且确定性可复现。
- `D`：该区段是否**完整执行**新规则（二元；`FULL_EXECUTED=1` / `NOT_EXECUTED=0`）。
  少量 `PARTIAL`（部分执行）区段保留在资格账、轨迹与偏差图中，但不进入“完整/完整不执行”的二元队列，
  仅在连续执行剂量 `adherence` 的 Wald 敏感性中使用。
- 目标人群是主分层 **COMPLIER**：`D(1)=1, D(0)=0`，即“分入处理会被促使完整执行、分入对照则完整不执行”的区段。
  **两种方法都只报告这同一 Z 诱导依从者人群的平均效应与 95% 区间，绝不称为“所有完整执行区段的平均效应”**——
  按实际 `D` 分组的均值差只是受执行动机混杂的 `AS_TREATED` 观察性关联。

### 2. 五件交付物与 HTTP 入口（均在 `/api/experiments/{id}` 下，需登录）

| 交付物 | 端点 | 内容 |
| --- | --- | --- |
| 总报告/工作包 | `GET /compliance?scenario=` | 估计量定义、门控、双估计、单调性、组成、偏差图、资格汇总、样例区段、四场景矩阵 |
| 依从者效应报告 | `GET /compliance/report?scenario=` | 仅双估计 + 区间 + 拒绝原因 + 口径纪律 + AS_TREATED 对照 |
| ① 分流资格账 | `GET /compliance/eligibility` | 合格/无资格候选、六项资格准则 JSON、无资格原因分布（暴雨停运/非试点/车辆不能折返/无折返径路/错过窗口/运能不足） |
| ② 区段执行轨迹 | `GET /compliance/segments`、`/compliance/trajectories?segmentCode=` | 区段主账（切分序号、Z、主分层类型、执行状态/剂量、偏差、司机/车辆/基线拥堵、结果）与有序检查点时间线 |
| ③ 偏差原因图 | `GET /compliance/deviation-graph` | `类别(人/车/路/天/调度/规则) → 偏差原因 → 执行状态` 的 nodes/links 加权图 + 原因明细与样例区段 |
| ④ 单调性诊断 | `GET /compliance/monotonicity` | 臂×完整执行率表、显式 DEFIER 清单、按司机层的负条件第一阶段反例 |
| Wald-IV | `GET /compliance/wald` | `CACE=ITT_Y/第一阶段`、CR1 聚类 delta 区间、第一阶段稳健 F、连续剂量敏感性 |
| ⑤ 主分层模型 | `GET /compliance/principal-stratification` | Logistic 拟合 `P(D=1|Z=1,X)` 与 `P(D=1|Z=0,X)`，`c(X)=p1−p0` 为依从概率；组成诊断、重叠、分层 Wald 加权、400 次聚类 bootstrap 区间 |
| 确定性重建 | `POST /compliance/rebuild` | 分析师及以上；重置同一固定种子并重建两张表与 `analysis_result` 中的三行结果 |

同一组接口另挂 `/complier-report/**` 与 `/cace/**` 别名；OpenAPI 中带中文 `@Operation` 摘要，便于检索。
前端新增 **“🩺 依从者效应 CACE”** 标签页（`static/compliance.js`），可切换四个场景、点开区段轨迹弹窗。

### 3. 三类“拒绝估计”门控（`scenario=PRIMARY|DEFIER_CASE|WEAK_CASE|OVERLAP_CASE`）

| 门控 | 阈值/判据 | 触发场景的实测 | 服务端行为 |
| --- | --- | --- | --- |
| 强工具 | 第一阶段 `|ΔD|≥0.10` 且聚类稳健 **F≥10** | `WEAK_CASE`：ΔD≈0.09、F≈6.4 | Wald 与主分层均 `estimable=false / WEAK_INSTRUMENT`，估计键显式为 `null` |
| 单调性 | 无显式 DEFIER，且无两臂样本≥6、率差≤−0.18 的司机反向层 | `DEFIER_CASE`：26 个 DEFIER；司机 `DRV-077` 为 Z1 执行率 0、Z0 执行率 1（Δ=−1） | `MONOTONICITY_DEFIERS`，拒绝 Wald（主分层一并拒绝） |
| 模型重叠 | 5 个风险层中，显著质量层不得缺任一臂；预测依从者规模≥40 | `OVERLAP_CASE`：高拥堵+强约束高峰层被确定性全部分流，最高风险层缺 Z0 | Wald 仍可报；主分层 `estimable=false / NO_OVERLAP`，不输出模型点估计/区间 |

### 4. 主场景的确定性结果（固定种子，重建两次数字完全一致；候车秒，负=改善）

规模：PRIMARY 共 **759** 条资格账行（**687** 合格随机区段 + **72** 无资格候选），合格区段中 **18** 条 PARTIAL，
二元完整/不完整队列 **669** 区段、**582** 个随机化单元、乘客人权重约 855。

| 方法 | CACE 估计 | SE | 95% 区间 | 关键诊断 |
| --- | --- | --- | --- | --- |
| 随机分流 Wald-IV（CR1 聚类 delta） | **约 −102** | ≈10.0 | **[−122, −83]** | 第一阶段 ≈0.47，稳健 **F≈162**，单调成立 |
| 主分层依从模型加权（聚类 bootstrap） | **约 −106** | bootstrap SE ≈14 | **[−139, −81]** | 预测依从者 ≈44%，5 层均两臂重叠，400/400 次 bootstrap 有效 |

两法区间高度重合、指向同一 Z 诱导依从者人群；数值在重置库/重复 rebuild 后可复现（`Random.setSeed` 每次重建复位）。
`analysis_result` 另写 `CACE_WALD_SEGMENT`、`CACE_PRINCIPAL_STRAT`、`MONOTONICITY_DIAG` 三行（`design_type=SEGMENT_COMPLIANCE, scope=COMPLIER_LATE`），
因此 `GET /analysis` 与离线审阅包也包含它们（审阅包新增 `complierReportPrimary`/`complierScenarioMatrix` 键）。

> 统计口径说明：合成 DGP 中结果代价只由基线协变量/外部事件与“和 Z 独立抽取的结构性改线”决定，
> 刻意避免“Z 改变偏差标签、偏差标签再改变结果”的排他性破坏；未测执行动机 `m` 同时影响依从类型与候车，
> 因此 AS_TREATED 对比有偏，而随机 Z 的 Wald/IV 仍稳健。真实数据里主分层“类型”不可观测，
> 其组成与加权有效性依赖 Logistic/分层模型设定正确——这也是题目要求同时报告更稳健的 Wald 并在无重叠时拒绝模型的原因。

---

## 七、主要 HTTP API

所有 `/api/**` 需登录（表单 Cookie 或 Basic）。完整交互文档：`/swagger-ui.html`。

| 方法/路径 | 作用 |
| --- | --- |
| `GET /api/me` | 当前账号与角色 |
| `GET/POST /api/experiments`、`GET /api/experiments/{id}` | 列表/登记/详情 |
| `POST /api/experiments/{id}/register` | 冻结簇并生成两套分配与指纹 |
| `GET /api/experiments/{id}/network` | 实验前边、冻结簇、两设计有效单元与选择依据 |
| `GET /api/experiments/{id}/assignments?design=...` | `TRIP_RANDOMIZED` / `CLUSTER_WEEK` 分配清单 |
| `POST /api/experiments/{id}/pipeline/run` | 用冻结数据重算暴露账→支持层→估计→护栏 |
| `GET /api/experiments/{id}/exposure` | 暴露台账（可按 pathLabel/q 过滤） |
| `GET /api/experiments/{id}/journeys/{code}` | 单旅程：卡片 + 逐段 GPS/臂/偏离 + 判定追踪 |
| `GET /api/experiments/{id}/support` | 共同支持分层与覆盖缺口 |
| `GET /api/experiments/{id}/analysis` | 全部估计结果 |
| `GET/POST /api/experiments/{id}/guardrails[/evaluate]` | 护栏规则/最新评估/重新评估 |
| `GET /api/experiments/{id}/expansion`、`POST .../expansion/evaluate|decide` | 扩围证据与结论（APPROVER） |
| `GET /api/experiments/{id}/review-bundle` | 离线审阅包（冻结配置+分配哈希+追踪+结果+依从者报告） |
| `GET /api/experiments/{id}/compliance[/report|/eligibility|/segments|/trajectories|/deviation-graph|/monotonicity|/wald|/principal-stratification]`、`POST .../compliance/rebuild` | **区段依从者 CACE 五件产物与双估计/拒绝门控**；另有 `/complier-report/**`、`/cace/**` 别名，详见第六章 |
| `GET /api/experiments/{id}/events`、`/complaints` | 外部事件时间线、投诉时间关联核验 |

---

## 八、目录与实现说明

```
src/main/java/com/ccdev/transit/
  engine/            # 纯逻辑、无 Spring：确定性哈希、平衡分配、网络聚类、路径分类、统计/RI
                     #   + ComplianceStats：Wald-IV/第一阶段F、单调性反例、Logistic IRLS 主分层、重叠与 bootstrap
  service/           # 登记冻结、确定性播种器、分析流水线、扩围决策
                     #   + ComplianceService：区段 DGP/资格账/切分轨迹/偏差图/双估计装配与幂等重建
  web/               # REST 控制器与 DTO（+ ComplianceController，/compliance、/complier-report、/cace 三组别名）
  config/ security/  # Spring Security 三角色、OpenAPI、jsonb 响应处理
src/main/resources/
  schema.sql         # 幂等 PostgreSQL 建表（含 compliance_segment、segment_trajectory_event）
  static/            # 原生 JS 单页控制台（login/index/app/compliance/styles）
src/test/java/...    # 21 个纯 JUnit 测试（不依赖数据库/Docker，含 ComplianceStatsTest 5 例）
verify.sh            # 60 项端到端不变量（含区段依从者特性 26 项）
Dockerfile docker-compose.yml docker-settings.xml
```

关键口径：

- **分配臂 vs 实际暴露是两条轴**：ITT 永远按随机分配臂；司机不执行/短线折返不改变分配臂，只改变实际路径标签。
- **CROSS_MIXED 单独成类**：枢纽异臂搭乘、短线折返分流都进该类，绝不并入 `FULL_CONTROL`。
- **不把执行者子集叫随机效应**：实际路径/执行者均值以 `AS_TREATED_ASSOCIATION` 明确标注为观察性关联；因果口径只有 ITT 与带假设的 CACE。
- **未知保留**：`INCOMPLETE_TAP/MID_ROUTE_CHANGE` 不插补、不归臂、不进效应估计，只计数并进入安全护栏。
- **共同支持**：无支持层旅程不产生外推结论，仅在支持页与 `gaps` 中报告覆盖缺口。
- **时间基准**：天气/施工/改线与 `service_ts` 按窗口重叠关联；护栏投诉率按 `complaint.service_event_time` 归集。
- **依从者口径（本题）**：只报告随机分流 `Z` 诱导的主分层 COMPLIER（`D(1)=1,D(0)=0`）区段 CACE/LATE 及区间；
  中途改线按区段切分并共享父班次 `Z`；PARTIAL 不混入二元完整/不完整队列；弱工具（F<10 或 |ΔD|<0.10）、
  单调性反例（DEFIER/显著负条件第一阶段）或主分层无重叠时，对应方法 `estimable=false` 且估计键为显式 `null`，
  绝不用 AS_TREATED 均值或“所有完整执行区段效应”替代。
