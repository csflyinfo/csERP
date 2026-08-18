-- V63: 放开 tms_sign_record.dispatch_id / detail_id 的非空约束
--
-- 背景（V60 司机回收链路）：
--   司机回收型退货单支持两条上车路径——
--     1) 组车：任务池勾选 → /tms/dispatch/create → 有 dispatch_id 与 tms_dispatch_detail 行
--     2) 直派：/tms/return-dispatch/assign 只指派司机（不建调度单）→ 无 dispatch_id、更无调度明细行
--   第 2 条路径下司机签收（/tms/app/return/sign）必须能落签收记录，
--   但 V51 建表时这两列是 NOT NULL，插入直接失败。
--
-- 影响面：结算类统计按 dispatch_id 分组（V55），null 行不参与分组，属预期
-- （直派回收没有车次成本可摊）；按 source_bill_no 的查询不受影响。
ALTER TABLE tms_sign_record ALTER COLUMN dispatch_id SET NULL;
ALTER TABLE tms_sign_record ALTER COLUMN detail_id SET NULL;
