package com.ccdev.transit.service;

import com.ccdev.transit.engine.ComplianceStats;
import com.ccdev.transit.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 区段级依从者效应特性服务。
 *
 * <p>负责：确定性构造“分流资格账 + 区段执行轨迹”样例（含 PRIMARY 主场景与三类拒绝场景
 * DEFIER_CASE / WEAK_CASE / OVERLAP_CASE）、偏差原因图、单调性诊断，以及 Wald-IV 与
 * 主分层依从模型两种同一目标估计量的装配。所有数字由固定种子生成，可删除数据后复算复现。
 */
@Service
@Order(30)
public class ComplianceService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ComplianceService.class);

    public static final String PRIMARY = "PRIMARY";
    public static final String DEFIER = "DEFIER_CASE";
    public static final String WEAK = "WEAK_CASE";
    public static final String OVERLAP = "OVERLAP_CASE";

    /** 弱工具 Staiger-Stock 经验阈值与最小第一阶段依从差。 */
    public static final double WEAK_F_THRESHOLD = 10.0;
    public static final double MIN_FIRST_STAGE_DELTA = 0.10;
    /** 条件第一阶段负差超过该幅度且两臂样本足够，视为可观测单调性反例。 */
    public static final double REVERSAL_MARGIN = 0.18;
    public static final int REVERSAL_MIN_ARM = 6;
    public static final int BINS = 5;
    public static final int BOOT_REPS = 400;
    public static final long BOOT_SEED = 86086001L;
    public static final int MIN_ARM_PER_BIN = 6;
    /** 主分层模型要求的最小预测依从者加权规模。 */
    public static final double MIN_COMPLIER_MASS = 40.0;

    /** 显式 JSON null：即使全局 NON_NULL 也保留键，便于对接方区分“拒绝估计”与“缺字段”。 */
    private static final Object JSON_NULL =
            com.fasterxml.jackson.databind.node.NullNode.getInstance();

    private final JdbcTemplate jdbc;
    private final Random rng = new Random(86086001L);
    private final Map<String, Computed> cache = new ConcurrentHashMap<>();

    private int codeSeq = 0;
    private final List<Object[]> eventBatch = new ArrayList<>();

    public ComplianceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        try {
            Long expId = jdbc.queryForObject(
                    "SELECT id FROM experiment WHERE code=?", Long.class, SeedService.SAMPLE_CODE);
            if (expId == null) return;
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM compliance_segment WHERE experiment_id=?", Integer.class, expId);
            if (n == null || n == 0) {
                rebuild(expId);
                log.info("区段依从者分析样例已构造 exp={}", expId);
            }
        } catch (Exception e) {
            log.warn("区段依从者特性自检跳过：{}", e.getMessage());
        }
    }

    // ============================ 重建 / 播种 ============================

    @Transactional
    public synchronized void rebuild(long expId) {
        // 每次重建都重置到同一固定种子：保证 POST /rebuild 与全新库启动得到完全一致的样例
        rng.setSeed(BOOT_SEED);
        jdbc.update("DELETE FROM segment_trajectory_event WHERE experiment_id=?", expId);
        jdbc.update("DELETE FROM compliance_segment WHERE experiment_id=?", expId);
        cache.clear();
        codeSeq = 0;
        eventBatch.clear();

        List<SegD> all = new ArrayList<>();
        all.addAll(genPrimary());
        all.addAll(genDefier());
        all.addAll(genWeak());
        all.addAll(genOverlap());

        for (SegD s : all) {
            insertSegment(expId, s);
            buildEvents(expId, s);
        }
        if (!eventBatch.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO segment_trajectory_event
                      (experiment_id,segment_code,seq,checkpoint,status,label,detail)
                    VALUES (?,?,?,?,?,?,?)
                    """, eventBatch);
        }
        eventBatch.clear();
        persistResults(expId);
    }

    /** 仅依据已落库区段重算并写回 analysis_result（不重新造数）。 */
    public synchronized void persistResults(long expId) {
        cache.clear();
        Computed c = compute(expId, PRIMARY, true);
        jdbc.update("""
                DELETE FROM analysis_result WHERE experiment_id=?
                AND estimator_code IN ('CACE_WALD_SEGMENT','CACE_PRINCIPAL_STRAT','MONOTONICITY_DIAG')
                """, expId);

        boolean strong = isStrong(c.wald());
        boolean waldOk = c.mono().holds() && strong && c.wald().computable();
        Map<String, Object> wdetail = waldDetail(c, waldOk);
        if (waldOk) {
            ComplianceStats.Wald w = c.wald();
            insertResult(expId, "CACE_WALD_SEGMENT", w.estimate(), w.se(), w.ciLow(), w.ciHigh(),
                    w.pValue(), w.n(), w.groups(), wdetail);
        } else {
            insertResult(expId, "CACE_WALD_SEGMENT", null, null, null, null, null,
                    c.wald().n(), c.wald().groups(), wdetail);
        }

        boolean psOk = principalEstimable(c);
        Map<String, Object> pdetail = principalDetail(c, psOk);
        if (psOk) {
            ComplianceStats.PrincipalResult p = c.ps();
            insertResult(expId, "CACE_PRINCIPAL_STRAT", p.estimate(), p.seBoot(), p.ciLow(), p.ciHigh(),
                    null, c.binary().size(), BINS, pdetail);
        } else {
            insertResult(expId, "CACE_PRINCIPAL_STRAT", null, null, null, null, null,
                    c.binary().size(), BINS, pdetail);
        }

        Map<String, Object> mdetail = new LinkedHashMap<>();
        mdetail.put("holds", c.mono().holds());
        mdetail.put("defier_count", c.mono().defierCount());
        mdetail.put("reversal_strata", c.mono().reversals().size());
        mdetail.put("first_stage_rate_diff", c.mono().firstStageRateDiff());
        mdetail.put("verdict", c.mono().verdict());
        insertResult(expId, "MONOTONICITY_DIAG", c.mono().holds() ? 1.0 : 0.0, null, null, null, null,
                c.binary().size(), null, mdetail);
    }

    private void insertResult(long expId, String estimator, Double est, Double se, Double lo, Double hi,
                              Double p, int nSeg, Integer nUnits, Map<String, Object> detail) {
        jdbc.update("""
                INSERT INTO analysis_result(experiment_id,design_type,estimator_code,metric_code,scope,
                    estimate,se,ci_low,ci_high,p_value,n_journeys,n_units,detail)
                VALUES (?,'SEGMENT_COMPLIANCE',?,'wait_seconds','COMPLIER_LATE',?,?,?,?,?,?,?,?::jsonb)
                """, expId, estimator, est, se, lo, hi, p, nSeg, nUnits, Json.write(detail));
    }

    // ============================ 确定性 DGP ============================

    private List<LocalDate> weekdays() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        List<LocalDate> ds = new ArrayList<>();
        for (int i = 0; i < 28; i++) {
            LocalDate d = start.plusDays(i);
            if (d.getDayOfWeek().getValue() <= 5) ds.add(d);
        }
        return ds;
    }

    private List<Integer> balancedZ(int units) {
        List<Integer> z = new ArrayList<>();
        for (int i = 0; i < units; i++) z.add(i < units / 2 ? 1 : 0);
        java.util.Collections.shuffle(z, rng);
        return z;
    }

    private double gauss(double sd) {
        return rng.nextGaussian() * sd;
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private String newCode() {
        return String.format("CSEG-%05d", ++codeSeq);
    }

    private static final String[] DRIVERS = buildDrivers();

    private static String[] buildDrivers() {
        String[] d = new String[26];
        for (int i = 0; i < 26; i++) d[i] = String.format("DRV-%03d", i + 1);
        return d;
    }

    private double driverConstraint(String drv) {
        int idx = Integer.parseInt(drv.substring(4));
        // 确定性的司机约束画像 0.12..0.92
        double base = ((idx * 37) % 100) / 100.0;
        return clamp(0.12 + 0.8 * base + (((idx * 13) % 7) - 3) * 0.01, 0.08, 0.95);
    }

    private double vehicleConstraint(String veh) {
        int idx = Integer.parseInt(veh.substring(4));
        double base = ((idx * 53 + 11) % 100) / 100.0;
        return clamp(0.1 + 0.82 * base, 0.06, 0.96);
    }

    private String pickRoute() {
        double r = rng.nextDouble();
        return r < 0.4 ? "R1" : r < 0.78 ? "R2" : "R3";
    }

    private String pickBand() {
        double r = rng.nextDouble();
        return r < 0.42 ? "AM_PEAK" : r < 0.70 ? "PM_PEAK" : "OFF_PEAK";
    }

    private double congestionFor(String band, LocalDate date) {
        double base = switch (band) {
            case "AM_PEAK" -> 0.74;
            case "PM_PEAK" -> 0.68;
            default -> 0.42;
        };
        double c = base + gauss(0.12);
        if (date.equals(LocalDate.of(2026, 6, 15))) c += 0.22;
        if (date.equals(LocalDate.of(2026, 6, 8))) c += 0.08;
        return clamp(c, 0.05, 1.12);
    }

    private String congBand(double c) {
        return c < 0.4 ? "LOW" : c < 0.72 ? "MEDIUM" : "HIGH";
    }

    /** 单调主分层类型（无 DEFIER），概率依赖基线约束与未测执行动机 m。 */
    private String drawMonotoneType(double drvC, double vehC, double cong, double m) {
        double logitN = -1.7 + 2.0 * drvC + 1.5 * vehC + 0.7 * (cong - 0.5) - 1.05 * m;
        double pN = ComplianceStats.sigmoid(logitN);
        double logitA = -2.2 - 0.8 * drvC - 0.6 * vehC + 0.5 * m;
        double pA = ComplianceStats.sigmoid(logitA);
        pN = Math.min(0.62, pN);
        pA = Math.min(0.30, pA);
        if (pN + pA > 0.82) {
            double scale = 0.82 / (pN + pA);
            pN *= scale;
            pA *= scale;
        }
        double pC = 1 - pN - pA;
        double r = rng.nextDouble();
        if (r < pN) return "NEVER_TAKER";
        if (r < pN + pA) return "ALWAYS_TAKER";
        return "COMPLIER";
    }

    private int dFromType(String type, int z) {
        return switch (type) {
            case "COMPLIER" -> z;
            case "ALWAYS_TAKER" -> 1;
            case "NEVER_TAKER" -> 0;
            default -> z == 1 ? 0 : 1; // DEFIER: D=1-Z
        };
    }

    private double outcome(double baseWait, double cong, double risk, double m, int d,
                           String structuralDev, String kind, int segIndex,
                           LocalDate date, double vehC) {
        double y = Math.max(45, baseWait + gauss(12) + cong * 70 - 10 * m);
        double tau = 82 + 8 * risk; // 平均约 -86 秒的完整执行效应，轻微协变量异质
        y -= tau * d;
        // —— 以下代价只由“分配前基线协变量/事件”决定，对 Z 两臂对称，避免制造 Z→Y 的额外通道 ——
        if (vehC > 0.8) y += 14;                 // 车辆约束（基线）
        if (cong > 0.86) y += 20;                // 基线/沿线拥堵
        if (date.equals(LocalDate.of(2026, 6, 15))) y += 40;  // 暴雨红色预警
        else if (date.equals(LocalDate.of(2026, 6, 8))) y += 10; // 降雨
        // —— 结构性改线/折返在 u 编号上确定性选取，与随机 Z 独立 ——
        if ("MID_ROUTE_CHANGE".equals(structuralDev)) y += 80 + (segIndex == 2 ? 40 : 0);
        if ("SHORT_TURN".equals(structuralDev)) y += 120;
        if ("TRANSFER_WAIT".equals(kind) || "POST_CHANGE".equals(kind)) y += 50;
        return Math.max(40, Math.round(y * 10) / 10.0);
    }

    private SegD baseSeg(String scenario, LocalDate date) {
        SegD s = new SegD();
        s.code = newCode();
        s.scenario = scenario;
        s.date = date;
        s.week = ((date.getDayOfMonth() - 1) / 7) + 1;
        s.assignmentProb = 0.5;
        s.eligible = true;
        s.eligibilityStatus = "ELIGIBLE";
        s.segmentIndex = 1;
        s.segmentCount = 1;
        s.accessibility = true;
        s.weight = 1 + (rng.nextDouble() < 0.2 ? 1 : 0) + (rng.nextDouble() < 0.05 ? 1 : 0);
        return s;
    }

    // ---- PRIMARY：强工具、单调、有重叠；含中途改线切分/加班/少量部分执行 ----
    private List<SegD> genPrimary() {
        List<LocalDate> days = weekdays();
        int units = 600;
        List<Integer> zs = balancedZ(units);
        List<SegD> out = new ArrayList<>();
        List<SegD> partialCandidates = new ArrayList<>();

        for (int u = 0; u < units; u++) {
            LocalDate date = days.get(rng.nextInt(days.size()));
            String band = pickBand();
            String route = pickRoute();
            String drv = DRIVERS[rng.nextInt(DRIVERS.length)];
            String veh = String.format("VEH-%03d", 1 + rng.nextInt(30));
            double cong = congestionFor(band, date);
            double drvC = driverConstraint(drv);
            double vehC = vehicleConstraint(veh);
            double risk = clamp(0.45 * cong + 0.30 * drvC + 0.25 * vehC, 0, 1.3);
            double m = rng.nextGaussian();
            String type = drawMonotoneType(drvC, vehC, cong, m);
            int z = zs.get(u);
            int d = dFromType(type, z);

            boolean split = (u % 11 == 3) || (u % 17 == 5); // ~ 1/11 + 1/17 ≈ 36 个切分班次
            String structDev = null;
            boolean overtime = false, shortTurn = false, midChange = false;
            if (split) {
                int kind = u % 3;
                if (kind == 0) { structDev = "MID_ROUTE_CHANGE"; midChange = true; }
                else if (kind == 1) { structDev = "SHORT_TURN"; shortTurn = true; }
                else { structDev = "OVERTIME"; overtime = true; }
            }
            String unitKey = String.format("U-P-%04d", u);
            String tripCode = String.format("TRIP-P-%04d", u);

            // 偏差原因（非切分班次也有日常偏差）
            String deviation = structDev;
            if (deviation == null) {
                if ("NEVER_TAKER".equals(type) && z == 1 && rng.nextDouble() < 0.75) deviation = "DRIVER_REFUSAL";
                else if (vehC > 0.78 && d == 0 && rng.nextDouble() < 0.5) deviation = "VEHICLE_CONSTRAINT";
                else if (cong > 0.86 && rng.nextDouble() < 0.55) deviation = "CONGESTION";
                else if (date.equals(LocalDate.of(2026, 6, 15)) && rng.nextDouble() < 0.6) deviation = "WEATHER";
                else if (rng.nextDouble() < 0.06) deviation = "SCHEDULE_TIGHT";
                else deviation = "NONE";
            }

            int parts = split ? 2 : 1;
            for (int p = 1; p <= parts; p++) {
                SegD s = baseSeg(PRIMARY, date);
                s.z = z;
                s.arm = z == 1 ? "TREATMENT" : "CONTROL";
                s.unitKey = unitKey;
                s.tripCode = tripCode;
                s.route = route;
                s.band = band;
                s.driver = drv; s.drvC = drvC;
                s.vehicle = veh; s.vehC = vehC;
                s.cong = cong; s.congBand = congBand(cong);
                s.risk = risk;
                s.type = type;
                s.d = d;
                s.state = d == 1 ? "FULL_EXECUTED" : "NOT_EXECUTED";
                s.adherence = d == 1 ? clamp(0.96 + rng.nextDouble() * 0.04, 0, 1)
                        : rng.nextDouble() * 0.15;
                s.segmentIndex = p;
                s.segmentCount = parts;
                s.midRouteChange = midChange;
                s.overtime = overtime;
                s.shortTurn = shortTurn;
                s.splitFrom = parts == 2 ? tripCode : null;
                s.kind = parts == 1 ? "FIRST_WAIT" : (p == 1 ? "FIRST_WAIT" : (midChange ? "POST_CHANGE" : "TRANSFER_WAIT"));
                s.deviation = deviation;
                s.deviationDetail = deviationDetail(deviation, drv, veh);
                double baseWait = "AM_PEAK".equals(band) ? 255 : "PM_PEAK".equals(band) ? 278 : 312;
                if (p == 2) baseWait = 192;
                s.y = outcome(baseWait, cong, risk, m, d, structDev, s.kind, p, date, vehC);
                s.journeyCode = "J-SEG-P-" + String.format("%04d-%d", u, p);
                out.add(s);
                if ("NONE".equals(s.deviation)) partialCandidates.add(s);
            }
        }

        // 一小批“部分执行”区段：保留在资格/轨迹/偏差图，但从二元完整/不完整 Wald 队列中置为 PARTIAL
        int forced = 0;
        for (SegD s : partialCandidates) {
            if (forced >= 18) break;
            if (rng.nextDouble() < 0.6) {
                s.state = "PARTIAL";
                s.adherence = Math.round((0.45 + rng.nextDouble() * 0.4) * 100.0) / 100.0;
                s.d = 0;
                s.partialReason = "仅在部分站点/时段落实间隔压缩，GPS 轨迹显示规则动作不完整";
                if ("NONE".equals(s.deviation)) s.deviation = "PARTIAL_EXECUTION";
                forced++;
            }
        }

        // 分流资格账中的无资格候选（不随机、不进效应估计）
        out.addAll(genIneligible(days));
        return out;
    }

    private List<SegD> genIneligible(List<LocalDate> days) {
        String[][] reasons = {
                {"STORM_SUSPENSION", "暴雨红色预警停运/可达性受损，不具备安全分流条件"},
                {"ROUTE_NOT_IN_PILOT", "非试点线路或非合格班次"},
                {"VEHICLE_CANNOT_TURNBACK", "车辆/车型不具备枢纽短线折返条件"},
                {"NO_TURNBACK_TRACK", "前方站点无折返/绕行径路"},
                {"WINDOW_CLOSED", "临时改线/加班申请晚于分流资格截止时刻"},
                {"CAPACITY_INSUFFICIENT", "接续运能不足，分流会加剧滞留"}
        };
        List<SegD> out = new ArrayList<>();
        for (int i = 0; i < 72; i++) {
            LocalDate date = days.get(rng.nextInt(days.size()));
            String band = pickBand();
            SegD s = baseSeg(PRIMARY, date);
            s.eligible = false;
            s.eligibilityStatus = "INELIGIBLE";
            String[] rr = reasons[rng.nextInt(reasons.length)];
            s.ineligibleReason = rr[0];
            s.route = pickRoute();
            s.band = band;
            s.cong = congestionFor(band, date);
            s.congBand = congBand(s.cong);
            s.driver = DRIVERS[rng.nextInt(DRIVERS.length)];
            s.drvC = driverConstraint(s.driver);
            s.vehicle = String.format("VEH-%03d", 1 + rng.nextInt(30));
            s.vehC = vehicleConstraint(s.vehicle);
            s.risk = clamp(0.45 * s.cong + 0.3 * s.drvC + 0.25 * s.vehC, 0, 1.3);
            s.unitKey = null; s.z = null; s.arm = null;
            s.type = null; s.state = null; s.d = 0; s.adherence = 0;
            s.deviation = "NONE";
            s.y = null;
            s.kind = "FIRST_WAIT";
            s.journeyCode = "J-INELIG-" + String.format("%04d", i);
            Map<String, Object> checks = new LinkedHashMap<>();
            checks.put("route_in_pilot", !"ROUTE_NOT_IN_PILOT".equals(rr[0]));
            checks.put("service_accessible", !"STORM_SUSPENSION".equals(rr[0]));
            checks.put("vehicle_turnback_capable", !"VEHICLE_CANNOT_TURNBACK".equals(rr[0]));
            checks.put("stop_turnback_track", !"NO_TURNBACK_TRACK".equals(rr[0]));
            checks.put("before_eligibility_deadline", !"WINDOW_CLOSED".equals(rr[0]));
            checks.put("connecting_capacity_ok", !"CAPACITY_INSUFFICIENT".equals(rr[0]));
            checks.put("passed", false);
            checks.put("primary_reason", rr[1]);
            s.eligibilityChecks = Json.write(checks);
            out.add(s);
        }
        return out;
    }

    // ---- DEFIER_CASE：含一个“分入处理反而更不执行”的反向司机组 ----
    private List<SegD> genDefier() {
        int units = 120, rogues = 26;
        List<Integer> zs = balancedZ(units - rogues);
        List<LocalDate> days = weekdays();
        List<SegD> out = new ArrayList<>();
        int normalIdx = 0;
        for (int u = 0; u < units; u++) {
            boolean rogue = u < rogues;
            LocalDate date = days.get(rng.nextInt(days.size()));
            String band = pickBand();
            String drv = rogue ? "DRV-077" : DRIVERS[rng.nextInt(DRIVERS.length)];
            String veh = String.format("VEH-%03d", 1 + rng.nextInt(30));
            double cong = congestionFor(band, date);
            double drvC = rogue ? 0.9 : driverConstraint(drv);
            double vehC = vehicleConstraint(veh);
            double risk = clamp(0.45 * cong + 0.3 * drvC + 0.25 * vehC, 0, 1.3);
            double m = rng.nextGaussian();
            int z;
            String type;
            if (rogue) {
                z = u % 2; // 该司机两臂各半
                type = "DEFIER";
            } else {
                z = zs.get(normalIdx++);
                type = drawMonotoneType(drvC, vehC, cong, m);
            }
            int d = dFromType(type, z);
            SegD s = baseSeg(DEFIER, date);
            s.z = z; s.arm = z == 1 ? "TREATMENT" : "CONTROL";
            s.unitKey = String.format("U-D-%04d", u);
            s.tripCode = String.format("TRIP-D-%04d", u);
            s.route = pickRoute(); s.band = band;
            s.driver = drv; s.drvC = drvC; s.vehicle = veh; s.vehC = vehC;
            s.cong = cong; s.congBand = congBand(cong); s.risk = risk;
            s.type = type; s.d = d;
            s.state = d == 1 ? "FULL_EXECUTED" : "NOT_EXECUTED";
            s.adherence = d == 1 ? 0.98 : 0.05 + rng.nextDouble() * 0.1;
            s.kind = "FIRST_WAIT";
            s.deviation = "DEFIER".equals(type) && z == 1 ? "DRIVER_REFUSAL" : "NONE";
            s.deviationDetail = "DEFIER".equals(type)
                    ? "该司机在被随机分入新规则/分流诱导时反而更不执行（反向依从者）" : null;
            s.y = outcome(300, cong, risk, m, d, null, s.kind, 1, date, vehC);
            s.journeyCode = "J-SEG-D-" + String.format("%04d", u);
            out.add(s);
        }
        return out;
    }

    // ---- WEAK_CASE：几乎全是 NEVER_TAKER，第一阶段极弱 ----
    private List<SegD> genWeak() {
        int units = 180;
        List<Integer> zs = balancedZ(units);
        List<LocalDate> days = weekdays();
        List<SegD> out = new ArrayList<>();
        for (int u = 0; u < units; u++) {
            LocalDate date = days.get(rng.nextInt(days.size()));
            String band = pickBand();
            String drv = DRIVERS[rng.nextInt(DRIVERS.length)];
            String veh = String.format("VEH-%03d", 1 + rng.nextInt(30));
            double cong = congestionFor(band, date);
            double drvC = clamp(driverConstraint(drv) + 0.1, 0, 0.98);
            double vehC = vehicleConstraint(veh);
            double risk = clamp(0.45 * cong + 0.3 * drvC + 0.25 * vehC, 0, 1.3);
            double m = rng.nextGaussian();
            double r = rng.nextDouble();
            // 92% NEVER，4% COMPLIER，4% ALWAYS -> 第一阶段约 0.04（弱工具）
            String type = r < 0.92 ? "NEVER_TAKER" : r < 0.96 ? "COMPLIER" : "ALWAYS_TAKER";
            int z = zs.get(u);
            int d = dFromType(type, z);
            SegD s = baseSeg(WEAK, date);
            s.z = z; s.arm = z == 1 ? "TREATMENT" : "CONTROL";
            s.unitKey = String.format("U-W-%04d", u);
            s.tripCode = String.format("TRIP-W-%04d", u);
            s.route = pickRoute(); s.band = band;
            s.driver = drv; s.drvC = drvC; s.vehicle = veh; s.vehC = vehC;
            s.cong = cong; s.congBand = congBand(cong); s.risk = risk;
            s.type = type; s.d = d;
            s.state = d == 1 ? "FULL_EXECUTED" : "NOT_EXECUTED";
            s.adherence = d == 1 ? 0.97 : rng.nextDouble() * 0.12;
            s.kind = "FIRST_WAIT";
            s.deviation = ("NEVER_TAKER".equals(type) && z == 1) ? "DRIVER_REFUSAL" : "NONE";
            s.y = outcome(300, cong, risk, m, d, null, s.kind, 1, date, vehC);
            s.journeyCode = "J-SEG-W-" + String.format("%04d", u);
            out.add(s);
        }
        return out;
    }

    // ---- OVERLAP_CASE：高约束高峰层被确定性全部分流到 Z=1，主分层模型无对照重叠 ----
    private List<SegD> genOverlap() {
        int constrained = 48, rest = 132;
        List<Integer> zs = balancedZ(rest);
        List<LocalDate> days = weekdays();
        List<SegD> out = new ArrayList<>();
        for (int u = 0; u < constrained + rest; u++) {
            boolean hard = u < constrained;
            LocalDate date = days.get(rng.nextInt(days.size()));
            String band = hard ? "AM_PEAK" : pickBand();
            String drv = DRIVERS[rng.nextInt(DRIVERS.length)];
            String veh = String.format("VEH-%03d", 1 + rng.nextInt(30));
            double cong = hard ? 0.9 + rng.nextDouble() * 0.18 : congestionFor(band, date);
            double drvC = hard ? 0.72 + rng.nextDouble() * 0.24 : driverConstraint(drv);
            double vehC = hard ? 0.72 + rng.nextDouble() * 0.24 : vehicleConstraint(veh);
            double risk = clamp(0.45 * cong + 0.3 * drvC + 0.25 * vehC, 0, 1.35);
            double m = rng.nextGaussian();
            String type = drawMonotoneType(clamp(drvC, 0, .95), clamp(vehC, 0, .95), cong, m);
            int z = hard ? 1 : zs.get(u - constrained);
            int d = dFromType(type, z);
            SegD s = baseSeg(OVERLAP, date);
            s.z = z; s.arm = z == 1 ? "TREATMENT" : "CONTROL";
            s.unitKey = String.format("U-O-%04d", u);
            s.tripCode = String.format("TRIP-O-%04d", u);
            s.route = pickRoute(); s.band = band;
            s.driver = drv; s.drvC = drvC; s.vehicle = veh; s.vehC = vehC;
            s.cong = clamp(cong, 0, 1.15); s.congBand = congBand(s.cong); s.risk = risk;
            s.type = type; s.d = d;
            s.state = d == 1 ? "FULL_EXECUTED" : "NOT_EXECUTED";
            s.adherence = d == 1 ? 0.97 : rng.nextDouble() * 0.12;
            s.kind = "FIRST_WAIT";
            s.deviation = hard ? "CAPACITY_INSUFFICIENT" : "NONE";
            s.deviationDetail = hard ? "高拥堵+强司机/车辆约束的高峰层被运营规则确定性分流，未保留随机对照" : null;
            s.y = outcome(300, s.cong, risk, m, d, null, s.kind, 1, date, vehC);
            s.journeyCode = "J-SEG-O-" + String.format("%04d", u);
            out.add(s);
        }
        return out;
    }

    private String deviationDetail(String code, String drv, String veh) {
        if (code == null || "NONE".equals(code)) return null;
        return switch (code) {
            case "DRIVER_REFUSAL" -> "司机 " + drv + " 未按新规则落实动作（执行动机/排班原因）";
            case "VEHICLE_CONSTRAINT" -> "车辆 " + veh + " 车型/车门或动力约束导致无法完整执行";
            case "CONGESTION" -> "基线与沿线拥堵使计划间隔无法兑现";
            case "MID_ROUTE_CHANGE" -> "临时改线，按改线切点把班次切为两个区段";
            case "SHORT_TURN" -> "枢纽短线折返，拆出折返前与接续两个区段";
            case "OVERTIME" -> "临时加班/延时运营，拆出正班与加班区段";
            case "WEATHER" -> "暴雨/降雨事件窗口内运行降速或停运";
            case "SCHEDULE_TIGHT" -> "点时刻/排班约束压缩规则动作空间";
            case "PARTIAL_EXECUTION" -> "仅部分落实新规则动作";
            default -> code;
        };
    }

    // ============================ 落库 ============================

    private void insertSegment(long expId, SegD s) {
        jdbc.update("""
                INSERT INTO compliance_segment(
                  experiment_id,segment_code,scenario,journey_code,trip_code,unit_key,route_code,
                  service_date,week_index,period_band,segment_index,segment_count,segment_kind,
                  mid_route_change,split_from_code,overtime,short_turn,
                  diversion_eligible,eligibility_status,ineligible_reason,eligibility_checks,
                  z_assigned,assignment_arm,assignment_prob,
                  baseline_congestion,congestion_band,driver_id,driver_constraint,vehicle_id,vehicle_constraint,
                  risk_index,covariate_bin,principal_type,execution_state,d_fully_executed,adherence_rate,
                  partial_reason,deviation_code,deviation_detail,wait_seconds,passenger_weight)
                VALUES (?,?,?,?,?,?,?,
                        ?,?,?,?,?,?,
                        ?,?,?,?,
                        ?,?,?,?::jsonb,
                        ?,?,?,
                        ?,?,?,?,?,?,
                        ?,?,?,?,?,?,
                        ?,?,?,?,?)
                """,
                expId, s.code, s.scenario, s.journeyCode, s.tripCode, s.unitKey, s.route,
                java.sql.Date.valueOf(s.date), s.week, s.band, s.segmentIndex, s.segmentCount, s.kind,
                s.midRouteChange, s.splitFrom, s.overtime, s.shortTurn,
                s.eligible, s.eligibilityStatus, s.ineligibleReason, s.eligibilityChecks,
                s.z, s.arm, s.assignmentProb,
                r2(s.cong), s.congBand, s.driver, r3(s.drvC), s.vehicle, r3(s.vehC),
                r3(s.risk), null, s.type, s.state, s.d, r3(s.adherence),
                s.partialReason, s.deviation, s.deviationDetail,
                s.y == null ? null : Math.round(s.y * 10.0) / 10.0, s.weight);
    }

    private void ev(long expId, String code, int seq, String cp, String status, String label, String detail) {
        eventBatch.add(new Object[]{expId, code, seq, cp, status, label, detail});
    }

    private void buildEvents(long expId, SegD s) {
        int k = 1;
        if (!s.eligible) {
            ev(expId, s.code, k++, "ELIGIBILITY", "WARN", "分流资格审核未通过", s.ineligibleReason);
            ev(expId, s.code, k++, "EXCLUDE", "INFO", "不进行随机分流，不进入依从者效应估计", null);
            return;
        }
        ev(expId, s.code, k++, "ELIGIBILITY", "OK", "符合分流资格", "线路/可达性/车辆折返/时刻窗口等准则均通过");
        ev(expId, s.code, k++, "ASSIGNMENT", "INFO",
                "随机分流分配 " + ("TREATMENT".equals(s.arm) ? "Z=1 处理/分流诱导" : "Z=0 对照"),
                "随机化单元 " + s.unitKey + "，π=" + s.assignmentProb);
        ev(expId, s.code, k++, "DEPARTURE", "OK", "GPS/AVL 班次发出", s.tripCode + " · " + s.band);
        if (s.segmentCount == 2 && s.segmentIndex == 1) {
            String cp = s.midRouteChange ? "MID_ROUTE_SPLIT" : s.shortTurn ? "SHORT_TURN_SPLIT" : "OVERTIME_SPLIT";
            ev(expId, s.code, k++, cp, "WARN",
                    s.midRouteChange ? "中途临时改线，按切点切分区段" : s.shortTurn ? "短线折返，切分区段" : "临时加班，切出加班区段",
                    "本记录为第 1/2 区段，共享同一随机分配 Z");
        }
        if (s.segmentIndex == 2) {
            ev(expId, s.code, k++, "SEGMENT_CONTINUATION", "INFO", "第 2/2 区段（改线/接续/加班段）",
                    "共享父班次 " + s.splitFrom + " 的随机分配 Z，不另行随机");
        }
        String ruleLabel = switch (s.state == null ? "NONE" : s.state) {
            case "FULL_EXECUTED" -> "完整执行新规则";
            case "PARTIAL" -> "部分执行";
            default -> "完整不执行/旧规则";
        };
        ev(expId, s.code, k++, "RULE_ACTION",
                "FULL_EXECUTED".equals(s.state) ? "OK" : "PARTIAL".equals(s.state) ? "WARN" : "DEVIATION",
                ruleLabel, "执行程度 adherence=" + s.adherence);
        if (s.deviation != null && !"NONE".equals(s.deviation)) {
            ev(expId, s.code, k++, "DEVIATION", "DEVIATION", "偏差原因：" + reasonLabel(s.deviation),
                    s.deviationDetail);
        }
        if ("PARTIAL".equals(s.state) && s.partialReason != null) {
            ev(expId, s.code, k++, "PARTIAL_REASON", "WARN", "部分执行说明", s.partialReason);
        }
        if (s.y != null) {
            ev(expId, s.code, k, "OUTCOME", "INFO", "区段可归属等候 " + s.y + " 秒",
                    "乘客权重 " + s.weight);
        }
    }

    // ============================ 分析装配 ============================

    private Computed compute(long expId, String scenario, boolean withBootstrap) {
        String sc = normalizeScenario(scenario);
        String cacheKey = expId + ":" + sc + ":" + withBootstrap;
        Computed cached = cache.get(cacheKey);
        if (cached != null) return cached;

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM compliance_segment WHERE experiment_id=? AND scenario=? ORDER BY id", expId, sc);

        List<ComplianceStats.Seg> binary = new ArrayList<>();
        List<ComplianceStats.Seg> allDose = new ArrayList<>();
        int partial = 0, ineligible = 0;
        double totalWeight = 0;
        for (Map<String, Object> r : rows) {
            boolean eligible = Boolean.TRUE.equals(r.get("diversion_eligible"));
            if (!eligible) { ineligible++; continue; }
            Object zO = r.get("z_assigned");
            Object yO = r.get("wait_seconds");
            if (zO == null || yO == null) continue;
            String state = String.valueOf(r.get("execution_state"));
            if ("PARTIAL".equals(state)) partial++;
            ComplianceStats.Seg seg = toSeg(r);
            allDose.add(seg);
            totalWeight += seg.w();
            if (!"PARTIAL".equals(state)) binary.add(seg);
        }

        ComplianceStats.Wald wald = ComplianceStats.waldClustered(binary);
        ComplianceStats.Monotonicity mono =
                ComplianceStats.monotonicity(binary, REVERSAL_MARGIN, REVERSAL_MIN_ARM);
        ComplianceStats.PrincipalResult ps = ComplianceStats.principalStratification(
                binary, BINS, withBootstrap ? BOOT_REPS : 0, BOOT_SEED, MIN_ARM_PER_BIN, 30);

        Computed c = new Computed(sc, rows, binary, allDose, wald, mono, ps,
                partial, ineligible, totalWeight);
        cache.put(cacheKey, c);
        // 无 bootstrap 的结果也可被带 bootstrap 请求复用？键不同；避免缓存爆炸：只缓存主键
        cache.put(expId + ":" + sc + ":false", c);
        return c;
    }

    private ComplianceStats.Seg toSeg(Map<String, Object> r) {
        return new ComplianceStats.Seg(
                String.valueOf(r.get("segment_code")),
                num(r.get("z_assigned")).intValue(),
                num(r.get("d_fully_executed")).intValue(),
                num(r.get("adherence_rate")).doubleValue(),
                num(r.get("wait_seconds")).doubleValue(),
                Math.max(1, num(r.get("passenger_weight")).intValue()),
                r.get("unit_key") == null ? String.valueOf(r.get("segment_code")) : String.valueOf(r.get("unit_key")),
                num(r.get("risk_index")).doubleValue(),
                num(r.get("baseline_congestion")).doubleValue(),
                num(r.get("driver_constraint")).doubleValue(),
                num(r.get("vehicle_constraint")).doubleValue(),
                r.get("driver_id") == null ? "UNKNOWN" : String.valueOf(r.get("driver_id")),
                r.get("principal_type") == null ? null : String.valueOf(r.get("principal_type")));
    }

    public String normalizeScenario(String s) {
        if (s == null || s.isBlank()) return PRIMARY;
        return switch (s.trim().toUpperCase()) {
            case "DEFIER", "DEFIER_CASE", "MONOTONICITY", "MONOTONICITY_FAIL" -> DEFIER;
            case "WEAK", "WEAK_CASE", "WEAK_INSTRUMENT" -> WEAK;
            case "OVERLAP", "OVERLAP_CASE", "NO_OVERLAP", "PRINCIPAL" -> OVERLAP;
            default -> PRIMARY;
        };
    }

    private boolean isStrong(ComplianceStats.Wald w) {
        return w.computable()
                && Double.isFinite(w.firstStageF()) && w.firstStageF() >= WEAK_F_THRESHOLD
                && Math.abs(w.firstStage()) >= MIN_FIRST_STAGE_DELTA;
    }

    private boolean principalEstimable(Computed c) {
        if (!c.mono().holds() || !isStrong(c.wald()) || !c.ps().estimable()) return false;
        double complierMass = c.ps().shareComplier() * c.totalWeight;
        return complierMass >= MIN_COMPLIER_MASS;
    }

    // ============================ 对外报告 Map ============================

    public Map<String, Object> bundle(long expId, String scenarioRaw) {
        String sc = normalizeScenario(scenarioRaw);
        Computed c = compute(expId, sc, PRIMARY.equals(sc));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("experiment_id", expId);
        m.put("scenario", sc);
        m.put("scenario_label", scenarioLabel(sc));
        m.put("estimand", estimand());
        m.put("thresholds", thresholds());
        m.put("counts", counts(c));
        m.put("gates", gates(c));
        m.put("wald", waldMap(expId, sc));
        m.put("principal_stratification", principalMap(expId, sc));
        m.put("monotonicity", monotonicityMap(c));
        m.put("eligibility_summary", eligibilitySummary(expId, sc));
        m.put("deviation_graph", deviationGraph(expId, sc));
        m.put("sample_segments", sampleSegments(c));
        m.put("scenario_diagnostics", scenarioMatrix(expId));
        return m;
    }

    public Map<String, Object> report(long expId, String scenarioRaw) {
        String sc = normalizeScenario(scenarioRaw);
        Computed c = compute(expId, sc, PRIMARY.equals(sc));
        boolean waldOk = c.mono().holds() && isStrong(c.wald()) && c.wald().computable();
        boolean psOk = principalEstimable(c);
        List<String> reasons = refusalReasons(c, waldOk, psOk);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("experiment_id", expId);
        m.put("scenario", sc);
        m.put("scenario_label", scenarioLabel(sc));
        m.put("estimand", estimand());
        m.put("metric", "wait_seconds");
        m.put("unit", "秒");
        m.put("direction", "负值=新规则降低等候时间");
        m.put("wald", estimateBlock(c.wald(), waldOk, waldRefusal(c)));
        m.put("principal_stratification", psBlock(c, psOk));
        m.put("gates", gates(c));
        m.put("refusal_reasons", reasons);
        m.put("dual_estimable", waldOk && psOk);
        m.put("same_population_statement",
                "Wald-IV 与主分层加权估计的都是同一“随机分流 Z 诱导的依从者区段”人群的平均效应（CACE/LATE），"
                        + "区间为各自 95% 不确定性区间；两种方法不是两个不同人群。");
        m.put("not_all_fully_executed_warning",
                "不得把该效应称为“所有完整执行区段的平均效应”：按实际是否完整执行分组的均值差是观察性 AS_TREATED 关联，"
                        + "受未测执行动机混杂，不等于本报告的依从者因果效应。");
        double at = ComplianceStats.asTreatedDiff(c.binary);
        m.put("as_treated_association_for_contrast", Double.isNaN(at) ? null : Math.round(at * 100.0) / 100.0);
        return m;
    }

    public Map<String, Object> waldMap(long expId, String scRaw) {
        String sc = normalizeScenario(scRaw);
        Computed c = compute(expId, sc, false);
        boolean ok = c.mono().holds() && isStrong(c.wald()) && c.wald().computable();
        Map<String, Object> m = new LinkedHashMap<>();
        m.putAll(estimateBlock(c.wald(), ok, waldRefusal(c)));
        m.put("method", "随机分流 Wald 工具变量：CACE = (E[Y|Z=1]-E[Y|Z=0]) / (E[D|Z=1]-E[D|Z=0])");
        m.put("robustness", "直接使用随机分流 Z，对未测的司机执行动机较稳健");
        m.put("assumptions", List.of(
                "随机分流分配 Z 独立（随机化）",
                "排他性：Z 仅通过规则执行 D 影响等候（分流改线的直接代价已计入并在口径内）",
                "相关性：第一阶段足够强（F≥" + (int) WEAK_F_THRESHOLD + "）",
                "单调性：不存在 D(1)<D(0) 的反向依从者 DEFIER"));
        m.put("cluster", "按随机化单元 unit_key 聚类 CR1 + delta 方法区间");
        try {
            double dose = ComplianceStats.waldDose(c.allDose);
            m.put("dose_sensitivity_wald", Double.isFinite(dose) ? Math.round(dose * 100.0) / 100.0 : null);
            m.put("dose_sensitivity_note", "以连续 adherence 为处理刻度、纳入 PARTIAL 区段的敏感性比率（秒/单位执行程度）");
        } catch (RuntimeException e) {
            m.put("dose_sensitivity_wald", null);
        }
        return m;
    }

    public Map<String, Object> principalMap(long expId, String scRaw) {
        String sc = normalizeScenario(scRaw);
        Computed c = compute(expId, sc, PRIMARY.equals(sc));
        boolean ok = principalEstimable(c);
        Map<String, Object> m = new LinkedHashMap<>();
        m.putAll(psBlock(c, ok));
        ComplianceStats.PrincipalResult p = c.ps();
        m.put("method", "主分层依从模型：Logistic 拟合 P(D=1|Z=1,X) 与 P(D=1|Z=0,X)，"
                + "c(X)=p1-p0 为预测依从者概率，按各协变量层预测依从者规模对层 Wald 加权");
        m.put("covariates", List.of("baseline_congestion 基线拥堵", "driver_constraint 司机约束",
                "vehicle_constraint 车辆约束"));
        m.put("model_dependence", "组成诊断与加权都依赖依从模型设定正确（Logistic/分层/主分层可交换性）");
        m.put("composition", Map.of(
                "complier", p.shareComplier(),
                "always_taker", p.shareAlways(),
                "never_taker", p.shareNever(),
                "negative_score_share", p.shareDefierScore()));
        m.put("complier_mass", Math.round(p.shareComplier() * c.totalWeight * 10.0) / 10.0);
        m.put("effective_sample_size", p.ess());
        m.put("overlap", p.overlap());
        m.put("overlap_bins_missing_arm", p.overlapBinsMissingArm());
        m.put("composition_by_baseline_congestion", p.compositionByCongestion());
        m.put("composition_by_driver_constraint", p.compositionByDriverConstraint());
        m.put("composition_by_vehicle_constraint", p.compositionByVehicleConstraint());
        m.put("bins", p.bins());
        m.put("logit_treatment_beta_standardized", p.logitTreatmentBeta());
        m.put("logit_control_beta_standardized", p.logitControlBeta());
        m.put("ci_method", "按随机化单元聚类的确定性 bootstrap（" + BOOT_REPS + " 次，2.5/97.5 百分位）");
        return m;
    }

    public Map<String, Object> monotonicityReport(long expId, String scRaw) {
        Computed c = compute(expId, normalizeScenario(scRaw), false);
        return monotonicityMap(c);
    }

    public Map<String, Object> deviationGraph(long expId, String scRaw) {
        String sc = normalizeScenario(scRaw);
        List<Map<String, Object>> reasonRaw = jdbc.queryForList("""
                SELECT deviation_code AS code, count(*) AS n,
                       array_agg(segment_code ORDER BY segment_code) FILTER (WHERE segment_code IS NOT NULL) examples
                FROM compliance_segment
                WHERE experiment_id=? AND scenario=? AND diversion_eligible=TRUE
                GROUP BY 1 ORDER BY n DESC
                """, expId, sc);
        List<Map<String, Object>> stateRaw = jdbc.queryForList("""
                SELECT deviation_code AS code, execution_state AS state, count(*) AS n
                FROM compliance_segment
                WHERE experiment_id=? AND scenario=? AND diversion_eligible=TRUE
                GROUP BY 1,2
                """, expId, sc);

        Map<String, String[]> tax = reasonTaxonomy();
        Map<String, Integer> reasonCount = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> stateBreak = new LinkedHashMap<>();
        for (Map<String, Object> r : stateRaw) {
            String code = String.valueOf(r.get("code"));
            int n = ((Number) r.get("n")).intValue();
            reasonCount.merge(code, n, Integer::sum);
            String st = r.get("state") == null ? "UNKNOWN" : String.valueOf(r.get("state"));
            stateBreak.computeIfAbsent(code, k -> new ArrayList<>())
                    .add(Map.of("state", st, "label", stateLabel(st), "n", n));
        }

        // 原因明细（每个偏差 code 一行，含样例区段与执行状态构成）
        List<Map<String, Object>> reasonRows = new ArrayList<>();
        for (Map<String, Object> r : reasonRaw) {
            String code = String.valueOf(r.get("code"));
            Object ex = r.get("examples");
            List<Object> exampleList = new ArrayList<>();
            if (ex instanceof java.sql.Array arr) {
                try {
                    Object data = arr.getArray();
                    if (data instanceof Object[] oa) {
                        for (Object o : oa) exampleList.add(String.valueOf(o));
                    }
                } catch (java.sql.SQLException ignored) {
                    // 样例仅用于展示，转换失败时留空
                }
            }
            if (exampleList.size() > 6) exampleList = new ArrayList<>(exampleList.subList(0, 6));
            Map<String, Object> rr = new LinkedHashMap<>();
            rr.put("code", code);
            rr.put("label", reasonLabel(code));
            rr.put("category", tax.getOrDefault(code, new String[]{"OTHER", code})[0]);
            rr.put("n", ((Number) r.get("n")).intValue());
            rr.put("execution_states", stateBreak.getOrDefault(code, List.of()));
            rr.put("examples", exampleList);
            reasonRows.add(rr);
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();
        // 类别节点
        Map<String, String> catLabel = categoryLabels();
        for (Map.Entry<String, String> e : catLabel.entrySet()) {
            nodes.add(node("CAT:" + e.getKey(), e.getValue(), "category", 0));
        }
        // 原因节点 + 类别边 + 原因->执行状态边（按状态计数加权）
        for (Map.Entry<String, Integer> e : reasonCount.entrySet()) {
            String code = e.getKey();
            int cnt = e.getValue();
            String cat = tax.getOrDefault(code, new String[]{"OTHER", ""})[0];
            nodes.add(node("REASON:" + code, reasonLabel(code), "reason", cnt));
            links.add(link("CAT:" + cat, "REASON:" + code, cnt));
            for (Map<String, Object> sb : stateBreak.getOrDefault(code, List.of())) {
                String state = String.valueOf(sb.get("state"));
                int sn = ((Number) sb.get("n")).intValue();
                String stateId = "STATE:" + state;
                if (nodes.stream().noneMatch(n -> stateId.equals(n.get("id")))) {
                    nodes.add(node(stateId, stateLabel(state), "state", 0));
                }
                links.add(link("REASON:" + code, stateId, sn));
            }
        }
        // 类别节点计数
        for (Map<String, Object> n : nodes) {
            if ("category".equals(n.get("kind"))) {
                String cat = String.valueOf(n.get("id")).substring(4);
                int cnt = reasonCount.entrySet().stream()
                        .filter(en -> cat.equals(tax.getOrDefault(en.getKey(), new String[]{"OTHER", ""})[0]))
                        .mapToInt(Map.Entry::getValue).sum();
                n.put("count", cnt);
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenario", sc);
        m.put("nodes", nodes);
        m.put("links", links);
        m.put("reasons", reasonRows);
        m.put("taxonomy", taxonomyDescriptions());
        m.put("note", "偏差原因图：运营约束类别 → 具体偏差原因 → 区段执行状态；中途改线/折返/加班已按区段切分。");
        return m;
    }

    // ============================ 小工具/Map 构造 ============================

    private Map<String, Object> estimand() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", "ASSIGNMENT_INDUCED_COMPLIER_ATE");
        m.put("name", "随机分流诱导依从者区段的新规则平均效应（CACE/LATE）");
        m.put("population", "Z 从对照翻为处理时，完整执行状态由‘完整不执行’翻为‘完整执行’的区段（主分层 COMPLIER：D(1)=1,D(0)=0）");
        m.put("unit_of_analysis", "区段（中途改线按切点切分，共享父班次随机分配 Z）");
        m.put("outcome", "区段可归属等候时间 wait_seconds");
        m.put("binary_cohort_note", "二元 Wald/主分层队列只含 FULL_EXECUTED / NOT_EXECUTED 轨迹；PARTIAL 区段单列并仅进入连续剂量敏感性与偏差图");
        return m;
    }

    private Map<String, Object> thresholds() {
        return Map.of(
                "weak_f_threshold", WEAK_F_THRESHOLD,
                "min_first_stage_delta", MIN_FIRST_STAGE_DELTA,
                "reversal_margin", REVERSAL_MARGIN,
                "reversal_min_arm", REVERSAL_MIN_ARM,
                "bins", BINS,
                "min_arm_per_bin", MIN_ARM_PER_BIN,
                "min_complier_mass", MIN_COMPLIER_MASS,
                "bootstrap_replicates", BOOT_REPS);
    }

    private Map<String, Object> counts(Computed c) {
        Map<String, Object> m = new LinkedHashMap<>();
        long segTotal = c.rows.size();
        long eligible = c.rows.stream().filter(r -> Boolean.TRUE.equals(r.get("diversion_eligible"))).count();
        m.put("segment_rows_in_scenario", segTotal);
        m.put("eligible_segments", eligible);
        m.put("ineligible_entries", c.ineligible);
        m.put("randomized_with_outcome", c.allDose.size());
        m.put("partial_segments", c.partial);
        m.put("binary_full_or_none_cohort", c.binary.size());
        m.put("clusters", c.wald.groups());
        m.put("passenger_weight_total", Math.round(c.totalWeight * 10) / 10.0);
        Map<String, Long> byState = new LinkedHashMap<>();
        for (String st : List.of("FULL_EXECUTED", "PARTIAL", "NOT_EXECUTED")) {
            byState.put(st, c.rows.stream()
                    .filter(r -> st.equals(String.valueOf(r.get("execution_state")))).count());
        }
        m.put("by_execution_state", byState);
        return m;
    }

    private Map<String, Object> gates(Computed c) {
        ComplianceStats.Wald w = c.wald();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("monotonicity_holds", c.mono().holds());
        m.put("defier_count", c.mono().defierCount());
        m.put("observable_reversal_strata", c.mono().reversals().size());
        m.put("first_stage", w.computable() ? w.firstStage() : null);
        m.put("first_stage_f", w.computable() ? w.firstStageF() : null);
        m.put("instrument_strong", isStrong(w));
        m.put("overlap", c.ps().overlap());
        m.put("overlap_bins_missing_arm", c.ps().overlapBinsMissingArm());
        double complierMass = c.ps().shareComplier() * c.totalWeight;
        m.put("complier_mass", Math.round(complierMass * 10) / 10.0);
        m.put("complier_support_ok", complierMass >= MIN_COMPLIER_MASS);
        return m;
    }

    private Map<String, Object> monotonicityMap(Computed c) {
        ComplianceStats.Monotonicity mo = c.mono();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("holds", mo.holds());
        m.put("verdict", mo.verdict());
        m.put("first_stage_rate_diff", mo.firstStageRateDiff());
        m.put("table", Map.of(
                "z1_treatment_assigned", cell(mo.z1()),
                "z0_control_assigned", cell(mo.z0())));
        m.put("defier_count", mo.defierCount());
        m.put("defier_segment_codes", mo.defierCodes());
        m.put("observable_reversals", mo.reversals());
        m.put("interpretation", "真实数据中个体的 D(1),D(0) 不可同时观测；显式 DEFIER 计数来自虚构 DGP 真值，"
                + "可观测的负条件第一阶段（按司机层）是单调性反例的现实证据。出现任一强反例即拒绝 Wald 估计。");
        return m;
    }

    private Map<String, Object> cell(ComplianceStats.RateCell rc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", rc.n());
        m.put("weighted_n", rc.weightedN());
        m.put("fully_executed", rc.executed());
        m.put("full_execution_rate", rc.rate());
        return m;
    }

    private Map<String, Object> estimateBlock(ComplianceStats.Wald w, boolean estimable, String refusal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("estimable", estimable);
        m.put("refusal_reason", estimable ? null : refusal);
        m.put("estimate", estimable ? w.estimate() : JSON_NULL);
        m.put("se", estimable ? w.se() : JSON_NULL);
        m.put("ci_low", estimable ? w.ciLow() : JSON_NULL);
        m.put("ci_high", estimable ? w.ciHigh() : JSON_NULL);
        m.put("p_value", estimable ? w.pValue() : JSON_NULL);
        m.put("n_segments", w.n());
        m.put("n_randomization_units", w.groups());
        m.put("df", w.df());
        m.put("reduced_form_itt_y", w.computable() ? nullSafe(w.ittY()) : null);
        m.put("first_stage_d", w.computable() ? nullSafe(w.firstStage()) : null);
        m.put("first_stage_se", w.computable() ? nullSafe(w.firstStageSe()) : null);
        m.put("first_stage_f_cluster_robust", w.computable() ? nullSafe(w.firstStageF()) : null);
        return m;
    }

    private Map<String, Object> psBlock(Computed c, boolean ok) {
        ComplianceStats.PrincipalResult p = c.ps();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("estimable", ok);
        String refusal = ok ? null : principalRefusal(c);
        m.put("refusal_reason", refusal);
        m.put("estimate", ok ? p.estimate() : JSON_NULL);
        m.put("se_bootstrap", ok ? p.seBoot() : JSON_NULL);
        m.put("ci_low", ok ? p.ciLow() : JSON_NULL);
        m.put("ci_high", ok ? p.ciHigh() : JSON_NULL);
        m.put("bootstrap_replicates", p.bootstrapReplicates());
        m.put("bootstrap_valid_replicates", p.validReplicates());
        m.put("n_segments", c.binary.size());
        m.put("share_complier_predicted", p.shareComplier());
        m.put("overlap", p.overlap());
        m.put("overlap_bins_missing_arm", p.overlapBinsMissingArm());
        return m;
    }

    private String waldRefusal(Computed c) {
        if (!c.mono().holds()) return "MONOTONICITY_DEFIERS: 存在反向依从者/单调性反例，Wald/IV 拒绝估计";
        if (!c.wald().computable()) return "WALD_NOT_COMPUTABLE: 第一阶段为 0 或矩阵退化";
        if (!isStrong(c.wald()))
            return "WEAK_INSTRUMENT: 第一阶段 F=" + r2(c.wald().firstStageF()) + " < " + WEAK_F_THRESHOLD
                    + " 或依从差 |ΔD|=" + r3(Math.abs(c.wald().firstStage())) + " < " + MIN_FIRST_STAGE_DELTA;
        return null;
    }

    private String principalRefusal(Computed c) {
        if (!c.mono().holds()) return "MONOTONICITY_DEFIERS: 主分层单调性不成立，拒绝模型估计";
        if (!isStrong(c.wald())) return "WEAK_INSTRUMENT: 依从者规模/第一阶段过弱，主分层权重不稳定";
        if (!c.ps().estimable()) return c.ps().refusalReason();
        double mass = c.ps().shareComplier() * c.totalWeight;
        if (mass < MIN_COMPLIER_MASS)
            return "INSUFFICIENT_COMPLIER_MASS: 预测依从者加权规模 " + Math.round(mass)
                    + " < " + (int) MIN_COMPLIER_MASS;
        return c.ps().refusalReason();
    }

    private List<String> refusalReasons(Computed c, boolean waldOk, boolean psOk) {
        List<String> r = new ArrayList<>();
        String wr = waldRefusal(c);
        if (!waldOk && wr != null) r.add(wr);
        String pr = principalRefusal(c);
        if (!psOk && pr != null && !pr.equals(wr)) r.add(pr);
        if (r.isEmpty()) r.add("NONE: 强工具、单调性成立且协变量重叠，两种估计均可报告");
        return r;
    }

    private Map<String, Object> waldDetail(Computed c, boolean ok) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("estimable", ok);
        m.put("refusal_reason", ok ? null : waldRefusal(c));
        m.put("estimand_code", "ASSIGNMENT_INDUCED_COMPLIER_ATE");
        m.put("estimand", "随机分流诱导依从者区段 CACE/LATE（非所有完整执行区段）");
        m.put("first_stage", c.wald().computable() ? r4(c.wald().firstStage()) : null);
        m.put("first_stage_f", c.wald().computable() ? r2(c.wald().firstStageF()) : null);
        m.put("monotonicity_holds", c.mono().holds());
        m.put("defier_count", c.mono().defierCount());
        m.put("method", "Wald/IV cluster-robust delta CI");
        return m;
    }

    private Map<String, Object> principalDetail(Computed c, boolean ok) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("estimable", ok);
        m.put("refusal_reason", ok ? null : principalRefusal(c));
        m.put("estimand_code", "ASSIGNMENT_INDUCED_COMPLIER_ATE");
        m.put("method", "principal-stratification complier-score weighted stratum Wald + cluster bootstrap");
        m.put("share_complier", c.ps().shareComplier());
        m.put("overlap", c.ps().overlap());
        m.put("composition_by_congestion", c.ps().compositionByCongestion());
        m.put("composition_by_driver", c.ps().compositionByDriverConstraint());
        m.put("composition_by_vehicle", c.ps().compositionByVehicleConstraint());
        return m;
    }

    public Map<String, Object> eligibilitySummaryPublic(long expId, String sc) {
        return eligibilitySummary(expId, sc);
    }

    private Map<String, Object> eligibilitySummary(long expId, String sc) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT eligibility_status, count(*) n FROM compliance_segment
                WHERE experiment_id=? AND scenario=? GROUP BY 1
                """, expId, sc);
        Map<String, Object> m = new LinkedHashMap<>();
        long eligible = 0, ineligible = 0;
        for (Map<String, Object> r : rows) {
            if ("ELIGIBLE".equals(r.get("eligibility_status"))) eligible = ((Number) r.get("n")).longValue();
            else ineligible += ((Number) r.get("n")).longValue();
        }
        m.put("eligible", eligible);
        m.put("ineligible", ineligible);
        m.put("total", eligible + ineligible);
        List<Map<String, Object>> reasons = jdbc.queryForList("""
                SELECT ineligible_reason, count(*) n FROM compliance_segment
                WHERE experiment_id=? AND scenario=? AND eligibility_status='INELIGIBLE'
                GROUP BY 1 ORDER BY n DESC
                """, expId, sc);
        m.put("ineligible_reasons", reasons);
        return m;
    }

    private List<Map<String, Object>> sampleSegments(Computed c) {
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        for (Map<String, Object> r : c.rows) {
            if (n++ >= 12) break;
            Map<String, Object> s = new LinkedHashMap<>();
            for (String k : List.of("segment_code", "journey_code", "trip_code", "route_code",
                    "segment_index", "segment_count", "segment_kind", "mid_route_change", "overtime",
                    "z_assigned", "assignment_arm", "principal_type", "execution_state",
                    "adherence_rate", "deviation_code", "driver_id", "baseline_congestion",
                    "driver_constraint", "vehicle_constraint", "wait_seconds")) {
                s.put(k, r.get(k));
            }
            out.add(s);
        }
        return out;
    }

    private List<Map<String, Object>> scenarioMatrix(long expId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String sc : List.of(PRIMARY, DEFIER, WEAK, OVERLAP)) {
            Computed c = compute(expId, sc, false);
            boolean waldOk = c.mono().holds() && isStrong(c.wald()) && c.wald().computable();
            boolean psOk = principalEstimable(c);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scenario", sc);
            m.put("scenario_label", scenarioLabel(sc));
            m.put("monotonicity_holds", c.mono().holds());
            m.put("defier_count", c.mono().defierCount());
            m.put("first_stage", c.wald().computable() ? r4(c.wald().firstStage()) : null);
            m.put("first_stage_f", c.wald().computable() ? r2(c.wald().firstStageF()) : null);
            m.put("instrument_strong", isStrong(c.wald()));
            m.put("overlap", c.ps().overlap());
            m.put("wald_estimable", waldOk);
            m.put("principal_estimable", psOk);
            m.put("wald_estimate_if_reported", waldOk ? c.wald().estimate() : JSON_NULL);
            m.put("principal_estimate_if_reported", psOk ? c.ps().estimate() : JSON_NULL);
            String wr = waldRefusal(c);
            String pr = principalRefusal(c);
            List<String> refs = new ArrayList<>();
            if (wr != null) refs.add(wr);
            if (pr != null && !pr.equals(wr)) refs.add(pr);
            if (refs.isEmpty()) refs.add("NONE");
            m.put("refusal", refs);
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> node(String id, String label, String kind, int count) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id); n.put("label", label); n.put("kind", kind); n.put("count", count);
        return n;
    }

    private Map<String, Object> link(String source, String target, int weight) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("source", source); l.put("target", target); l.put("weight", weight);
        return l;
    }

    private Map<String, String> categoryLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("PEOPLE", "人 · 司机/执行动机");
        m.put("VEHICLE", "车 · 车辆/运能约束");
        m.put("ROAD", "路 · 拥堵/改线");
        m.put("WEATHER", "天 · 外部事件");
        m.put("DISPATCH", "调度 · 折返/加班/排班");
        m.put("RULE", "规则执行");
        m.put("OK", "正常");
        m.put("OTHER", "其他");
        return m;
    }

    /** code -> [category, description] */
    private Map<String, String[]> reasonTaxonomy() {
        Map<String, String[]> t = new LinkedHashMap<>();
        t.put("NONE", new String[]{"OK", "无偏差，按计划执行"});
        t.put("DRIVER_REFUSAL", new String[]{"PEOPLE", "司机不执行/反向依从"});
        t.put("SCHEDULE_TIGHT", new String[]{"DISPATCH", "排班/点时刻约束"});
        t.put("VEHICLE_CONSTRAINT", new String[]{"VEHICLE", "车型/车门/动力约束"});
        t.put("CAPACITY_INSUFFICIENT", new String[]{"VEHICLE", "接续/满载运能不足"});
        t.put("CONGESTION", new String[]{"ROAD", "沿线/基线拥堵"});
        t.put("MID_ROUTE_CHANGE", new String[]{"ROAD", "中途临时改线"});
        t.put("SHORT_TURN", new String[]{"DISPATCH", "短线折返"});
        t.put("OVERTIME", new String[]{"DISPATCH", "临时加班/延时"});
        t.put("WEATHER", new String[]{"WEATHER", "暴雨/降雨"});
        t.put("PARTIAL_EXECUTION", new String[]{"RULE", "部分执行新规则"});
        return t;
    }

    private List<Map<String, String>> taxonomyDescriptions() {
        List<Map<String, String>> out = new ArrayList<>();
        Map<String, String[]> t = reasonTaxonomy();
        Map<String, String> cat = categoryLabels();
        t.forEach((code, cd) -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("category", cd[0]);
            m.put("category_label", cat.getOrDefault(cd[0], cd[0]));
            m.put("description", cd[1]);
            out.add(m);
        });
        return out;
    }

    public String reasonLabel(String code) {
        if (code == null) return "未知";
        return switch (code) {
            case "NONE" -> "无偏差";
            case "DRIVER_REFUSAL" -> "司机不执行/抵触";
            case "SCHEDULE_TIGHT" -> "排班时刻紧张";
            case "VEHICLE_CONSTRAINT" -> "车辆约束";
            case "CAPACITY_INSUFFICIENT" -> "运能不足/满载";
            case "CONGESTION" -> "拥堵";
            case "MID_ROUTE_CHANGE" -> "中途改线";
            case "SHORT_TURN" -> "短线折返";
            case "OVERTIME" -> "临时加班";
            case "WEATHER" -> "天气扰动";
            case "PARTIAL_EXECUTION" -> "部分执行";
            default -> code;
        };
    }

    private String stateLabel(String st) {
        return switch (st) {
            case "FULL_EXECUTED" -> "完整执行";
            case "PARTIAL" -> "部分执行";
            case "NOT_EXECUTED" -> "完整不执行";
            default -> st;
        };
    }

    public String scenarioLabel(String sc) {
        return switch (sc) {
            case DEFIER -> "反例场景：反向依从者（单调性失败，应拒绝 Wald）";
            case WEAK -> "弱工具场景：几乎无依从差（应拒绝估计）";
            case OVERLAP -> "无重叠场景：高约束层缺随机对照（主分层模型应拒绝）";
            default -> "主场景：强工具·单调·有重叠（可报告双估计）";
        };
    }

    private static Double num(Object o) {
        return o == null ? 0d : ((Number) o).doubleValue();
    }

    private static Double nullSafe(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : Math.round(v * 1000.0) / 1000.0;
    }

    private static double r2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 100.0) / 100.0;
    }

    private static double r3(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static double r4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 10000.0) / 10000.0;
    }

    private record Computed(
            String scenario,
            List<Map<String, Object>> rows,
            List<ComplianceStats.Seg> binary,
            List<ComplianceStats.Seg> allDose,
            ComplianceStats.Wald wald,
            ComplianceStats.Monotonicity mono,
            ComplianceStats.PrincipalResult ps,
            int partial,
            int ineligible,
            double totalWeight) {
    }

    /** 生成期可变字段袋。 */
    private static final class SegD {
        String code, scenario, journeyCode, tripCode, unitKey, route, band, arm;
        LocalDate date;
        int week, segmentIndex, segmentCount, weight, d;
        Integer z;
        String kind, splitFrom, driver, vehicle, congBand, type, state, partialReason;
        boolean midRouteChange, overtime, shortTurn, eligible, accessibility;
        String eligibilityStatus, ineligibleReason, eligibilityChecks;
        Double assignmentProb;
        double cong, drvC, vehC, risk, adherence;
        String deviation = "NONE", deviationDetail;
        Double y;
    }
}
