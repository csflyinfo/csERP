package com.erp.report.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.erp.common.security.CurrentUser;
import com.erp.common.security.PermissionService;
import com.erp.common.security.datascope.DataScopeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * 报表查询护栏（方案 §4.6）：
 * <ol>
 *   <li>全局并发信号量 15、单用户并发 3，超出快速失败（不排队耗尽 Web 线程）；</li>
 *   <li>30s 语句超时由 {@link ReportDataSourceConfig} 保证，这里统一翻译为中文业务异常；</li>
 *   <li>相同 报表+入参+可见性（数据范围签名+字段权限签名）的结果短缓存：明细 60s、
 *       DWS 汇总 5min；同角色同范围的不同账号共享缓存项（SELF/默认仅本人范围按账号隔离），
 *       避免 50 个同岗位用户打开同一报表时重复统计同样的数据；</li>
 *   <li>单飞合并：缓存未命中但同一键已有在途查询时，后来者等待并共享同一个结果
 *       （不占全局数据库名额），同条件并发洪峰真正落库的重查询只有 1 个；</li>
 *   <li>每条查询落 rpt_query_log（耗时/行数，&gt;3s 标 slow），审计写入失败绝不影响查询。</li>
 * </ol>
 */
@Component
public class ReportGuard {

    private static final Logger log = LoggerFactory.getLogger(ReportGuard.class);
    private static final int GLOBAL_PERMITS = 15;
    private static final int PER_USER_PERMITS = 3;
    private static final int SLOW_THRESHOLD_MS = 3_000;
    /** 跟随者等待同键在途查询的最长时间：覆盖「计数+取页两条语句各 30s」的上限。 */
    private static final long INFLIGHT_WAIT_SECONDS = 65;

    private final Semaphore global = new Semaphore(GLOBAL_PERMITS, true);
    private final ConcurrentHashMap<String, Semaphore> userSemaphores = new ConcurrentHashMap<>();
    /** 单飞合并：同一缓存键正在执行时，后来者等待同一个 Future，不再各占数据库名额重查。 */
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

    private final Cache<String, Object> detailCache = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(300).build();
    private final Cache<String, Object> dwsCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(300).build();

    private final JdbcTemplate primaryJdbc;
    private final DataScopeService dataScope;
    private final PermissionService permissionService;

    public ReportGuard(JdbcTemplate primaryJdbc, DataScopeService dataScope,
                       PermissionService permissionService) {
        this.primaryJdbc = primaryJdbc;
        this.dataScope = dataScope;
        this.permissionService = permissionService;
    }

    /**
     * 执行一次受保护的报表查询。
     *
     * @param dws true=走 DWS/快照（缓存 5min）；false=明细实时（缓存 60s）
     */
    public <T> T run(String reportCode, Map<String, Object> body, boolean dws, Supplier<T> loader) {
        return run(reportCode, body, dws, loader, r -> 0);
    }

    /** 带审计行数统计的重载（如分页结果传 total）。 */
    @SuppressWarnings("unchecked")
    public <T> T run(String reportCode, Map<String, Object> body, boolean dws,
                     Supplier<T> loader, ToIntFunction<T> rowCounter) {
        String userKey = userKey();
        String visibilityKey = visibilityKey();
        String paramHash = sha256(String.valueOf(body));
        String cacheKey = reportCode + "|" + visibilityKey + "|" + paramHash;
        Cache<String, Object> cache = dws ? dwsCache : detailCache;
        Object cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            return (T) cached;
        }

        // 单飞合并：同键已有在途查询时跟随等待同一结果，不占全局数据库名额（50 人同条件
        // 同时打开报表时，真正落库的重查询只有 1 个，其余共享其结果或异常）
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> leader = inflight.putIfAbsent(cacheKey, mine);
        if (leader != null) {
            return awaitInflight(leader);
        }

        Semaphore userSem = userSemaphores.computeIfAbsent(userKey, k -> new Semaphore(PER_USER_PERMITS, true));
        boolean globalAcquired = false;
        boolean userAcquired = false;
        long t0 = System.currentTimeMillis();
        T result;
        try {
            userAcquired = userSem.tryAcquire();
            if (!userAcquired) {
                throw new IllegalArgumentException("报表查询排队中（每人最多 3 个并发），请稍后再试或缩小查询范围");
            }
            try {
                globalAcquired = global.tryAcquire(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("查询被中断", e);
            }
            if (!globalAcquired) {
                throw new IllegalArgumentException("报表查询繁忙，请稍后再试或缩小查询范围");
            }
            result = doLoad(reportCode, loader);

            long cost = System.currentTimeMillis() - t0;
            cache.put(cacheKey, result);
            audit(reportCode, paramHash, cost, result, rowCounter);
            mine.complete(result);
            return result;
        } catch (Throwable t) {
            // 异常（含护栏快速失败/超时/10万行上限）不写缓存，跟随者收同一异常后可自行重试
            mine.completeExceptionally(t);
            throw t;
        } finally {
            inflight.remove(cacheKey, mine);
            if (globalAcquired) global.release();
            if (userAcquired) userSem.release();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T awaitInflight(CompletableFuture<Object> leader) {
        try {
            return (T) leader.get(INFLIGHT_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error er) throw er;
            throw new RuntimeException(cause);
        } catch (TimeoutException e) {
            throw new IllegalArgumentException("报表查询繁忙，请稍后再试或缩小查询范围");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("查询被中断", e);
        }
    }

    private <T> T doLoad(String reportCode, Supplier<T> loader) {
        try {
            return loader.get();
        } catch (DataAccessException e) {
            if (isTimeout(e)) {
                log.warn("报表 {} 查询超时：{}", reportCode, e.getMessage());
                throw new IllegalArgumentException("查询超时（超过 30 秒），请缩小日期范围或改用异步导出");
            }
            throw e;
        }
    }

    private boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getSimpleName();
            String msg = String.valueOf(t.getMessage());
            if (name.contains("Timeout") || msg.toLowerCase().contains("timeout")
                    || msg.contains("超时")) {
                return true;
            }
        }
        return false;
    }

    private <T> void audit(String reportCode, String paramHash, long costMs, T result) {
        audit(reportCode, paramHash, costMs, result, r -> 0);
    }

    private <T> void audit(String reportCode, String paramHash, long costMs, T result,
                           ToIntFunction<T> rowCounter) {
        try {
            int rowCount = rowCounter.applyAsInt(result);
            CurrentUser.Principal u = CurrentUser.get();
            primaryJdbc.update("""
                    INSERT INTO rpt_query_log(report_code, param_hash, user_id, user_name, data_scope,
                        cost_ms, row_count, slow, created_at)
                    VALUES (?, ?, ?, ?, NULL, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, reportCode, paramHash,
                    u == null ? null : u.userId(),
                    u == null ? null : u.displayName(),
                    (int) Math.min(costMs, Integer.MAX_VALUE), rowCount,
                    costMs > SLOW_THRESHOLD_MS);
        } catch (Exception e) {
            log.debug("rpt_query_log 写入失败：{}", e.getMessage());
        }
    }

    /** 90 天以前的审计日志清理（由定时任务调用）。 */
    public int purgeQueryLog(java.time.LocalDateTime cutoff) {
        return primaryJdbc.update("DELETE FROM rpt_query_log WHERE created_at < ?", cutoff);
    }

    private static String userKey() {
        CurrentUser.Principal u = CurrentUser.get();
        return u == null || u.userId() == null ? "anonymous" : u.userId();
    }

    /**
     * 结果可见性键：决定两个账号能否共享同一缓存项。
     * 超管结果一致（不脱敏、无范围）共用固定键；普通用户必须数据范围签名与字段授权签名
     * 同时相同才共享——脱敏在 loader 内已完成，字段权限不同绝不会命中同一结果。
     */
    private String visibilityKey() {
        CurrentUser.Principal u = CurrentUser.get();
        if (u == null || u.userId() == null) return "anonymous";
        if (u.isSuperAdmin()) return "superadmin";
        return "scope=" + dataScope.currentScopeSignature()
                + "|fields=" + permissionService.currentFieldCodesSignature();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
