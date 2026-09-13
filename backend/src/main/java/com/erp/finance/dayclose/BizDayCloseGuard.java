package com.erp.finance.dayclose;

import com.erp.system.SysParamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 业务日结封单守卫（PRD-33 §5.2）。
 *
 * <p>已生效单据的冲销/变更动作（反审核、作废、删除、撤签、核销冲回、争议、桥接冲销等）
 * 落库前必须显式调用本守卫；未审核草稿的增删改一律不守卫。
 *
 * <p>设计要点：
 * <ul>
 *   <li>总开关 {@code BIZ_DAY_CLOSE_ENABLED=N} 或库中从无日结记录时全部 no-op，上线即兼容存量；</li>
 *   <li>封单日（{@code max(biz_day_close.close_date)}）走 volatile 缓存，
 *       日结/反日结事务提交后调 {@link #evict()}，不在业务链路里反复查表；</li>
 *   <li>不使用 AOP（离线包无 aspectjweaver），不挂 GL 事件钩子，全部由服务方法内联显式调用；</li>
 *   <li>盘点单、收付款单、费用单按单据上的记账日期判封单（v1.3：该日期不回填），
 *       其余单据审核动作生效日恒为当天，只需防"当天已封"。</li>
 * </ul>
 */
@Service
public class BizDayCloseGuard {

    private static final Logger log = LoggerFactory.getLogger(BizDayCloseGuard.class);

    private final JdbcTemplate jdbcTemplate;
    private final SysParamService sysParam;

    /** null = 尚未加载；查询本身在无任何日结记录时也缓存为 null（由 evict 失效）。 */
    private volatile LocalDate cachedLastClosed;
    private volatile boolean loaded = false;

    public BizDayCloseGuard(JdbcTemplate jdbcTemplate, SysParamService sysParam) {
        this.jdbcTemplate = jdbcTemplate;
        this.sysParam = sysParam;
    }

    /**
     * 按生效日期判封单。生效日 ≤ 封单日即抛 IllegalArgumentException（中文文案直接展示）。
     *
     * @param effectiveDate 单据生效日（审核日 / 签收日 / 收付款记账日期 / 盘点日）；null 放行
     * @param bizType       业务类型中文名（仅日志定位用）
     * @param billNo        单号（仅日志定位用）
     */
    public void assertWritable(LocalDate effectiveDate, String bizType, String billNo) {
        if (effectiveDate == null || !enabled()) {
            return;
        }
        LocalDate last = lastClosedDate();
        if (last != null && !effectiveDate.isAfter(last)) {
            log.info("日结封单拦截：type={}, bill={}, date={}, lastClosed={}", bizType, billNo, effectiveDate, last);
            throw new IllegalArgumentException(String.format(BizDayCloseConst.CLOSED_MSG, effectiveDate));
        }
    }

    /**
     * 头表反查便捷重载：按头表日期列判封单。表名/列名均为代码内常量，禁止拼用户输入。
     *
     * @param table      头表名（如 fin_receipt_bill）
     * @param dateColumn 记账日期列（如 receipt_date）
     * @param idColumn   主键列（如 bill_id / id）
     * @param id         主键值
     * @param bizType    业务类型中文名
     */
    public void assertBillWritable(String table, String dateColumn, String idColumn, Object id, String bizType) {
        if (id == null || !enabled()) {
            return;
        }
        String sql = "SELECT " + dateColumn + " FROM " + table + " WHERE " + idColumn + " = ?";
        Object v = jdbcTemplate.query(sql, ps -> ps.setObject(1, id), rs -> rs.next() ? rs.getObject(1) : null);
        assertWritable(toLocalDate(v), bizType, String.valueOf(id));
    }

    /** 封单日（最近一次已日结日期）；库中无日结记录返回 null。 */
    public LocalDate lastClosedDate() {
        LocalDate snapshot = cachedLastClosed;
        if (loaded) {
            return snapshot;
        }
        synchronized (this) {
            if (loaded) {
                return cachedLastClosed;
            }
            Object v = jdbcTemplate.queryForObject("SELECT MAX(close_date) FROM biz_day_close", Object.class);
            cachedLastClosed = toLocalDate(v);
            loaded = true;
            return cachedLastClosed;
        }
    }

    /**
     * 区间防封：管理端重算 DWS/快照时，区间起点已封单即拒（日结内部调用走 DWS 服务本身，
     * 不经过管理入口，天然绕过）。null 区间放行。
     */
    public void assertRangeOpen(LocalDate start, LocalDate end, String bizType) {
        if (start == null || !enabled()) {
            return;
        }
        LocalDate last = lastClosedDate();
        if (last != null && !start.isAfter(last)) {
            throw new IllegalArgumentException("日期区间 " + start + " ~ " + end
                    + " 含已日结封单日期（当前封单日 " + last + "），"
                    + (bizType == null ? "" : bizType + "：") + "请先反日结后再操作");
        }
    }

    /** 指定日期是否已封单。 */
    public boolean isClosed(LocalDate date) {
        if (date == null) {
            return false;
        }
        LocalDate last = lastClosedDate();
        return last != null && !date.isAfter(last);
    }

    /** 守卫是否启用（开关默认 Y；参数不可查时按启用处理，保证控制不失效）。 */
    public boolean enabled() {
        return sysParam.getBool(BizDayCloseConst.PARAM_ENABLED, true);
    }

    /** 日结/反日结提交后调用，强制下次重新查库。 */
    public void evict() {
        loaded = false;
        cachedLastClosed = null;
    }

    /**
     * H2 经 getObject 取回的日期类型可能是 LocalDate / java.sql.Date / Timestamp，
     * 各头表日期列类型不一（DATE/TIMESTAMP），统一转 LocalDate。
     */
    public static LocalDate toLocalDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof LocalDateTime dt) {
            return dt.toLocalDate();
        }
        if (v instanceof java.sql.Date sd) {
            return sd.toLocalDate();
        }
        if (v instanceof Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        if (v instanceof Date d) {
            return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        return LocalDate.parse(String.valueOf(v).substring(0, 10));
    }
}
