package com.ccdev.transit.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区段依从者统计引擎的纯 JUnit 测试：
 * Wald 已知常数效应、第一阶段强度、单调性/反向依从者、主分层加权恢复与无重叠拒绝。
 */
class ComplianceStatsTest {

    private static final double TAU = 80.0;

    private ComplianceStats.Seg seg(int i, int z, String type, double risk, String driver) {
        int d = switch (type) {
            case "COMPLIER" -> z;
            case "ALWAYS_TAKER" -> 1;
            case "NEVER_TAKER" -> 0;
            default -> 1 - z; // DEFIER
        };
        double y = 400 - TAU * d; // 常数处理效应，无噪声 -> Wald/分层 Wald 必须精确恢复
        return new ComplianceStats.Seg(
                "S" + i, z, d, d == 1 ? 1.0 : 0.0, y, 1.0, "U" + i,
                risk, 0.3 + (i % 5) * 0.1, 0.2 + (i % 4) * 0.15, 0.25 + (i % 6) * 0.1,
                driver, type);
    }

    /** 60% 依从者 / 20% 始终 / 20% 绝不，z=i%2 保证每类两臂平衡。 */
    private List<ComplianceStats.Seg> strongMonotone(int n) {
        List<ComplianceStats.Seg> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int z = i % 2;
            int m = i % 5;
            String type = (m <= 2) ? "COMPLIER" : (m == 3 ? "NEVER_TAKER" : "ALWAYS_TAKER");
            rows.add(seg(i, z, type, (i + 1.0) / n, "D1"));
        }
        return rows;
    }

    @Test
    void waldRecoversKnownComplierEffectAndStrongInstrument() {
        List<ComplianceStats.Seg> rows = strongMonotone(210);
        ComplianceStats.Wald w = ComplianceStats.waldClustered(rows);
        assertTrue(w.computable(), "Wald 应可计算");
        // 第一阶段 = P(d=1|z1)-P(d=1|z0) = 0.6
        assertEquals(0.6, w.firstStage(), 1e-6);
        assertEquals(-TAU, w.estimate(), 1e-6, "Wald 比率必须恢复常数 CACE=-80");
        // 无噪声精确结构下 delta 方差恰好为 0、区间退化为点，故用非严格跨越断言
        assertTrue(w.se() >= 0 && !Double.isNaN(w.se()));
        assertTrue(w.ciLow() <= -TAU + 1e-9 && w.ciHigh() >= -TAU - 1e-9);
        assertTrue(w.firstStageF() >= 10, "强工具 F 应远大于 10，实际 " + w.firstStageF());
    }

    @Test
    void monotonicityAcceptsCleanTypesAndRejectsDefiers() {
        List<ComplianceStats.Seg> clean = strongMonotone(70);
        ComplianceStats.Monotonicity ok = ComplianceStats.monotonicity(clean, 0.18, 6);
        assertTrue(ok.holds(), "无 DEFIER 且总体第一阶段为正，应通过单调性");
        assertEquals(0, ok.defierCount());
        assertTrue(ok.reversals().isEmpty());

        // 加入 26 个明确反向依从者，集中在司机 DRV-X，两臂各半，D=1-Z
        List<ComplianceStats.Seg> bad = new ArrayList<>(clean);
        int base = 1000;
        for (int j = 0; j < 26; j++) {
            int z = j % 2;
            bad.add(seg(base + j, z, "DEFIER", 0.5, "DRV-X"));
        }
        ComplianceStats.Monotonicity badMono = ComplianceStats.monotonicity(bad, 0.18, 6);
        assertFalse(badMono.holds());
        assertEquals(26, badMono.defierCount());
        assertTrue(badMono.defierCodes().size() <= 50);
        assertTrue(badMono.reversals().stream().anyMatch(r -> "DRV-X".equals(r.stratum())
                && r.rateZ1() == 0.0 && r.rateZ0() == 1.0 && r.delta() <= -0.9),
                "DRV-X 应出现 0 vs 1 的完全反向层");
    }

    @Test
    void weakInstrumentHasSmallFirstStage() {
        List<ComplianceStats.Seg> rows = new ArrayList<>();
        int n = 180;
        for (int i = 0; i < n; i++) {
            int z = i % 2;
            // 约 96% 绝不，2% 依从，2% 始终 -> 第一阶段约 0.04
            String type = (i % 25 == 0) ? "COMPLIER" : (i % 25 == 1 ? "ALWAYS_TAKER" : "NEVER_TAKER");
            rows.add(seg(i, z, type, (i + 1.0) / n, "D" + (i % 9)));
        }
        ComplianceStats.Wald w = ComplianceStats.waldClustered(rows);
        assertTrue(Math.abs(w.firstStage()) < 0.1, "第一阶段应很弱，实际 " + w.firstStage());
        assertTrue(w.firstStageF() < 10, "弱工具 F 应 <10，实际 " + w.firstStageF());
    }

    @Test
    void principalStratificationRecoversEffectWithOverlap() {
        List<ComplianceStats.Seg> rows = strongMonotone(220);
        ComplianceStats.PrincipalResult p =
                ComplianceStats.principalStratification(rows, 5, 60, 12345L, 6, 30);
        assertTrue(p.estimable(), "强单调+重叠样本应可估计：" + p.refusalReason());
        assertTrue(p.overlap());
        assertEquals(-TAU, p.estimate(), 2.0, "常效应下层加权 Wald 应接近 -80");
        assertTrue(p.ciLow() <= p.estimate() && p.ciHigh() >= p.estimate());
        assertTrue(p.shareComplier() > 0.4, "预测依从者占比应约 0.6，实际 " + p.shareComplier());
        assertEquals(60, p.validReplicates(), "bootstrap 应全部有效");
    }

    @Test
    void principalStratificationRefusesWhenHighRiskBinHasNoControl() {
        int normal = 200, hard = 48, n = normal + hard;
        List<ComplianceStats.Seg> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean hardRow = i >= normal;
            int z = hardRow ? 1 : i % 2;          // 高约束层确定性全处理
            String type = i % 5 <= 2 ? "COMPLIER" : "NEVER_TAKER";
            double risk = hardRow ? 0.92 + 0.07 * ((i - normal) / (double) hard)
                    : 0.05 + 0.6 * ((i % normal) / (double) normal);
            rows.add(seg(i, z, type, risk, "D" + (i % 8)));
        }
        ComplianceStats.PrincipalResult p =
                ComplianceStats.principalStratification(rows, 5, 0, 1L, 6, 30);
        assertFalse(p.estimable());
        assertFalse(p.overlap());
        assertTrue(p.refusalReason() != null && p.refusalReason().contains("NO_OVERLAP"),
                "应以 NO_OVERLAP 拒绝，实际 " + p.refusalReason());
        assertTrue(p.overlapBinsMissingArm() >= 1);
    }
}
