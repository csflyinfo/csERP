# 上线手册 — PRD-28 系统用户与权限管理 V2（RBAC，卡片1~10）

> 配套：方案《docs/系统用户及权限管理方案.md》、计划《docs/开发计划-PRD28-RBAC.md》、
> 核对脚本《development/07-deployment/prd28-rbac-launch-checks.sql》。
> 生产部署只从 tag 出，不从分支出。本手册对应迁移版本 **V102 / V103 / V104 / V107 / V108**（V105/V106 空出，允许跳号）。

## 1. 上线前置条件（不满足不发布）

1. **prod 短信通道已配置**（卡片10 硬门槛，否则应用拒启）：
   - `sms.webhook-url`：必填，必须 `http(s)://` 开头的企业短信网关 Webhook；
   - `sms.webhook-token`：可选，配置后以 `Authorization: Bearer <token>` 发送；
   - `sms.login-template-code`：可选，登录短信模板号，随报文透传；
   - 网关契约：`POST <webhook-url>`，JSON `{"mobile":"...","code":"123456","bizType":"LOGIN","templateCode":"..."}`，非 2xx 即发送失败；
   - 配置方式：外部化 `application-prod.yml` 或环境变量（`SPRING_APPLICATION_JSON` / `SMS_WEBHOOK_URL` 等按运维约定），**不入库不入 git**；
   - prod 下万能码 `888888` 不生效；启动缺配置时报「生产环境必须配置短信网关 sms.webhook-url（司机短信登录依赖），启动中止」。
2. 已用**生产快照**在预发演练 V102（卡片11 第 1 项）：重点观察长脚本执行时长/锁表、存量角色兜底授权、存量用户全部能登录。
3. 已在预发执行核对脚本，全部 PASS（见第 4 节）。
4. 发布 tag 已从 main 切出并完成包体验证。

## 2. 备份

- **H2 文件库**：停应用后复制 `backend/data/erp-v1.mv.db` 到带时间戳的备份（应用运行中复制可能不一致）；
- MySQL（如已切换）：`mysqldump --single-transaction` 全库备份；
- 保留备份至少到上线观察期结束（卡片12 最早在上线 ≥2 周后）。

## 3. 发布步骤

1. 停旧应用；
2. 备份数据库（第 2 节）；
3. 部署 tag 构建的新 jar（`mvn clean package -DskipTests` 产物 `erp-wms-tms-backend-0.1.0-SNAPSHOT.jar`，生产由 tag 出包）；
4. 以 `prod` profile 启动（无 Redis 的环境继续用 `--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration`）；
5. Flyway 随启动自动执行 V102~V108，无需手工跑 SQL；V102 建表+种子最长，关注启动日志中 flyway 段无 FAIL；
6. 启动成功标志日志（ApplicationReadyEvent 后一行）：

   ```
   权限同步完成：菜单 192（新增N 停用N），功能点 1699（新增33 停用0），字段 26 项全部对拍通过，PDA 六角色授权 199 行，司机三角色授权 104 行
   ```

   - 「新增」数与库现状有关：全新库菜单新增 192、功能点新增 1699；已跑过卡片1~9 的库只新增卡片10 差额（功能点 +33）；
   - 若日志为 `权限元数据同步失败` ERROR 堆栈，应用已中止，按堆栈排查，不要放流量。
7. 健康检查 `GET /api/actuator/health` = UP。

## 4. 上线后核对（放流量前）

执行只读脚本 `development/07-deployment/prd28-rbac-launch-checks.sql`（H2 Shell / mysql CLI 均可，脚本头有用法），33 项全部 `PASS`：

- A. Flyway 5 个版本成功、无失败记录；
- B. 系统内置菜单 193 行（代码 192 + `_global_` 占位 1）、功能点 1699、字段 26；
- C. 21 个内置角色齐备且 NORMAL；**若 C1 FAIL**，用脚本内注释的定位语句查出缺失角色，从 V102 种子补回角色行及其授权后再继续；
- D. 授权矩阵：司机 46/10/48 功能点、菜单 16/5/16，PDA 六角色功能点合计 199，超管功能点 1699；
- E. 九类孤儿关系全 0；
- F. 启用账号均有角色、admin 挂 SYS_ADMIN、超管角色未停用；
- G. `sys_sms_code` 风控三列（fail_count/lock_until/send_ip）齐全。

另做一次人工冒烟（prod 真实通道）：

1. ERP 后台 admin 登录、菜单树完整；
2. PDA 用一个作业账号走选仓登录；
3. 司机端用一个档案在册（is_deliveryman=TRUE、status=NORMAL）司机的手机号收真实验证码登录（验证整条短信通道）；错误码连试 5 次确认锁定提示。

## 5. 上线首日操作（卡片11 第 3 项）

1. 管理员按角色矩阵（方案 §7.1~§7.3）在【角色管理】逐角色核对/补授权；「不收款司机」「新手司机」等通过**复制 TMS_DRIVER 后裁剪**创建自定义角色，不要改内置角色的 driver.*/wms_pda.* 授权（重启后会被代码矩阵收回）；
2. 存量司机首次短信登录会自动开通账号（工号为用户名、16 位随机密码、must_change_pwd=TRUE、绑 TMS_DRIVER）；账号已存在的司机走密码直签，旧版 APP 令牌（无 appType）访问 /tms/app/** 会收到 401「请使用司机端重新登录」，需重新登录；
3. 公告默认权限变化：按功能点授权后，部分账号可能出现入口消失/按钮隐藏（预期行为）；异议走角色补授权；
4. 参数 `TMS_ONSITE_RETURN_ENABLED` 与现场退货入口的双控关系同步告知调度（权限+参数都满足才显示）。

## 6. 回滚

- V102~V108 **全部为新增表/新增列/种子数据，不删旧列、不改旧语义**，旧版本 jar 可直接回退启动；
- 回退顺序：停新应用 → 恢复第 2 节备份（或直接用旧 jar，旧代码不读新表新列）→ 验证登录；
- 已通过新版登录产生的 `sys_sms_code`、自动开通的司机用户行不影响旧代码；如需彻底清除按卡片12 思路处理（当前阶段保留）；
- 短信 webhook 配置对旧版本无害，回滚后旧版固定 888888 逻辑恢复（仅限非 prod 构建）。

## 7. 已知风险与注意

- V102 是长脚本（12 张新表 + 21 角色 + 菜单/功能/字段种子 + 存量映射），生产库须先在快照演练并记录耗时，避免在业务高峰执行；
- 应用启动即做权限元数据对账：内置角色的 `driver.%`/`wms_pda.%` 授权会被矩阵覆盖（收回矩阵外、补齐矩阵内），**手工给内置移动角色加的同前缀授权不保留**；自定义角色不受影响；
- prod 启动强校验短信配置，配置变更后需重启生效；
- 本手册不覆盖卡片12（V109 清旧：SystemController 硬编码菜单树、fallback-menus.js、冗余列评估），该卡在上线观察 ≥2 周后另行执行。
