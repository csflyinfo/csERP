package com.erp.tms;

import com.erp.system.OperationLogService;
import org.springframework.stereotype.Component;

/**
 * 把统一 {@link OperationLogService} 注入静态 {@link TmsUtil}（PRD-31）。
 *
 * <p>TMS/WMS 的 20+ 控制器/服务都以静态方式调用 {@code TmsUtil.log(...)}，为保持调用点零改动，
 * 由本桥接组件在 Spring 启动时把日志服务 Bean 塞进 TmsUtil 的静态字段；之后 TMS/WMS 日志
 * 与 ERP 走同一写入路径（真实操作人、IP、耗时、中文名、单据时间线、敏感标记）。
 */
@Component
public class TmsLogBridge {

    public TmsLogBridge(OperationLogService opLogService) {
        TmsUtil.initLogService(opLogService);
    }
}
