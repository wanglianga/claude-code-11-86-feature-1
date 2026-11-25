package com.ccdev.transit.web;

import com.ccdev.transit.service.ComplianceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 区段级“随机分流诱导依从者效应（CACE/LATE）”API。
 * 路径同时挂载在 /compliance 与 /complier-report（及 /cace）下，便于发现与对接。
 */
@RestController
@RequestMapping({
        "/api/experiments/{id}/compliance",
        "/api/experiments/{id}/complier-report",
        "/api/experiments/{id}/complier-effect",
        "/api/experiments/{id}/compliance-report",
        "/api/experiments/{id}/cace"
})
@Tag(name = "区段依从者效应 CACE/LATE",
        description = "分流资格账、区段执行轨迹、偏差原因图、单调性诊断、Wald-IV 与主分层依从模型")
public class ComplianceController {

    private final ComplianceService compliance;
    private final JdbcTemplate jdbc;

    public ComplianceController(ComplianceService compliance, JdbcTemplate jdbc) {
        this.compliance = compliance;
        this.jdbc = jdbc;
    }

    @Operation(summary = "依从者效应完整工作包：资格账/轨迹/偏差图/单调性/双估计/三拒绝场景矩阵")
    @GetMapping({"", "/"})
    public Map<String, Object> bundle(@PathVariable long id,
                                      @RequestParam(required = false, defaultValue = "PRIMARY") String scenario) {
        return compliance.bundle(id, scenario);
    }

    @Operation(summary = "依从者效应报告：Wald 与主分层同一 CACE 目标 + 95% 区间 + 拒绝门控")
    @GetMapping("/report")
    public Map<String, Object> report(@PathVariable long id,
                                      @RequestParam(required = false, defaultValue = "PRIMARY") String scenario) {
        return compliance.report(id, scenario);
    }

    @Operation(summary = "随机分流 Wald 工具变量估计（含第一阶段 F/弱工具门控/聚类区间）")
    @GetMapping("/wald")
    public Map<String, Object> wald(@PathVariable long id,
                                    @RequestParam(required = false, defaultValue = "PRIMARY") String scenario) {
        return compliance.waldMap(id, scenario);
    }

    @Operation(summary = "主分层依从模型：基线拥堵/司机/车辆约束预测依从类型后加权，含组成诊断与重叠")
    @GetMapping("/principal-stratification")
    public Map<String, Object> principal(@PathVariable long id,
                                         @RequestParam(required = false, defaultValue = "PRIMARY") String scenario) {
        return compliance.principalMap(id, scenario);
    }

    @Operation(summary = "单调性诊断：臂×完整执行率、DEFIER 反向依从者、负条件第一阶段层")
    @GetMapping("/monotonicity")
    public Map<String, Object> monotonicity(@PathVariable long id,
                                            @RequestParam(required = false, defaultValue = "PRIMARY") String scenario) {
        return compliance.monotonicityReport(id, scenario);
    }

    @Operation(summary = "偏差原因图：约束类别→偏差原因→执行状态的 nodes/links")
    @GetMapping("/deviation-graph")
    public Map<String, Object> deviationGraph(@PathVariable long id,
                                               @RequestParam(required = false, defaultValue = "PRIMARY") String scenario) {
        return compliance.deviationGraph(id, scenario);
    }

    @Operation(summary = "分流资格账：合格/无资格候选、逐项资格准则与无资格原因")
    @GetMapping("/eligibility")
    public Map<String, Object> eligibility(@PathVariable long id,
                                           @RequestParam(required = false, defaultValue = "PRIMARY") String scenario,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "200") int limit) {
        String sc = compliance.normalizeScenario(scenario);
        int lim = Math.min(1000, Math.max(1, limit));
        StringBuilder where = new StringBuilder(" WHERE experiment_id=? AND scenario=? ");
        List<Object> args = new ArrayList<>();
        args.add(id);
        args.add(sc);
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            where.append(" AND eligibility_status=? ");
            args.add(status.toUpperCase());
        }
        args.add(lim);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT segment_code,journey_code,trip_code,route_code,service_date,period_band,
                       diversion_eligible,eligibility_status,ineligible_reason,eligibility_checks,
                       z_assigned,assignment_arm,baseline_congestion,congestion_band,
                       driver_id,driver_constraint,vehicle_id,vehicle_constraint,risk_index
                FROM compliance_segment
                """ + where + " ORDER BY eligibility_status, segment_code LIMIT ?", args.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", sc);
        out.put("summary", compliance.eligibilitySummaryPublic(id, sc));
        out.put("rows", rows);
        out.put("returned", rows.size());
        return out;
    }

    @Operation(summary = "区段执行轨迹主账：中途改线已按区段切分，含分配/主分层类型/执行状态/结果")
    @GetMapping("/segments")
    public Map<String, Object> segments(@PathVariable long id,
                                        @RequestParam(required = false, defaultValue = "PRIMARY") String scenario,
                                        @RequestParam(required = false) String executionState,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "100") int limit) {
        String sc = compliance.normalizeScenario(scenario);
        int lim = Math.min(500, Math.max(1, limit));
        StringBuilder where = new StringBuilder(" WHERE experiment_id=? AND scenario=? ");
        List<Object> args = new ArrayList<>();
        args.add(id);
        args.add(sc);
        if (executionState != null && !executionState.isBlank() && !"ALL".equalsIgnoreCase(executionState)) {
            where.append(" AND execution_state=? ");
            args.add(executionState.toUpperCase());
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (segment_code ILIKE ? OR journey_code ILIKE ? OR trip_code ILIKE ? OR driver_id ILIKE ?) ");
            String like = "%" + q + "%";
            args.add(like); args.add(like); args.add(like); args.add(like);
        }
        args.add(lim);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT segment_code,scenario,journey_code,trip_code,unit_key,route_code,service_date,
                       period_band,segment_index,segment_count,segment_kind,mid_route_change,split_from_code,
                       overtime,short_turn,z_assigned,assignment_arm,principal_type,execution_state,
                       d_fully_executed,adherence_rate,partial_reason,deviation_code,deviation_detail,
                       driver_id,driver_constraint,vehicle_id,vehicle_constraint,
                       baseline_congestion,congestion_band,risk_index,wait_seconds,passenger_weight
                FROM compliance_segment
                """ + where + " ORDER BY id LIMIT ?", args.toArray());

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM compliance_segment WHERE experiment_id=? AND scenario=?",
                Long.class, id, sc);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", sc);
        out.put("total", total);
        out.put("returned", rows.size());
        out.put("rows", rows);
        return out;
    }

    @Operation(summary = "区段有序执行轨迹事件（资格/分配/切点/规则动作/偏差/结果）")
    @GetMapping("/trajectories")
    public Map<String, Object> trajectories(@PathVariable long id,
                                            @RequestParam(required = false, defaultValue = "PRIMARY") String scenario,
                                            @RequestParam(required = false) String segmentCode,
                                            @RequestParam(defaultValue = "500") int limit) {
        String sc = compliance.normalizeScenario(scenario);
        int lim = Math.min(2000, Math.max(1, limit));
        StringBuilder sql = new StringBuilder("""
                SELECT e.segment_code,e.seq,e.checkpoint,e.status,e.label,e.detail
                FROM segment_trajectory_event e
                JOIN compliance_segment s
                  ON s.experiment_id=e.experiment_id AND s.segment_code=e.segment_code
                WHERE e.experiment_id=? AND s.scenario=?
                """);
        List<Object> args = new ArrayList<>();
        args.add(id); args.add(sc);
        if (segmentCode != null && !segmentCode.isBlank()) {
            sql.append(" AND e.segment_code=? ");
            args.add(segmentCode);
        }
        sql.append(" ORDER BY s.id, e.seq LIMIT ?");
        args.add(lim);
        List<Map<String, Object>> events = jdbc.queryForList(sql.toString(), args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", sc);
        out.put("segment_code", segmentCode);
        out.put("events", events);
        out.put("returned", events.size());
        return out;
    }

    @Operation(summary = "用冻结数据确定性重建区段账与依从者效应结果（分析师及以上）")
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild(@PathVariable long id) {
        compliance.rebuild(id);
        return Map.of("ok", true,
                "message", "分流资格账、区段轨迹、偏差图、单调性诊断与双估计已确定性重建");
    }
}
