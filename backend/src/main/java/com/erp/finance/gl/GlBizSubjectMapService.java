package com.erp.finance.gl;

import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务类型科目映射（其他出/入库类型 → 对方科目）。
 * 类型仍由 sys_dictionary 维护（不剥离字典）；本服务把字典值与映射行合并返回，
 * 字典里有值但未配映射的类型以 unmapped=TRUE 红字提示。
 */
@Service
public class GlBizSubjectMapService {

    private final JdbcTemplate jdbc;

    public GlBizSubjectMapService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 分组返回：[{eventCode, eventName, dictType, rows:[{bizTypeCode,bizTypeName,counterSubjectCode,accountName,isSystem,unmapped}]}] */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> groups = new ArrayList<>();
        groups.add(buildGroup("OTHER_OUT", "其他出库类型", "other_outbound_type"));
        groups.add(buildGroup("OTHER_IN", "其他入库类型", "other_inbound_type"));
        return groups;
    }

    private Map<String, Object> buildGroup(String eventCode, String eventName, String dictType) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("eventCode", eventCode);
        group.put("eventName", eventName);

        List<Map<String, Object>> dictRows = TmsUtil.queryCamel(jdbc,
                "SELECT dict_code, dict_name FROM sys_dictionary WHERE dict_type = ? AND status = 'NORMAL' ORDER BY sort_order",
                dictType);
        List<Map<String, Object>> mapRows = TmsUtil.queryCamel(jdbc,
                "SELECT m.id, m.biz_type_code, m.biz_type_name, m.counter_subject_code, m.is_system, " +
                        "a.account_name FROM fin_gl_biz_subject_map m " +
                        "LEFT JOIN fin_account a ON a.account_code = m.counter_subject_code " +
                        "WHERE m.event_code = ? ORDER BY m.biz_type_code", eventCode);

        Map<String, Map<String, Object>> mapByCode = new LinkedHashMap<>();
        for (Map<String, Object> m : mapRows) mapByCode.put(TmsUtil.str(m.get("bizTypeCode")), m);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> d : dictRows) {
            String code = TmsUtil.str(d.get("dictCode"));
            String name = TmsUtil.str(d.get("dictName"));
            Map<String, Object> m = mapByCode.remove(code);
            Map<String, Object> row = new LinkedHashMap<>();
            if (m != null) {
                row.put("id", m.get("id"));
                row.put("bizTypeCode", code);
                row.put("bizTypeName", TmsUtil.str(m.get("bizTypeName")).isEmpty() ? name : m.get("bizTypeName"));
                row.put("counterSubjectCode", m.get("counterSubjectCode"));
                row.put("accountName", m.get("accountName"));
                row.put("isSystem", m.get("isSystem"));
                row.put("unmapped", false);
            } else {
                row.put("id", null);
                row.put("bizTypeCode", code);
                row.put("bizTypeName", name);
                row.put("counterSubjectCode", null);
                row.put("accountName", null);
                row.put("isSystem", false);
                row.put("unmapped", true); // 字典有值但未配映射
            }
            rows.add(row);
        }
        // 映射表里有、字典里已删的值（历史快照）也列出
        for (Map<String, Object> m : mapByCode.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.get("id"));
            row.put("bizTypeCode", m.get("bizTypeCode"));
            row.put("bizTypeName", m.get("bizTypeName"));
            row.put("counterSubjectCode", m.get("counterSubjectCode"));
            row.put("accountName", m.get("accountName"));
            row.put("isSystem", m.get("isSystem"));
            row.put("unmapped", false);
            row.put("dictMissing", true);
            rows.add(row);
        }
        group.put("rows", rows);
        return group;
    }

    /** 新增/更新映射；counterSubjectCode 为空 = 该类型不生成凭证。 */
    public void save(Map<String, Object> body) {
        String eventCode = TmsUtil.str(body.get("eventCode"));
        String bizTypeCode = TmsUtil.str(body.get("bizTypeCode"));
        String bizTypeName = TmsUtil.str(body.get("bizTypeName"));
        String subjectCode = TmsUtil.str(body.get("counterSubjectCode"));
        if (!List.of("OTHER_OUT", "OTHER_IN").contains(eventCode))
            throw new IllegalArgumentException("仅支持 OTHER_OUT/OTHER_IN 事件的业务类型映射");
        if (bizTypeCode.isEmpty()) throw new IllegalArgumentException("业务类型编码不能为空");

        if (!subjectCode.isEmpty()) {
            List<Map<String, Object>> acc = TmsUtil.queryCamel(jdbc,
                    "SELECT account_name, status, is_leaf FROM fin_account WHERE account_code = ?", subjectCode);
            if (acc.isEmpty()) throw new IllegalArgumentException("科目「" + subjectCode + "」不存在");
            Map<String, Object> a = acc.get(0);
            if (!Boolean.TRUE.equals(a.get("isLeaf")))
                throw new IllegalArgumentException("对方科目必须是末级科目：" + subjectCode);
            if (!GlConst.ACCOUNT_ENABLED.equals(TmsUtil.str(a.get("status"))))
                throw new IllegalArgumentException("对方科目已停用：" + subjectCode);
            if (bizTypeName.isEmpty()) bizTypeName = subjectCode;
        }
        // 名称快照优先取字典当前名
        List<Map<String, Object>> dict = TmsUtil.queryCamel(jdbc,
                "SELECT dict_name FROM sys_dictionary WHERE dict_type = ? AND dict_code = ?",
                "OTHER_OUT".equals(eventCode) ? "other_outbound_type" : "other_inbound_type", bizTypeCode);
        if (!dict.isEmpty()) bizTypeName = TmsUtil.str(dict.get(0).get("dictName"));
        if (bizTypeName.isEmpty()) bizTypeName = bizTypeCode;

        List<Map<String, Object>> exist = TmsUtil.queryCamel(jdbc,
                "SELECT id FROM fin_gl_biz_subject_map WHERE event_code = ? AND biz_type_code = ?",
                eventCode, bizTypeCode);
        if (exist.isEmpty()) {
            jdbc.update("INSERT INTO fin_gl_biz_subject_map (id, event_code, biz_type_code, biz_type_name, " +
                            "counter_subject_code, is_system) VALUES (?,?,?,?,?, FALSE)",
                    TmsUtil.uuid("bm"), eventCode, bizTypeCode, bizTypeName,
                    subjectCode.isEmpty() ? null : subjectCode);
        } else {
            jdbc.update("UPDATE fin_gl_biz_subject_map SET biz_type_name = ?, counter_subject_code = ?, " +
                            "update_time = CURRENT_TIMESTAMP WHERE id = ?",
                    bizTypeName, subjectCode.isEmpty() ? null : subjectCode,
                    TmsUtil.str(exist.get(0).get("id")));
        }
        TmsUtil.log(jdbc, "finance.gl.bizmap", "SAVE", eventCode + ":" + bizTypeCode,
                "业务类型映射 " + eventCode + " " + bizTypeCode + " → " + (subjectCode.isEmpty() ? "不生凭证" : subjectCode));
    }
}
