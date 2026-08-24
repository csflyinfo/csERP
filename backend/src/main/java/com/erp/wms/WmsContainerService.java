package com.erp.wms;

import com.erp.common.util.BillNoGenerator;
import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WMS 容器（周转箱/托盘/笼车）档案与作业服务（V86）。
 *
 * <p>容器收货流程：
 * <ol>
 *   <li>PDA 扫容器条码 → {@link #lookup(String)}：存在且 EMPTY/IN_USE(本任务) 则可用；不存在则提示建档</li>
 *   <li>PDA 收货时带 containerCode → {@link WmsInboundService#receive} 首次把容器绑到任务、状态 IN_USE</li>
 *   <li>同一张收货任务里多次扫码收货，同一容器累计装入；明细上记 container_code</li>
 *   <li>结束收货后生成的上架任务按容器维度生成（一个容器一条上架任务）</li>
 *   <li>PDA 扫容器上架：{@link #taskContents(String)} 看容器内商品；confirmPutaway 一次性上到同一库位</li>
 *   <li>整单全部上架完后，容器自动释放回 EMPTY</li>
 * </ol>
 *
 * <p>容器编号由用户贴码手工录入，不走 BillNoGenerator（容器是长期复用的资产，不是一次性单据）。
 * 但建档时如果没传编号，自动用 RQ+yyyyMMdd+4 位流水兜底。
 */
@Service
public class WmsContainerService {

    private final JdbcTemplate jdbc;
    private final BillNoGenerator billNo;

    public WmsContainerService(JdbcTemplate jdbc, BillNoGenerator billNo) {
        this.jdbc = jdbc;
        this.billNo = billNo;
    }

    /** 容器列表，按仓库/状态/关键字过滤。 */
    public List<Map<String, Object>> list(String warehouse, String status, String keyword) {
        StringBuilder sql = new StringBuilder("""
                SELECT container_id, container_code, container_type, warehouse, zone_code, bin_code,
                       status, current_task_id, current_task_no, bound_at, bound_by, remark, created_at
                FROM wms_container WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (warehouse != null && !warehouse.isBlank()) {
            sql.append(" AND warehouse = ?"); args.add(warehouse);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?"); args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (LOWER(container_code) LIKE ? OR LOWER(COALESCE(remark,'')) LIKE ?)");
            String k = "%" + keyword.toLowerCase() + "%";
            args.add(k); args.add(k);
        }
        sql.append(" ORDER BY created_at DESC");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    /**
     * 扫容器条码查询：存在返回容器头 + 当前任务；不存在抛 IllegalArgumentException 由前端引导建档。
     * IN_USE 且绑定了别的任务时抛 IllegalStateException（不能混用）。
     */
    public Map<String, Object> lookup(String containerCode) {
        if (containerCode == null || containerCode.isBlank()) {
            throw new IllegalArgumentException("请扫描容器条码");
        }
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT * FROM wms_container WHERE container_code = ?", containerCode);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("容器 " + containerCode + " 未建档，请先在 PC 端建档或点击【新建容器】");
        }
        Map<String, Object> c = rows.get(0);
        // 当前任务里的收货明细（已收）
        String currentTaskId = TmsUtil.str(c.get("currentTaskId"));
        if (!currentTaskId.isBlank()) {
            List<Map<String, Object>> items = TmsUtil.queryCamel(jdbc, """
                    SELECT d.goods_code, d.goods_name, d.unit_name, d.batch_no, d.production_date,
                           SUM(COALESCE(d.received_qty,0)) AS qty
                    FROM wms_inbound_task_detail d
                    WHERE d.task_id = ? AND COALESCE(d.container_code,'') = ?
                    GROUP BY d.goods_code, d.goods_name, d.unit_name, d.batch_no, d.production_date
                    """, currentTaskId, containerCode);
            c.put("items", items);
        } else {
            c.put("items", List.of());
        }
        return c;
    }

    /** 容器建档。code 为空时自动生成 RQ+yyyyMMdd+4 位流水。 */
    @Transactional
    public Map<String, Object> create(String code, String type, String warehouse,
                                      String zoneCode, String binCode, String remark, String operator) {
        String finalCode = (code == null || code.isBlank())
                ? billNo.nextNo(BillNoGenerator.BillType.WMS_INBOUND, "wms_container", "container_code")
                        .replace("RK", "RQ")
                : code.trim();
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_container WHERE container_code = ?",
                Integer.class, finalCode);
        if (exists != null && exists > 0) {
            throw new IllegalStateException("容器编号已存在：" + finalCode);
        }
        String id = "CT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String finalType = (type == null || type.isBlank()) ? "TOTE" : type;
        jdbc.update("""
                INSERT INTO wms_container
                (container_id, container_code, container_type, warehouse, zone_code, bin_code,
                 status, remark, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'EMPTY', ?, CURRENT_TIMESTAMP)
                """, id, finalCode, finalType, warehouse, zoneCode, binCode, remark);
        TmsUtil.log(jdbc, "wms.container", "CREATE", finalCode, "建档容器类型 " + finalType + " 仓库 " + warehouse);
        return Map.of("containerId", id, "containerCode", finalCode, "status", "EMPTY");
    }

    /** 容器内的收货项（PDA 扫容器后展示用）。 */
    public List<Map<String, Object>> taskContents(String containerCode) {
        return TmsUtil.queryCamel(jdbc, """
                SELECT d.goods_code, d.goods_name, d.spec, d.unit_name, d.batch_no, d.production_date,
                       d.container_code, SUM(COALESCE(d.received_qty,0)) AS qty
                FROM wms_inbound_task_detail d
                WHERE d.container_code = ?
                GROUP BY d.goods_code, d.goods_name, d.spec, d.unit_name, d.batch_no, d.production_date, d.container_code
                ORDER BY d.goods_code
                """, containerCode);
    }

    /** 容器操作流水（按容器）。 */
    public List<Map<String, Object>> records(String containerCode) {
        return TmsUtil.queryCamel(jdbc, """
                SELECT record_id, container_code, task_id, task_no, putaway_id, operation,
                       goods_code, qty, operator, remark, created_at
                FROM wms_container_record
                WHERE container_code = ? ORDER BY created_at DESC LIMIT 200
                """, containerCode);
    }

    /** 手工释放容器（仅 IN_USE 可释放，用于异常场景；整单上架完会自动释放）。 */
    @Transactional
    public Map<String, Object> release(String containerCode, String operator) {
        int n = jdbc.update("""
                UPDATE wms_container SET status='EMPTY', current_task_id=NULL, current_task_no=NULL,
                    bound_at=NULL, bound_by=NULL
                WHERE container_code = ? AND status = 'IN_USE'
                """, containerCode);
        if (n == 0) {
            throw new IllegalStateException("容器 " + containerCode + " 当前不是使用中状态，不能释放");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("containerCode", containerCode);
        r.put("status", "EMPTY");
        return r;
    }
}
