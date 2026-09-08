package com.erp.finance.gl;

import com.erp.tms.TmsUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 核算项目——项目档案服务（fin_aux_project）。
 * 客户/供应商/部门/员工/商品/片区六维直接复用主数据，项目维度由本模块维护。
 */
@Service
public class GlAuxProjectService {

    private final JdbcTemplate jdbc;

    public GlAuxProjectService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list(String keyword, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, project_code, project_name, project_type, " +
                "start_date, end_date, budget, owner_name, " +
                "status, remark, creator_name, create_time " +
                "FROM fin_aux_project WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (project_code LIKE ? OR project_name LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        if (status != null && !status.isEmpty()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY project_code");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    public Map<String, Object> create(Map<String, Object> req) {
        String code = TmsUtil.str(req.get("projectCode"));
        String name = TmsUtil.str(req.get("projectName"));
        if (code.isEmpty()) throw new IllegalArgumentException("项目编码不能为空");
        if (name.isEmpty()) throw new IllegalArgumentException("项目名称不能为空");
        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM fin_aux_project WHERE project_code = ?",
                Integer.class, code);
        if (cnt != null && cnt > 0) throw new IllegalArgumentException("项目编码已存在：" + code);
        String id = TmsUtil.uuid("PJ");
        jdbc.update("INSERT INTO fin_aux_project(id, project_code, project_name, project_type, start_date, " +
                "end_date, budget, owner_name, status, remark, creator_name) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, code, name, TmsUtil.str(req.get("projectType")),
                dateOrNull(req.get("startDate")), dateOrNull(req.get("endDate")),
                TmsUtil.toBd(req.get("budgetAmount")), TmsUtil.str(req.get("ownerName")),
                TmsUtil.str(req.get("status")).isEmpty() ? GlConst.ACCOUNT_ENABLED : TmsUtil.str(req.get("status")),
                TmsUtil.str(req.get("remark")), TmsUtil.currentUser());
        TmsUtil.log(jdbc, "finance.gl.aux-project", "CREATE", code, "新增核算项目 " + code + " " + name);
        return findById(id);
    }

    public void update(Map<String, Object> req) {
        String id = TmsUtil.str(req.get("id"));
        if (findById(id) == null) throw new IllegalArgumentException("项目不存在");
        jdbc.update("UPDATE fin_aux_project SET project_name=?, project_type=?, start_date=?, end_date=?, " +
                "budget=?, owner_name=?, status=?, remark=?, update_time=CURRENT_TIMESTAMP WHERE id=?",
                TmsUtil.str(req.get("projectName")), TmsUtil.str(req.get("projectType")),
                dateOrNull(req.get("startDate")), dateOrNull(req.get("endDate")),
                TmsUtil.toBd(req.get("budgetAmount")), TmsUtil.str(req.get("ownerName")),
                TmsUtil.str(req.get("status")), TmsUtil.str(req.get("remark")), id);
        TmsUtil.log(jdbc, "finance.gl.aux-project", "UPDATE", TmsUtil.str(req.get("projectCode")),
                "修改核算项目 " + TmsUtil.str(req.get("projectName")));
    }

    /** 项目下拉（启用中可用，停用的排除）。 */
    public List<Map<String, Object>> options(String keyword) {
        StringBuilder sql = new StringBuilder(
                "SELECT project_code, project_name, project_type FROM fin_aux_project " +
                "WHERE status <> '" + GlConst.ACCOUNT_DISABLED + "'");
        List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (project_code LIKE ? OR project_name LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY project_code");
        return TmsUtil.queryCamel(jdbc, sql.toString(), args.toArray());
    }

    private Map<String, Object> findById(String id) {
        List<Map<String, Object>> rows = TmsUtil.queryCamel(jdbc,
                "SELECT id, project_code, project_name FROM fin_aux_project WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static java.sql.Date dateOrNull(Object o) {
        java.time.LocalDate d = TmsUtil.toLocalDate(o);
        return d == null ? null : java.sql.Date.valueOf(d);
    }
}
