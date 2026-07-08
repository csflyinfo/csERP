-- ============================================
-- V26: 回填已审核客户调价单的商品价格到「客户价格查询」
--
-- 业务背景：
--   base_customer_price_item / base_customer_price_change_log 由 V25 新建，
--   之后才在审核环节写入。因此 V25 之前**已审核**的调价单，其价格没有进入
--   客户价格查询与客户商品变价查询 —— 本迁移把这批历史数据补进去。
--
-- 口径与 CustomerPriceController.applyUnitPrices() 保持一致：
--   · 只回填 status = 'APPROVED' 的单据；待审核/已作废不算生效价
--   · 只回填「已启用且填了现价」的单位；未启用或空价不生成记录
--   · 小单位恒启用；中/大单位以单据快照的 *_unit_enabled 为准
--   · 一客户一商品一单位只保留一条 —— 同一组合被多张单调过价时，
--     取**最新单据**（adjust_no 最大）的价格作为当前价
--
-- 幂等性：
--   插入前用 NOT EXISTS 排除已有记录，重复执行不会产生重复行。
--   （Flyway 正常只跑一次，但本脚本允许手工重跑做数据修补。）
--
-- 变价日志的 old_price 一律为 NULL（首次设价）：
--   历史单据的「上一次价格」已无从可靠还原 —— 老表 base_customer_price
--   只存最终态、不留变更链。硬凑一个变价前的值会造成假数据，
--   宁可如实标记为首次设价。
-- ============================================

-- ---------- 1. 回填当前生效价 ----------
-- 三个单位各跑一遍：结构相同，只是取的列不同。
-- 用 adjust_no 最大的单据作为该 (客户,商品,单位) 的最新价。

-- 小单位（level 1，恒启用）
INSERT INTO base_customer_price_item(
    id, customer_code, customer_name, goods_code, goods_name,
    unit_level, unit_name, standard_price, price,
    adjust_no, effective_mode, valid_range, is_active)
SELECT CONCAT('CPIBF1', RIGHT(REPLACE(RANDOM_UUID(), '-', ''), 10)),
       t.customer_code, t.customer_name, t.goods_code, t.goods_name,
       1, t.small_unit, t.small_standard_price, t.small_current_price,
       t.adjust_no, t.effective_mode, t.valid_range, TRUE
FROM (
    SELECT d.customer_code, d.customer_name, d.goods_code, d.goods_name,
           d.small_unit, d.small_standard_price, d.small_current_price,
           d.adjust_no, d.effective_mode, d.valid_range,
           ROW_NUMBER() OVER (PARTITION BY d.customer_code, d.goods_code ORDER BY d.adjust_no DESC) rn
    FROM (
        SELECT h.customer_code, h.customer_name, h.adjust_no, h.effective_mode, h.valid_range,
               dt.goods_code, dt.goods_name, dt.small_unit,
               dt.small_standard_price, dt.small_current_price
        FROM base_customer_price_adjust h
        JOIN base_customer_price_adjust_detail dt ON dt.adjust_id = h.adjust_id
        WHERE h.status = 'APPROVED'
          AND dt.small_current_price IS NOT NULL
    ) d
) t
WHERE t.rn = 1
  AND NOT EXISTS (
      SELECT 1 FROM base_customer_price_item i
      WHERE i.customer_code = t.customer_code
        AND i.goods_code = t.goods_code
        AND i.unit_level = 1
  );

-- 中单位（level 2，需 medium_unit_enabled）
INSERT INTO base_customer_price_item(
    id, customer_code, customer_name, goods_code, goods_name,
    unit_level, unit_name, standard_price, price,
    adjust_no, effective_mode, valid_range, is_active)
SELECT CONCAT('CPIBF2', RIGHT(REPLACE(RANDOM_UUID(), '-', ''), 10)),
       t.customer_code, t.customer_name, t.goods_code, t.goods_name,
       2, t.medium_unit, t.medium_standard_price, t.medium_current_price,
       t.adjust_no, t.effective_mode, t.valid_range, TRUE
FROM (
    SELECT d.customer_code, d.customer_name, d.goods_code, d.goods_name,
           d.medium_unit, d.medium_standard_price, d.medium_current_price,
           d.adjust_no, d.effective_mode, d.valid_range,
           ROW_NUMBER() OVER (PARTITION BY d.customer_code, d.goods_code ORDER BY d.adjust_no DESC) rn
    FROM (
        SELECT h.customer_code, h.customer_name, h.adjust_no, h.effective_mode, h.valid_range,
               dt.goods_code, dt.goods_name, dt.medium_unit,
               dt.medium_standard_price, dt.medium_current_price
        FROM base_customer_price_adjust h
        JOIN base_customer_price_adjust_detail dt ON dt.adjust_id = h.adjust_id
        WHERE h.status = 'APPROVED'
          AND dt.medium_unit_enabled = TRUE
          AND dt.medium_current_price IS NOT NULL
    ) d
) t
WHERE t.rn = 1
  AND NOT EXISTS (
      SELECT 1 FROM base_customer_price_item i
      WHERE i.customer_code = t.customer_code
        AND i.goods_code = t.goods_code
        AND i.unit_level = 2
  );

-- 大单位（level 3，需 large_unit_enabled）
INSERT INTO base_customer_price_item(
    id, customer_code, customer_name, goods_code, goods_name,
    unit_level, unit_name, standard_price, price,
    adjust_no, effective_mode, valid_range, is_active)
SELECT CONCAT('CPIBF3', RIGHT(REPLACE(RANDOM_UUID(), '-', ''), 10)),
       t.customer_code, t.customer_name, t.goods_code, t.goods_name,
       3, t.large_unit, t.large_standard_price, t.large_current_price,
       t.adjust_no, t.effective_mode, t.valid_range, TRUE
FROM (
    SELECT d.customer_code, d.customer_name, d.goods_code, d.goods_name,
           d.large_unit, d.large_standard_price, d.large_current_price,
           d.adjust_no, d.effective_mode, d.valid_range,
           ROW_NUMBER() OVER (PARTITION BY d.customer_code, d.goods_code ORDER BY d.adjust_no DESC) rn
    FROM (
        SELECT h.customer_code, h.customer_name, h.adjust_no, h.effective_mode, h.valid_range,
               dt.goods_code, dt.goods_name, dt.large_unit,
               dt.large_standard_price, dt.large_current_price
        FROM base_customer_price_adjust h
        JOIN base_customer_price_adjust_detail dt ON dt.adjust_id = h.adjust_id
        WHERE h.status = 'APPROVED'
          AND dt.large_unit_enabled = TRUE
          AND dt.large_current_price IS NOT NULL
    ) d
) t
WHERE t.rn = 1
  AND NOT EXISTS (
      SELECT 1 FROM base_customer_price_item i
      WHERE i.customer_code = t.customer_code
        AND i.goods_code = t.goods_code
        AND i.unit_level = 3
  );

-- ---------- 2. 回填变价记录 ----------
-- 每张已审核单据的每个启用单位一条，old_price 留空（见文件头说明）。
-- operator 取单据的审核人，没有则退回制单人。

-- 小单位
INSERT INTO base_customer_price_change_log(
    id, adjust_no, bill_date, customer_code, customer_name,
    goods_code, goods_name, unit_level, unit_name,
    category_name, brand_name, old_price, new_price,
    effective_mode, valid_range, operator, remark, created_at)
SELECT CONCAT('CPLBF1', RIGHT(REPLACE(RANDOM_UUID(), '-', ''), 10)),
       h.adjust_no, COALESCE(h.audit_date, h.bill_date), h.customer_code, h.customer_name,
       dt.goods_code, dt.goods_name, 1, dt.small_unit,
       dt.category_name, dt.brand_name, NULL, dt.small_current_price,
       h.effective_mode, h.valid_range,
       COALESCE(h.auditor_name, h.creator_name), h.remark,
       COALESCE(h.audit_time, h.create_time, CURRENT_TIMESTAMP)
FROM base_customer_price_adjust h
JOIN base_customer_price_adjust_detail dt ON dt.adjust_id = h.adjust_id
WHERE h.status = 'APPROVED'
  AND dt.small_current_price IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM base_customer_price_change_log l
      WHERE l.adjust_no = h.adjust_no
        AND l.goods_code = dt.goods_code
        AND l.unit_level = 1
  );

-- 中单位
INSERT INTO base_customer_price_change_log(
    id, adjust_no, bill_date, customer_code, customer_name,
    goods_code, goods_name, unit_level, unit_name,
    category_name, brand_name, old_price, new_price,
    effective_mode, valid_range, operator, remark, created_at)
SELECT CONCAT('CPLBF2', RIGHT(REPLACE(RANDOM_UUID(), '-', ''), 10)),
       h.adjust_no, COALESCE(h.audit_date, h.bill_date), h.customer_code, h.customer_name,
       dt.goods_code, dt.goods_name, 2, dt.medium_unit,
       dt.category_name, dt.brand_name, NULL, dt.medium_current_price,
       h.effective_mode, h.valid_range,
       COALESCE(h.auditor_name, h.creator_name), h.remark,
       COALESCE(h.audit_time, h.create_time, CURRENT_TIMESTAMP)
FROM base_customer_price_adjust h
JOIN base_customer_price_adjust_detail dt ON dt.adjust_id = h.adjust_id
WHERE h.status = 'APPROVED'
  AND dt.medium_unit_enabled = TRUE
  AND dt.medium_current_price IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM base_customer_price_change_log l
      WHERE l.adjust_no = h.adjust_no
        AND l.goods_code = dt.goods_code
        AND l.unit_level = 2
  );

-- 大单位
INSERT INTO base_customer_price_change_log(
    id, adjust_no, bill_date, customer_code, customer_name,
    goods_code, goods_name, unit_level, unit_name,
    category_name, brand_name, old_price, new_price,
    effective_mode, valid_range, operator, remark, created_at)
SELECT CONCAT('CPLBF3', RIGHT(REPLACE(RANDOM_UUID(), '-', ''), 10)),
       h.adjust_no, COALESCE(h.audit_date, h.bill_date), h.customer_code, h.customer_name,
       dt.goods_code, dt.goods_name, 3, dt.large_unit,
       dt.category_name, dt.brand_name, NULL, dt.large_current_price,
       h.effective_mode, h.valid_range,
       COALESCE(h.auditor_name, h.creator_name), h.remark,
       COALESCE(h.audit_time, h.create_time, CURRENT_TIMESTAMP)
FROM base_customer_price_adjust h
JOIN base_customer_price_adjust_detail dt ON dt.adjust_id = h.adjust_id
WHERE h.status = 'APPROVED'
  AND dt.large_unit_enabled = TRUE
  AND dt.large_current_price IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM base_customer_price_change_log l
      WHERE l.adjust_no = h.adjust_no
        AND l.goods_code = dt.goods_code
        AND l.unit_level = 3
  );
