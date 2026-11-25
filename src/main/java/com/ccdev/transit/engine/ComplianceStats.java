package com.ccdev.transit.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 区段级“随机分流诱导依从者效应（CACE/LATE）”统计引擎，全部为无外部依赖的可复现计算。
 *
 * <p>唯一目标估计量：<b>随机分流工具 Z 所诱导的依从者区段</b>（Z=1 时被诱导完整执行、
 * Z=0 时完整不执行，即 D(1)=1,D(0)=0）的新规则对区段等候时间的平均因果效应。
 * 两种估计途径都必须指向同一人群：
 * <ol>
 *   <li>{@link #waldClustered}：Wald/IV = ITT_Y / 第一阶段，对未测执行动机稳健，
 *       但要求单调性（不存在 DEFIER 反向依从者）与强工具；</li>
 *   <li>{@link #principalStratification}：用基线拥堵、司机约束、车辆约束拟合
 *       P(D=1|Z,X)，得主分层依从概率 c(X)=p1-p0，再按预测依从者规模对协变量层 Wald 加权，
 *       给出组成诊断；依赖依从模型正确且需要协变量重叠。</li>
 * </ol>
 * 本类只做计算；“是否拒绝估计”的门控由结果字段暴露，由服务/接口统一表述。
 */
public final class ComplianceStats {

    private ComplianceStats() {
    }

    /** 一个区段的估计输入。w=乘客权重；group=聚类随机化单元（班次/派发 unit_key）。 */
    public record Seg(
            String code,
            int z,            // 随机分流分配（工具）1/0
            int d,            // 是否完整执行新规则（二元处理）
            double dose,      // 连续执行程度 0..1（敏感性）
            double y,         // 区段等候时间（秒）
            double w,         // 乘客权重
            String group,
            double risk,      // 基线风险/约束指数（用于分层）
            double congestion,
            double driverConstraint,
            double vehicleConstraint,
            String driver,
            String type) {    // 虚构 DGP 的主分层真值（真实数据为 null）
    }

    public record Wald(
            boolean computable,
            double estimate, double se, double ciLow, double ciHigh, double pValue,
            double ittY, double firstStage, double firstStageSe, double firstStageF,
            int n, int groups, double df, String note) {
    }

    public record RateCell(int n, double weightedN, int executed, double rate) {
    }

    public record Reversal(
            String stratum, int nZ1, int nZ0, int execZ1, int execZ0,
            double rateZ1, double rateZ0, double delta) {
    }

    public record Monotonicity(
            boolean holds,
            int defierCount,
            List<String> defierCodes,
            RateCell z1, RateCell z0,
            double firstStageRateDiff,
            List<Reversal> reversals,
            String verdict) {
    }

    public record LogitModel(double[] beta, double[] mu, double[] sd, boolean converged) {
        public double probability(double congestion, double drvC, double vehC) {
            double[] x = {
                    1.0,
                    (congestion - mu[0]) / sd[0],
                    (drvC - mu[1]) / sd[1],
                    (vehC - mu[2]) / sd[2]
            };
            double eta = 0;
            for (int i = 0; i < beta.length; i++) eta += beta[i] * x[i];
            return sigmoid(eta);
        }
    }

    public record BinStats(
            int bin, int n, int nZ1, int nZ0,
            double mass, double complierShare, double fs, double ittY, double wald) {
    }

    public record PrincipalResult(
            boolean estimable,
            String refusalReason,
            double estimate, double seBoot, double ciLow, double ciHigh,
            int bootstrapReplicates, int validReplicates,
            double shareComplier, double shareAlways, double shareNever, double shareDefierScore,
            double ess, int overlapBinsMissingArm,
            boolean overlap,
            double[] logitTreatmentBeta, double[] logitControlBeta,
            List<BinStats> bins,
            Map<String, Double> compositionByCongestion,
            Map<String, Double> compositionByDriverConstraint,
            Map<String, Double> compositionByVehicleConstraint) {
    }

    /** 一个协变量层的第一阶段低于该值时，不纳入分层 Wald 加权，避免近零分母产生极端值。 */
    public static final double STRATUM_FS_MIN = 0.10;

    // ============================ Wald / IV ============================

    /**
     * 聚类稳健 Wald-IV。复用 {@link Stats#clusterCovariancePair} 的 2 参数 WLS + CR1 交叉 meat
     * 与 delta 方法方差；第一阶段 F = beta_D^2 / Var(beta_D)。
     */
    public static Wald waldClustered(List<Seg> rows) {
        if (rows == null || rows.size() < 8) {
            return new Wald(false, nan(), nan(), nan(), nan(), nan(), nan(), nan(), nan(), nan(),
                    rows == null ? 0 : rows.size(), 0, 1, "样本不足，无法计算 Wald/IV");
        }
        List<Stats.Observation> yo = new ArrayList<>();
        List<Stats.Observation> dO = new ArrayList<>();
        for (Seg s : rows) {
            String g = s.group() == null ? s.code() : s.group();
            yo.add(new Stats.Observation(s.y(), s.w(), s.z(), g));
            dO.add(new Stats.Observation(s.d(), s.w(), s.z(), g));
        }
        double[] zc;
        try {
            zc = Stats.clusterCovariancePair(yo, dO);
        } catch (RuntimeException e) {
            return new Wald(false, nan(), nan(), nan(), nan(), nan(), nan(), nan(), nan(), nan(),
                    rows.size(), 0, 1, "Wald/IV 矩阵退化：" + e.getMessage());
        }
        double betaY = zc[0], betaD = zc[1], vd11 = zc[4];
        double wald = zc[5], seW = zc[6];
        int groups = (int) yo.stream().map(Stats.Observation::group).distinct().count();
        double df = Math.max(1, groups - 1);
        double tcrit = Stats.tCritical(df);
        double fsSe = Math.sqrt(Math.max(vd11, 0));
        double f = vd11 > 0 ? betaD * betaD / vd11 : nan();
        if (Double.isNaN(wald) || Double.isInfinite(wald) || betaD == 0) {
            return new Wald(false, nan(), nan(), nan(), nan(), nan(), betaY, betaD, fsSe, f,
                    rows.size(), groups, df, "第一阶段为 0，Wald 比率不可定义");
        }
        double zscore = seW > 0 ? Math.abs(wald / seW) : Double.NaN;
        double p = Stats.normalTwoSidedP(zscore);
        return new Wald(true, round(wald, 3), round(seW, 3),
                round(wald - tcrit * seW, 2), round(wald + tcrit * seW, 2),
                round(p, 4), round(betaY, 3), round(betaD, 4), round(fsSe, 4), round(f, 2),
                rows.size(), groups, df,
                "Wald=ITT_Y/第一阶段；CR1 聚类 delta 方法 95% 区间（df=" + (int) df + "）");
    }

    /** 连续执行剂量 dose 作为第一阶段的 Wald 敏感性（不改变依从者人群口径，仅替换处理刻度）。 */
    public static double waldDose(List<Seg> rows) {
        List<Stats.Observation> yo = new ArrayList<>();
        List<Stats.Observation> dO = new ArrayList<>();
        for (Seg s : rows) {
            String g = s.group() == null ? s.code() : s.group();
            yo.add(new Stats.Observation(s.y(), s.w(), s.z(), g));
            dO.add(new Stats.Observation(s.dose(), s.w(), s.z(), g));
        }
        double[] zc = Stats.clusterCovariancePair(yo, dO);
        return zc[5];
    }

    // ============================ 单调性 ============================

    public static Monotonicity monotonicity(List<Seg> rows, double reversalMargin, int minArm) {
        RateCell z1 = rateCell(rows, 1);
        RateCell z0 = rateCell(rows, 0);
        double diff = z1.rate() - z0.rate();

        int defiers = 0;
        List<String> defierCodes = new ArrayList<>();
        for (Seg s : rows) {
            if ("DEFIER".equals(s.type())) {
                defiers++;
                if (defierCodes.size() < 50) defierCodes.add(s.code());
            }
        }

        // 可观测的“条件第一阶段为负”反例：按司机分层，两臂都有足够样本且率差显著为负
        Map<String, List<Seg>> byDriver = new LinkedHashMap<>();
        for (Seg s : rows) {
            byDriver.computeIfAbsent(s.driver() == null ? "UNKNOWN" : s.driver(), k -> new ArrayList<>()).add(s);
        }
        List<Reversal> reversals = new ArrayList<>();
        for (Map.Entry<String, List<Seg>> e : byDriver.entrySet()) {
            RateCell a = rateCell(e.getValue(), 1);
            RateCell b = rateCell(e.getValue(), 0);
            if (a.n() >= minArm && b.n() >= minArm && a.rate() - b.rate() <= -reversalMargin) {
                reversals.add(new Reversal(e.getKey(), a.n(), b.n(), a.executed(), b.executed(),
                        round(a.rate(), 3), round(b.rate(), 3), round(a.rate() - b.rate(), 3)));
            }
        }
        // 总体层面的强反向也作为一个反例层
        if (z1.n() >= minArm && z0.n() >= minArm && diff <= -reversalMargin) {
            reversals.add(0, new Reversal("__ALL__", z1.n(), z0.n(), z1.executed(), z0.executed(),
                    round(z1.rate(), 3), round(z0.rate(), 3), round(diff, 3)));
        }

        boolean holds = defiers == 0 && reversals.isEmpty();
        String verdict = holds
                ? "未发现反向依从者/单调性反例：各臂完整执行率满足 P(D=1|Z=1) ≥ P(D=1|Z=0)"
                : "发现单调性反例（显式 DEFIER=" + defiers + "，可观测反向层=" + reversals.size()
                  + "）：存在“分入处理反而更不执行”的证据，Wald/IV 必须拒绝估计";
        return new Monotonicity(holds, defiers, defierCodes, z1, z0, round(diff, 4), reversals, verdict);
    }

    private static RateCell rateCell(List<Seg> rows, int z) {
        int n = 0, exec = 0;
        double wn = 0;
        for (Seg s : rows) {
            if (s.z() != z || !eligibleForBinary(s)) continue;
            n++;
            exec += s.d();
            wn += s.w();
        }
        double rate = n == 0 ? Double.NaN : exec / (double) n;
        return new RateCell(n, round(wn, 1), exec, round(rate, 4));
    }

    /** 二元完整执行诊断只使用有明确完整/不完整轨迹的区段；服务层可先过滤 PARTIAL。 */
    private static boolean eligibleForBinary(Seg s) {
        return true;
    }

    // ============================ Logistic / 主分层 ============================

    public static double sigmoid(double eta) {
        if (eta >= 0) {
            double e = Math.exp(-eta);
            return 1 / (1 + e);
        }
        double e = Math.exp(eta);
        return e / (1 + e);
    }

    /** 三协变量 [congestion, driverConstraint, vehicleConstraint] 的标准化 Logistic IRLS（带岭项）。 */
    public static LogitModel fitLogit(List<Seg> rows, int arm) {
        int p = 4;
        int n = rows.size();
        double[] mu = new double[3], sd = new double[3];
        for (Seg s : rows) {
            mu[0] += s.congestion();
            mu[1] += s.driverConstraint();
            mu[2] += s.vehicleConstraint();
        }
        for (int j = 0; j < 3; j++) mu[j] /= Math.max(1, n);
        for (Seg s : rows) {
            sd[0] += sq(s.congestion() - mu[0]);
            sd[1] += sq(s.driverConstraint() - mu[1]);
            sd[2] += sq(s.vehicleConstraint() - mu[2]);
        }
        for (int j = 0; j < 3; j++) {
            sd[j] = Math.sqrt(sd[j] / Math.max(1, n));
            if (sd[j] < 1e-6) sd[j] = 1.0;
        }

        double[][] X = new double[n][p];
        double[] y = new double[n], w = new double[n];
        for (int i = 0; i < n; i++) {
            Seg s = rows.get(i);
            X[i][0] = 1;
            X[i][1] = (s.congestion() - mu[0]) / sd[0];
            X[i][2] = (s.driverConstraint() - mu[1]) / sd[1];
            X[i][3] = (s.vehicleConstraint() - mu[2]) / sd[2];
            y[i] = s.d();
            w[i] = s.w();
        }

        double[] beta = new double[p];
        boolean converged = false;
        double ridge = 1e-3;
        for (int iter = 0; iter < 60; iter++) {
            double[][] xtwx = new double[p][p];
            double[] xtwz = new double[p];
            double maxStep = 0;
            for (int i = 0; i < n; i++) {
                double eta = 0;
                for (int j = 0; j < p; j++) eta += beta[j] * X[i][j];
                double pr = sigmoid(eta);
                double wi = w[i] * Math.max(1e-4, pr * (1 - pr));
                double zwork = eta + (y[i] - pr) / Math.max(1e-4, pr * (1 - pr));
                for (int a = 0; a < p; a++) {
                    xtwz[a] += w[i] * (y[i] - pr) * 0 + wi * zwork * X[i][a];
                    for (int b = 0; b < p; b++) xtwx[a][b] += wi * X[i][a] * X[i][b];
                }
            }
            for (int a = 0; a < p; a++) xtwx[a][a] += ridge;
            double[] next = solve(xtwx, xtwz);
            if (next == null) break;
            for (int a = 0; a < p; a++) maxStep = Math.max(maxStep, Math.abs(next[a] - beta[a]));
            beta = next;
            if (maxStep < 1e-7) {
                converged = true;
                break;
            }
        }
        return new LogitModel(beta, mu, sd, converged);
    }

    /** 高斯消元（带部分主元）求解 A x = b；4 维小矩阵。 */
    private static double[] solve(double[][] A, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            }
            double tmpV = m[col][col];
            for (int j = 0; j <= n; j++) {
                double t = m[col][j];
                m[col][j] = m[piv][j];
                m[piv][j] = t;
            }
            if (Math.abs(m[col][col]) < 1e-12) return null;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double factor = m[r][col] / m[col][col];
                for (int j = col; j <= n; j++) m[r][j] -= factor * m[col][j];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = m[i][n] / m[i][i];
        return x;
    }

    // ============================ 主分层加权 CACE ============================

    public static double[] quantileCuts(List<Seg> rows, int bins) {
        List<Double> risks = new ArrayList<>();
        for (Seg s : rows) risks.add(s.risk());
        Collections.sort(risks);
        double[] cuts = new double[bins - 1];
        for (int b = 1; b < bins; b++) {
            int idx = (int) Math.floor(b * risks.size() / (double) bins);
            idx = Math.min(risks.size() - 1, Math.max(0, idx));
            cuts[b - 1] = risks.get(idx);
        }
        return cuts;
    }

    public static int binOf(double risk, double[] cuts) {
        int b = 0;
        for (double c : cuts) {
            if (risk > c) b++;
        }
        return b;
    }

    /**
     * 主分层依从模型。fitBootstrap&gt;0 时做聚类 bootstrap 给区间；否则只给点估计/组成。
     * 门控：协变量层两臂样本缺失（无重叠）或有效样本量过低 → estimable=false。
     */
    public static PrincipalResult principalStratification(
            List<Seg> rows, int bins, int bootstrapReps, long bootstrapSeed,
            int minArmPerBin, double minEss) {

        List<Seg> arm1 = rows.stream().filter(s -> s.z() == 1).toList();
        List<Seg> arm0 = rows.stream().filter(s -> s.z() == 0).toList();
        if (arm1.size() < 10 || arm0.size() < 10) {
            return refused("RANDOMIZATION_ARM_TOO_SMALL", "两随机臂样本不足，无法拟合主分层模型");
        }
        LogitModel m1 = fitLogit(arm1, 1);
        LogitModel m0 = fitLogit(arm0, 0);

        double[] cuts = quantileCuts(rows, bins);

        // 逐段预测主分层概率
        double sumW = 0, comp = 0, always = 0, never = 0, defScore = 0;
        double[] cScore = new double[rows.size()];
        int[] binN = new int[bins], binN1 = new int[bins], binN0 = new int[bins];
        double[] binW = new double[bins];
        for (int i = 0; i < rows.size(); i++) {
            Seg s = rows.get(i);
            double p1 = m1.probability(s.congestion(), s.driverConstraint(), s.vehicleConstraint());
            double p0 = m0.probability(s.congestion(), s.driverConstraint(), s.vehicleConstraint());
            double c = p1 - p0;
            cScore[i] = c;
            double w = s.w();
            sumW += w;
            comp += Math.max(0, c) * w;
            always += p0 * w;
            never += (1 - p1) * w;
            if (c < -0.05) defScore += w;
            int b = binOf(s.risk(), cuts);
            binN[b]++;
            binW[b] += w;
            if (s.z() == 1) binN1[b]++; else binN0[b]++;
        }

        // 重叠诊断：覆盖显著质量的层不允许缺臂
        int missing = 0;
        for (int b = 0; b < bins; b++) {
            boolean material = binN[b] >= Math.max(12, rows.size() / (bins * 3));
            if (material && (binN1[b] < minArmPerBin || binN0[b] < minArmPerBin)) missing++;
        }

        // 组成诊断（按基线维度三分位）
        Map<String, Double> byCong = compositionByBand(rows, m1, m0, Seg::congestion);
        Map<String, Double> byDrv = compositionByBand(rows, m1, m0, Seg::driverConstraint);
        Map<String, Double> byVeh = compositionByBand(rows, m1, m0, Seg::vehicleConstraint);

        // 分层 Wald + 依从者规模加权
        List<BinStats> binStats = new ArrayList<>();
        double num = 0, massTotal = 0;
        List<Double> complierWeights = new ArrayList<>();
        double sumC2 = 0, sumC = 0;
        for (int b = 0; b < bins; b++) {
            List<Seg> in = new ArrayList<>();
            double mass = 0;
            for (int i = 0; i < rows.size(); i++) {
                Seg s = rows.get(i);
                if (binOf(s.risk(), cuts) != b) continue;
                in.add(s);
                double c = Math.max(0, cScore[i]);
                mass += c * s.w();
                complierWeights.add(c * s.w());
                sumC += c * s.w();
                sumC2 += c * c * s.w() * s.w();
            }
            double fs = weightedRate(in, 1, true) - weightedRate(in, 0, true);
            double itt = weightedMeanY(in, 1) - weightedMeanY(in, 0);
            double waldB = Math.abs(fs) > 1e-6 ? itt / fs : Double.NaN;
            binStats.add(new BinStats(b, in.size(), binN1[b], binN0[b], round(mass, 2),
                    round(mass / Math.max(1e-9, binW[b]), 3), round(fs, 4), round(itt, 2),
                    Double.isNaN(waldB) ? nan() : round(waldB, 2)));
            if (Math.abs(fs) >= STRATUM_FS_MIN && !Double.isNaN(waldB) && mass > 0) {
                num += mass * waldB;
                massTotal += mass;
            }
        }
        double estimate = massTotal > 0 ? num / massTotal : nan();

        // 有效样本量 ESS = (Σ c w)^2 / Σ (c w)^2
        double ess = sumC2 > 0 ? sumC * sumC / sumC2 : 0;

        boolean overlap = missing == 0 && ess >= minEss;
        if (!overlap) {
            String reason = missing > 0 ? "NO_OVERLAP" : "INSUFFICIENT_COMPLIER_SUPPORT";
            PrincipalResult base = new PrincipalResult(false, reason, nan(), nan(), nan(), nan(),
                    0, 0, r4(comp / sumW), r4(always / sumW), r4(never / sumW), r4(defScore / sumW),
                    round(ess, 1), missing, false,
                    m1.beta(), m0.beta(), binStats, byCong, byDrv, byVeh);
            return base;
        }
        if (Double.isNaN(estimate)) {
            return new PrincipalResult(false, "STRATUM_FIRST_STAGE_ZERO", nan(), nan(), nan(), nan(),
                    0, 0, r4(comp / sumW), r4(always / sumW), r4(never / sumW), r4(defScore / sumW),
                    round(ess, 1), missing, false,
                    m1.beta(), m0.beta(), binStats, byCong, byDrv, byVeh);
        }

        // 聚类 bootstrap：固定切点，重采样随机化单元
        double seBoot = nan(), ciLow = nan(), ciHigh = nan();
        int valid = 0;
        List<Double> boot = new ArrayList<>();
        if (bootstrapReps > 0) {
            Map<String, List<Seg>> groups = new LinkedHashMap<>();
            for (Seg s : rows) groups.computeIfAbsent(s.group(), k -> new ArrayList<>()).add(s);
            List<String> gkeys = new ArrayList<>(groups.keySet());
            Random rnd = new Random(bootstrapSeed);
            for (int rep = 0; rep < bootstrapReps; rep++) {
                List<Seg> sample = new ArrayList<>();
                for (int g = 0; g < gkeys.size(); g++) {
                    String k = gkeys.get(rnd.nextInt(gkeys.size()));
                    sample.addAll(groups.get(k));
                }
                Double e = principalPointEstimate(sample, cuts, bins);
                if (e != null && Double.isFinite(e)) {
                    boot.add(e);
                    valid++;
                }
            }
            if (valid >= bootstrapReps * 0.8) {
                Collections.sort(boot);
                double mean = boot.stream().mapToDouble(Double::doubleValue).average().orElse(nan());
                double var = boot.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum()
                        / Math.max(1, boot.size() - 1);
                seBoot = Math.sqrt(var);
                ciLow = percentile(boot, 0.025);
                ciHigh = percentile(boot, 0.975);
            }
        }

        return new PrincipalResult(true, null, round(estimate, 2), round(seBoot, 2),
                round(ciLow, 2), round(ciHigh, 2), bootstrapReps, valid,
                r4(comp / sumW), r4(always / sumW), r4(never / sumW), r4(defScore / sumW),
                round(ess, 1), missing, true,
                m1.beta(), m0.beta(), binStats, byCong, byDrv, byVeh);
    }

    /** bootstrap 内部使用：给定固定切点求分层加权点估计；退化时返回 null。 */
    private static Double principalPointEstimate(List<Seg> sample, double[] cuts, int bins) {
        try {
            List<Seg> a1 = sample.stream().filter(s -> s.z() == 1).toList();
            List<Seg> a0 = sample.stream().filter(s -> s.z() == 0).toList();
            if (a1.size() < 5 || a0.size() < 5) return null;
            LogitModel m1 = fitLogit(a1, 1);
            LogitModel m0 = fitLogit(a0, 0);
            double num = 0, massTot = 0;
            for (int b = 0; b < bins; b++) {
                List<Seg> in = new ArrayList<>();
                for (Seg s : sample) if (binOf(s.risk(), cuts) == b) in.add(s);
                if (in.size() < 6) continue;
                double fs = weightedRate(in, 1, true) - weightedRate(in, 0, true);
                double itt = weightedMeanY(in, 1) - weightedMeanY(in, 0);
                if (Math.abs(fs) < STRATUM_FS_MIN) continue;
                double waldB = itt / fs;
                double mass = 0;
                for (Seg s : in) {
                    double c = m1.probability(s.congestion(), s.driverConstraint(), s.vehicleConstraint())
                            - m0.probability(s.congestion(), s.driverConstraint(), s.vehicleConstraint());
                    mass += Math.max(0, c) * s.w();
                }
                if (mass > 0 && Double.isFinite(waldB)) {
                    num += mass * waldB;
                    massTot += mass;
                }
            }
            return massTot > 0 ? num / massTot : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static PrincipalResult refused(String code, String detail) {
        String reason = code + (detail == null ? "" : ": " + detail);
        return new PrincipalResult(false, reason, nan(), nan(), nan(), nan(), 0, 0,
                nan(), nan(), nan(), nan(), 0, 0, false, null, null,
                List.of(), Map.of(), Map.of(), Map.of());
    }

    private static Map<String, Double> compositionByBand(
            List<Seg> rows, LogitModel m1, LogitModel m0,
            java.util.function.ToDoubleFunction<Seg> dim) {
        double[] sums = new double[3], wsum = new double[3], comp = new double[3];
        for (Seg s : rows) {
            double v = dim.applyAsDouble(s);
            int band = v < 0.33 ? 0 : v < 0.66 ? 1 : 2;
            double c = Math.max(0, m1.probability(s.congestion(), s.driverConstraint(), s.vehicleConstraint())
                    - m0.probability(s.congestion(), s.driverConstraint(), s.vehicleConstraint()));
            sums[band] += s.w();
            comp[band] += c * s.w();
            wsum[band] += s.w();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        String[] names = {"LOW", "MEDIUM", "HIGH"};
        for (int b = 0; b < 3; b++) out.put(names[b], wsum[b] > 0 ? r4(comp[b] / wsum[b]) : null);
        return out;
    }

    // ============================ 加权矩辅助 ============================

    private static double weightedRate(List<Seg> in, int arm, boolean fully) {
        double num = 0, wsum = 0;
        for (Seg s : in) {
            if (s.z() != arm) continue;
            num += (fully ? s.d() : s.dose()) * s.w();
            wsum += s.w();
        }
        return wsum == 0 ? Double.NaN : num / wsum;
    }

    private static double weightedMeanY(List<Seg> in, int arm) {
        List<Double> vals = new ArrayList<>(), ws = new ArrayList<>();
        for (Seg s : in) {
            if (s.z() != arm) continue;
            vals.add(s.y());
            ws.add(s.w());
        }
        if (vals.isEmpty()) return Double.NaN;
        return Stats.weightedMean(vals, ws);
    }

    /** 按实际是否完整执行分组的加权均值差（仅作 AS_TREATED 观察性对照，非因果）。 */
    public static double asTreatedDiff(List<Seg> rows) {
        List<Double> y1 = new ArrayList<>(), w1 = new ArrayList<>();
        List<Double> y0 = new ArrayList<>(), w0 = new ArrayList<>();
        for (Seg s : rows) {
            if (s.d() == 1) { y1.add(s.y()); w1.add(s.w()); } else { y0.add(s.y()); w0.add(s.w()); }
        }
        if (y1.isEmpty() || y0.isEmpty()) return nan();
        return Stats.weightedMean(y1, w1) - Stats.weightedMean(y0, w0);
    }

    private static double percentile(List<Double> sortedAsc, double q) {
        if (sortedAsc.isEmpty()) return nan();
        double pos = q * (sortedAsc.size() - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        if (lo == hi) return sortedAsc.get(lo);
        double frac = pos - lo;
        return sortedAsc.get(lo) * (1 - frac) + sortedAsc.get(hi) * frac;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double nan() {
        return Double.NaN;
    }

    private static double round(double v, int digits) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        double p = Math.pow(10, digits);
        return Math.round(v * p) / p;
    }

    private static double r4(double v) {
        return round(v, 4);
    }
}
