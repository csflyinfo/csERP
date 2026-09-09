package com.erp.common.security.datascope;

import com.erp.common.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据范围解析与 SQL 拼装（PRD-28 §5.3，卡片4）。
 *
 * <p>七个维度：WAREHOSE / CUSTOMER / SUPPLIER / SALESMAN / OWNER / GOODS_CATEGORY / BRAND。
 * 合并规则：多角色同维度取并集；sys_user_data_scope 用户层只减不增（交集）；
 * 不同维度之间 AND；ALL 短路；角色一个维度都没配时默认仅看自己创建（DEFAULT DENY）；
 * SYS_ADMIN 全放行；PDA token 携带 warehouseId 时强制单仓；sys_user_warehouse
 * 绑定仓存在时对 WAREHOUSE 维度收窄。
 *
 * <p>历史业务表普遍存「名称/编码」而非 ID（sales_order.warehouse/customer/salesman、
 * inv_stock_balance.warehouse/goods_code 等），因此维度 ID 一律翻译为子查询匹配名称列；
 * 商品维度经 base_goods 分类/品牌名映射到 goods_code，再过滤单据明细行。
 *
 * <p>用法：
 * <pre>{@code
 * StringBuilder sql = ...;
 * List<Object> args = new ArrayList<>();
 * DataScopeService.ScopeClause scope = dataScope.target()
 *         .warehouse("so.warehouse").customer("so.customer").salesman("so.salesman")
 *         .creator("so.creator_name")
 *         .goodsLines("so.order_id", "sales_order_detail", "order_id")
 *         .build();
 * scope.appendTo(sql, args);
 * }</pre>
 */
@Service
public class DataScopeService {

    private static final String REQUEST_ATTR_SCOPE = "rbac.dataScope";
    private static final int SUBTREE_MAX_DEPTH = 5;

    private final JdbcTemplate jdbcTemplate;

    public DataScopeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 构造一个数据范围目标（业务列表 SQL 的列映射）。 */
    public ScopeTarget target() {
        return new ScopeTarget(this);
    }

    // ============================ 目标描述 ============================

    /** 业务列表与七维的列映射；所有列表达式都应带表别名（H2 MODE=MySQL JOIN 同名列必须限定）。 */
    public static class ScopeTarget {
        String warehouse;
        String customer;
        String supplier;
        String salesman;
        String owner;
        /** 建档人列（存当前用户姓名），DEFAULT DENY 与「—」维度兜底用，如 so.creator_name。 */
        String creator;
        /** 直接挂在商品编码列上的过滤（库存/商品档案列表），如 b.goods_code。 */
        String goodsColumn;
        /** 单据主表 ID 表达式 + 明细表名/明细主单外键（按明细行商品范围过滤主单）。 */
        String masterExpr;
        String detailTable;
        String detailMasterColumn;

        private final DataScopeService svc;

        ScopeTarget(DataScopeService svc) {
            this.svc = svc;
        }

        public ScopeTarget warehouse(String column) { this.warehouse = column; return this; }
        public ScopeTarget customer(String column) { this.customer = column; return this; }
        public ScopeTarget supplier(String column) { this.supplier = column; return this; }
        public ScopeTarget salesman(String column) { this.salesman = column; return this; }
        public ScopeTarget owner(String column) { this.owner = column; return this; }
        public ScopeTarget creator(String column) { this.creator = column; return this; }
        public ScopeTarget goodsColumn(String column) { this.goodsColumn = column; return this; }

        public ScopeTarget goodsLines(String masterExpr, String detailTable, String detailMasterColumn) {
            this.masterExpr = masterExpr;
            this.detailTable = detailTable;
            this.detailMasterColumn = detailMasterColumn;
            return this;
        }

        public ScopeClause build() {
            return svc.build(this);
        }
    }

    // ============================ 拼装结果 ============================

    /** 拼装结果：追加到业务 SQL 的 WHERE 尾部；商品受限时可再取可见商品条件做明细/金额过滤。 */
    public static class ScopeClause {
        private final String whereSql;          // 形如 " AND (...)"，空串表示不限制
        private final List<Object> whereParams;
        private final boolean goodsRestricted;
        // 可见商品条件：分类名集合（空集表示该维度不限制）、品牌名集合
        private final Set<String> categoryNames;
        private final Set<String> brandNames;
        private final boolean denyAll;

        ScopeClause(String whereSql, List<Object> whereParams, boolean goodsRestricted,
                    Set<String> categoryNames, Set<String> brandNames, boolean denyAll) {
            this.whereSql = whereSql;
            this.whereParams = whereParams;
            this.goodsRestricted = goodsRestricted;
            this.categoryNames = categoryNames;
            this.brandNames = brandNames;
            this.denyAll = denyAll;
        }

        /** 是否一条都不允许看（显式拼 1=0 的场景，如收窄后空集、未配范围且无建档人列）。 */
        public boolean isDenyAll() {
            return denyAll;
        }

        public boolean isGoodsRestricted() {
            return goodsRestricted;
        }

        /** 把 WHERE 片段（" AND ..."）与参数追加到业务 SQL。 */
        public void appendTo(StringBuilder sql, List<Object> params) {
            sql.append(whereSql);
            params.addAll(whereParams);
        }

        /**
         * 追加「商品编码在可见范围内」条件：{@code expr IN (SELECT goods_code FROM base_goods WHERE ...)}。
         * 仅在 {@link #isGoodsRestricted()} 为 true 时调用；用于明细行过滤与可见行金额汇总。
         */
        public void appendGoodsCodeCondition(String goodsCodeExpr, StringBuilder sql, List<Object> params) {
            sql.append(goodsCodeExpr).append(" IN (SELECT goods_code FROM base_goods WHERE 1=1");
            if (!categoryNames.isEmpty()) {
                sql.append(" AND category_name IN (").append(placeholders(categoryNames.size())).append(")");
                params.addAll(categoryNames);
            }
            if (!brandNames.isEmpty()) {
                sql.append(" AND brand_name IN (").append(placeholders(brandNames.size())).append(")");
                params.addAll(brandNames);
            }
            sql.append(")");
        }
    }

    // ============================ 核心拼装 ============================

    private ScopeClause build(ScopeTarget t) {
        CurrentUser.Principal p = CurrentUser.get();
        if (p == null || p.isSuperAdmin()) {
            return new ScopeClause("", List.of(), false, Set.of(), Set.of(), false);
        }
        Resolution r = resolve(p);

        // 已配置的维度才参与过滤；目标声明了但角色没配的维度按「—」处理＝不加条件（§5.3.3-3，典型角色表）
        List<String> predicates = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        boolean pdaForcedWarehouse = false;

        if (t.warehouse != null) {
            // PDA 登录仓强制（§5.3.3-7）
            if (p.isPda() && p.warehouseId() != null && !p.warehouseId().isBlank()) {
                predicates.add(t.warehouse + " IN (SELECT warehouse_name FROM base_warehouse WHERE warehouse_id = ?)");
                params.add(p.warehouseId());
                pdaForcedWarehouse = true;
            } else {
                appendIdNameDim(t.warehouse, "base_warehouse", "warehouse_id", "warehouse_name",
                        r.dims.get("WAREHOUSE"), predicates, params);
            }
        }
        if (t.customer != null) {
            appendCustomerDim(t.customer, r.dims.get("CUSTOMER"), p, predicates, params);
        }
        if (t.supplier != null) {
            appendIdNameDim(t.supplier, "base_supplier", "supplier_id", "supplier_name",
                    r.dims.get("SUPPLIER"), predicates, params);
        }
        if (t.salesman != null) {
            appendSalesmanDim(t.salesman, r.dims.get("SALESMAN"), predicates, params);
        }
        if (t.owner != null) {
            appendOwnerDim(t.owner, r.dims.get("OWNER"), predicates, params);
        }

        // 商品分类/品牌：先解析为可见分类名/品牌名
        Set<String> categoryNames = Set.of();
        Set<String> brandNames = Set.of();
        if (r.categoryRestricted || r.brandRestricted) {
            categoryNames = r.categoryRestricted ? expandCategoryNames(r.categoryIds) : Set.of();
            brandNames = r.brandRestricted ? expandBrandNames(r.brandIds) : Set.of();
            boolean visibleGoodsExists = r.categoryRestricted && categoryNames.isEmpty()
                    ? false : !(r.brandRestricted && brandNames.isEmpty());
            if (!visibleGoodsExists) {
                return new ScopeClause(" AND 1=0", List.of(), true, categoryNames, brandNames, true);
            }
            String goodsExpr;
            if (t.goodsColumn != null) {
                goodsExpr = t.goodsColumn;
            } else if (t.masterExpr != null) {
                goodsExpr = null; // 主单走 EXISTS 明细
            } else {
                goodsExpr = null;
            }
            if (goodsExpr != null) {
                predicates.add(renderGoodsCondition(goodsExpr, categoryNames, brandNames, params));
            } else if (t.masterExpr != null) {
                StringBuilder sb = new StringBuilder();
                sb.append(t.masterExpr).append(" IN (SELECT d.").append(t.detailMasterColumn)
                        .append(" FROM ").append(t.detailTable).append(" d WHERE ");
                sb.append("d.goods_code");
                appendGoodsInner(sb, categoryNames, brandNames, params);
                sb.append(")");
                predicates.add(sb.toString());
            }
        }

        boolean goodsRestricted = r.categoryRestricted || r.brandRestricted;

        // DEFAULT DENY（§5.3.3-5）：角色一个数据范围维度都没配（也没有绑定仓/PDA 强制仓）时，
        // 表头有建档人列→仅看自己创建；没有→fail-closed，1=0。已配任一维度即按已配维度过滤，其余维度「—」放行。
        if (!r.configuredAny && !pdaForcedWarehouse) {
            if (t.creator != null) {
                String creator = p.displayName() == null || p.displayName().isBlank() ? p.username() : p.displayName();
                predicates.add(t.creator + " = ?");
                params.add(creator);
            } else {
                // 完全没配范围、表头又没有建档人列：fail-closed，一条都看不到
                return new ScopeClause(" AND 1=0", List.of(), goodsRestricted, categoryNames, brandNames, true);
            }
        }

        if (predicates.isEmpty()) {
            return new ScopeClause("", List.of(), goodsRestricted, categoryNames, brandNames, false);
        }
        StringBuilder sql = new StringBuilder(" AND (");
        for (int i = 0; i < predicates.size(); i++) {
            if (i > 0) sql.append(" AND ");
            sql.append(predicates.get(i));
        }
        sql.append(")");
        return new ScopeClause(sql.toString(), List.copyOf(params), goodsRestricted, categoryNames, brandNames, false);
    }

    private String renderGoodsCondition(String goodsExpr, Set<String> categoryNames, Set<String> brandNames,
                                        List<Object> params) {
        StringBuilder sb = new StringBuilder();
        sb.append(goodsExpr);
        appendGoodsInner(sb, categoryNames, brandNames, params);
        return sb.toString();
    }

    private void appendGoodsInner(StringBuilder sb, Set<String> categoryNames, Set<String> brandNames,
                                  List<Object> params) {
        sb.append(" IN (SELECT goods_code FROM base_goods WHERE 1=1");
        if (!categoryNames.isEmpty()) {
            sb.append(" AND category_name IN (").append(placeholders(categoryNames.size())).append(")");
            params.addAll(categoryNames);
        }
        if (!brandNames.isEmpty()) {
            sb.append(" AND brand_name IN (").append(placeholders(brandNames.size())).append(")");
            params.addAll(brandNames);
        }
        sb.append(")");
    }

    /** 仓库/供应商类：ID → 名称子查询。返回 false 表示该维度未配置（调用方决定兜底）。 */
    private boolean appendIdNameDim(String column, String table, String idCol, String nameCol,
                                    DimValues dim, List<String> predicates, List<Object> params) {
        if (dim == null || dim.unconfigured) return false;
        if (dim.all) return true;
        if (dim.ids.isEmpty()) {
            predicates.add("1=0");
            return true;
        }
        predicates.add(column + " IN (SELECT " + nameCol + " FROM " + table + " WHERE " + idCol
                + " IN (" + placeholders(dim.ids.size()) + "))");
        params.addAll(dim.ids);
        return true;
    }

    /** 客户维度：指定 ID / SELF（归属当前业务员的客户）/ REGION（片区）。 */
    private boolean appendCustomerDim(String column, DimValues dim, CurrentUser.Principal p,
                                      List<String> predicates, List<Object> params) {
        if (dim == null || dim.unconfigured) return false;
        if (dim.all) return true;
        List<String> ors = new ArrayList<>();
        if (!dim.ids.isEmpty()) {
            ors.add(column + " IN (SELECT customer_name FROM base_customer WHERE customer_id IN ("
                    + placeholders(dim.ids.size()) + "))");
            params.addAll(dim.ids);
        }
        if (dim.self) {
            String empName = currentEmployeeName(p);
            if (empName == null) {
                predicates.add("1=0"); // 未绑定员工的账号配 SELF：fail-closed
                return true;
            }
            ors.add(column + " IN (SELECT customer_name FROM base_customer WHERE salesman = ?)");
            params.add(empName);
        }
        if (!dim.regions.isEmpty()) {
            ors.add(column + " IN (SELECT c.customer_name FROM base_customer c "
                    + "WHERE c.territory IN (SELECT territory_name FROM base_territory WHERE territory_id IN ("
                    + placeholders(dim.regions.size()) + ")))");
            params.addAll(dim.regions);
        }
        if (ors.isEmpty()) {
            predicates.add("1=0");
            return true;
        }
        predicates.add(ors.size() == 1 ? ors.get(0) : "(" + String.join(" OR ", ors) + ")");
        return true;
    }

    /** 业务员维度：SELF / SUB_TREE / 指定 ID，全部翻译成 employee_name 匹配单据列。 */
    private boolean appendSalesmanDim(String column, DimValues dim, List<String> predicates,
                                      List<Object> params) {
        if (dim == null || dim.unconfigured) return false;
        if (dim.all) return true;
        Set<String> names = new LinkedHashSet<>();
        if ((dim.self || dim.subTree) && dim.ids.isEmpty()) {
            // 纯 SELF/SUB_TREE（无显式 ID 交叠）
            CurrentUser.Principal p = CurrentUser.get();
            String selfName = currentEmployeeName(p);
            if (selfName == null) {
                predicates.add("1=0");
                return true;
            }
            names.add(selfName);
            if (dim.subTree) names.addAll(subordinateNames(selfName));
        } else {
            names.addAll(employeeNamesByIds(dim.ids));
        }
        if (names.isEmpty()) {
            predicates.add("1=0");
            return true;
        }
        predicates.add(column + " IN (" + placeholders(names.size()) + ")");
        params.addAll(names);
        return true;
    }

    /** 货主维度：后期多货主，当前单据无 owner 列；按 ID 原样匹配。 */
    private boolean appendOwnerDim(String column, DimValues dim, List<String> predicates, List<Object> params) {
        if (dim == null || dim.unconfigured) return false;
        if (dim.all) return true;
        if (dim.ids.isEmpty()) {
            predicates.add("1=0");
            return true;
        }
        predicates.add(column + " IN (" + placeholders(dim.ids.size()) + ")");
        params.addAll(dim.ids);
        return true;
    }

    // ============================ 范围解析（角色并集 × 用户收窄） ============================

    static class DimValues {
        boolean unconfigured = true;   // 角色与用户层均未出现该维度
        boolean all;
        boolean self;
        boolean subTree;
        Set<String> ids = new LinkedHashSet<>();
        Set<String> regions = new LinkedHashSet<>();
    }

    static class Resolution {
        final Map<String, DimValues> dims = new LinkedHashMap<>();
        boolean configuredAny;
        boolean categoryRestricted;
        boolean brandRestricted;
        Set<String> categoryIds = Set.of();
        Set<String> brandIds = Set.of();
        DimValues warehouse() { return dims.computeIfAbsent("WAREHOUSE", k -> new DimValues()); }
        DimValues customer() { return dims.computeIfAbsent("CUSTOMER", k -> new DimValues()); }
        DimValues supplier() { return dims.computeIfAbsent("SUPPLIER", k -> new DimValues()); }
        DimValues salesman() { return dims.computeIfAbsent("SALESMAN", k -> new DimValues()); }
        DimValues owner() { return dims.computeIfAbsent("OWNER", k -> new DimValues()); }
    }

    @SuppressWarnings("unchecked")
    private Resolution resolve(CurrentUser.Principal p) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object cached = attrs.getAttribute(REQUEST_ATTR_SCOPE, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof Resolution r) return r;
        }
        Resolution r = new Resolution();

        // 角色层：sys_user_role_rel × sys_role_data_scope（多角色并集）
        List<Map<String, Object>> roleRows = jdbcTemplate.queryForList(
                "SELECT ds.scope_type AS scope_type, ds.scope_value AS scope_value "
                        + "FROM sys_user_role_rel urr "
                        + "JOIN sys_role_runtime rr ON rr.role_id = urr.role_id AND rr.status = 'NORMAL' "
                        + "JOIN sys_role_data_scope ds ON ds.role_id = rr.role_id "
                        + "WHERE urr.user_id = ?", p.userId());
        Map<String, DimValues> roleDims = new LinkedHashMap<>();
        for (Map<String, Object> row : roleRows) {
            mergeTokens(roleDims.computeIfAbsent(str(row.get("scope_type")), k -> new DimValues()),
                    str(row.get("scope_value")));
        }

        // 用户层收窄（交集，只减不增）
        List<Map<String, Object>> userRows = jdbcTemplate.queryForList(
                "SELECT scope_type AS scope_type, scope_value AS scope_value "
                        + "FROM sys_user_data_scope WHERE user_id = ?", p.userId());
        Map<String, DimValues> userDims = new LinkedHashMap<>();
        for (Map<String, Object> row : userRows) {
            mergeTokens(userDims.computeIfAbsent(str(row.get("scope_type")), k -> new DimValues()),
                    str(row.get("scope_value")));
        }

        Set<String> types = new LinkedHashSet<>();
        types.addAll(roleDims.keySet());
        types.addAll(userDims.keySet());
        for (String type : types) {
            DimValues role = roleDims.get(type);
            DimValues user = userDims.get(type);
            DimValues eff = intersect(role, user);
            r.dims.put(type, eff);
        }
        r.configuredAny = !roleRows.isEmpty() || !userRows.isEmpty();

        // 用户绑定仓：存在绑定时对 WAREHOUSE 维度收窄（仓库岗授权载体）
        List<String> boundWarehouses = jdbcTemplate.queryForList(
                "SELECT warehouse_id FROM sys_user_warehouse WHERE user_id = ?", String.class, p.userId());
        if (!boundWarehouses.isEmpty()) {
            DimValues wh = r.dims.computeIfAbsent("WAREHOUSE", k -> new DimValues());
            if (wh.unconfigured || wh.all) {
                wh.unconfigured = false;
                wh.all = false;
                wh.ids = new LinkedHashSet<>(boundWarehouses);
            } else {
                wh.ids.retainAll(boundWarehouses);
            }
            r.configuredAny = true;
        }

        if (r.dims.containsKey("GOODS_CATEGORY")) {
            DimValues cat = r.dims.get("GOODS_CATEGORY");
            if (!cat.unconfigured && !cat.all) {
                r.categoryRestricted = true;
                r.categoryIds = cat.ids;
            }
        }
        if (r.dims.containsKey("BRAND")) {
            DimValues brand = r.dims.get("BRAND");
            if (!brand.unconfigured && !brand.all) {
                r.brandRestricted = true;
                r.brandIds = brand.ids;
            }
        }

        if (attrs != null) attrs.setAttribute(REQUEST_ATTR_SCOPE, r, RequestAttributes.SCOPE_REQUEST);
        return r;
    }

    /** 并集合并一段 scope_value（ALL 短路；SELF/SUB_TREE/REGION:id/逗号 ID）。 */
    private void mergeTokens(DimValues dim, String value) {
        if (value == null || value.isBlank()) return;
        dim.unconfigured = false;
        for (String raw : value.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) continue;
            if ("ALL".equalsIgnoreCase(token)) {
                dim.all = true;
            } else if ("SELF".equalsIgnoreCase(token)) {
                dim.self = true;
            } else if ("SUB_TREE".equalsIgnoreCase(token)) {
                dim.subTree = true;
                dim.self = true;
            } else if (token.startsWith("REGION:")) {
                dim.regions.add(token.substring("REGION:".length()).trim());
            } else {
                dim.ids.add(token);
            }
        }
    }

    /** 用户层与角色层取交集；用户层未配置时原样返回角色层。 */
    private DimValues intersect(DimValues role, DimValues user) {
        if (role == null && user == null) return new DimValues();
        if (user == null) return role;
        if (role == null) {
            // 只在用户层配置：同样生效（管理员主动收窄）
            return user;
        }
        if (role.all) return user;
        if (user.all) return role;
        DimValues out = new DimValues();
        out.unconfigured = false;
        out.self = role.self && user.self;
        out.subTree = role.subTree && user.subTree;
        out.ids = new LinkedHashSet<>(role.ids);
        out.ids.retainAll(user.ids);
        // SELF/SUB_TREE 与显式 ID 的交叠：当前员工在显式集合内才保留
        if ((role.self || role.subTree) && !user.ids.isEmpty()) {
            CurrentUser.Principal p = CurrentUser.get();
            String empId = p == null ? null : p.employeeId();
            if (empId != null && user.ids.contains(empId)) out.ids.add(empId);
        }
        if ((user.self || user.subTree) && !role.ids.isEmpty()) {
            CurrentUser.Principal p = CurrentUser.get();
            String empId = p == null ? null : p.employeeId();
            if (empId != null && role.ids.contains(empId)) out.ids.add(empId);
        }
        out.regions = new LinkedHashSet<>(role.regions);
        out.regions.retainAll(user.regions);
        return out;
    }

    // ============================ ID/名称翻译 ============================

    private String currentEmployeeName(CurrentUser.Principal p) {
        if (p == null || p.employeeId() == null || p.employeeId().isBlank()) return null;
        List<String> names = jdbcTemplate.queryForList(
                "SELECT employee_name FROM base_employee WHERE employee_id = ?", String.class, p.employeeId());
        return names.isEmpty() ? null : names.get(0);
    }

    /** 沿 parent_salesman（存上级 employee_name）向下递归，最多 5 层。 */
    private Set<String> subordinateNames(String selfName) {
        Set<String> all = new LinkedHashSet<>();
        Set<String> frontier = Set.of(selfName);
        for (int depth = 0; depth < SUBTREE_MAX_DEPTH && !frontier.isEmpty(); depth++) {
            List<String> next = jdbcTemplate.queryForList(
                    "SELECT employee_name FROM base_employee WHERE parent_salesman IN ("
                            + placeholders(frontier.size()) + ")",
                    String.class, frontier.toArray());
            frontier = new LinkedHashSet<>(next);
            all.addAll(frontier);
        }
        return all;
    }

    private Set<String> employeeNamesByIds(Set<String> ids) {
        if (ids.isEmpty()) return Set.of();
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT employee_name FROM base_employee WHERE employee_id IN (" + placeholders(ids.size()) + ")",
                String.class, ids.toArray()));
    }

    /** 分类含所有子分类：按 parent_id 逐层展开，返回分类名集合。 */
    private Set<String> expandCategoryNames(Set<String> rootIds) {
        Set<String> ids = new LinkedHashSet<>(rootIds);
        Set<String> frontier = new LinkedHashSet<>(rootIds);
        Set<String> names = new LinkedHashSet<>();
        for (int depth = 0; depth < 10 && !frontier.isEmpty(); depth++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT category_id AS category_id, category_name AS category_name FROM base_category "
                            + "WHERE category_id IN (" + placeholders(frontier.size()) + ")",
                    frontier.toArray());
            frontier.clear();
            for (Map<String, Object> row : rows) {
                String id = str(row.get("category_id"));
                String name = str(row.get("category_name"));
                if (name != null && !name.isBlank()) names.add(name);
                frontier.add(id);
            }
            // 下一层：父在本层的子节点
            if (!frontier.isEmpty()) {
                List<String> children = jdbcTemplate.queryForList(
                        "SELECT category_id FROM base_category WHERE parent_id IN ("
                                + placeholders(frontier.size()) + ")",
                        String.class, frontier.toArray());
                children.removeAll(ids);
                frontier = new LinkedHashSet<>(children);
                ids.addAll(frontier);
            }
        }
        return names;
    }

    private Set<String> expandBrandNames(Set<String> brandIds) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT brand_name FROM base_brand WHERE brand_id IN (" + placeholders(brandIds.size()) + ")",
                String.class, brandIds.toArray()));
    }

    // ============================ 工具 ============================

    static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
