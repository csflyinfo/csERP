-- V77: 司机配送 APP 优化（PRD-27）
--   1. 发车留痕：调度单/行程记录发车公里数与里程照片
--   2. 新参数：司机发车是否强制填写公里数+拍里程照片（默认是）
--   3. 调度明细补 remark（装车退回时写「某某司机退回」留痕，明细删除前同步写到单据）
--
-- 注意 H2 约束：新增列只能 ADD COLUMN IF NOT EXISTS，不支持 DROP。

-- 1) 调度单发车留痕
ALTER TABLE tms_dispatch ADD COLUMN IF NOT EXISTS depart_mileage DECIMAL(10,1);
ALTER TABLE tms_dispatch ADD COLUMN IF NOT EXISTS depart_photo_url VARCHAR(500);
ALTER TABLE tms_dispatch ADD COLUMN IF NOT EXISTS depart_mileage_time TIMESTAMP;

-- 行程同步留痕（与调度单同值，行程页直接取本表）
ALTER TABLE tms_delivery_trip ADD COLUMN IF NOT EXISTS depart_mileage DECIMAL(10,1);
ALTER TABLE tms_delivery_trip ADD COLUMN IF NOT EXISTS depart_photo_url VARCHAR(500);
ALTER TABLE tms_delivery_trip ADD COLUMN IF NOT EXISTS depart_mileage_time TIMESTAMP;

-- 2) 调度明细 remark（装车退回原因留痕；正常流程明细行会删除，单据 remark 才是最终留痕处）
ALTER TABLE tms_dispatch_detail ADD COLUMN IF NOT EXISTS remark VARCHAR(500);

-- 3) 系统参数 P0128（沿用 V75 的 ON DUPLICATE KEY UPDATE 幂等写法）
INSERT INTO sys_param_runtime(param_id, param_key, param_name, param_value, default_value, param_group, remark)
VALUES ('P0128', 'TMS_DEPART_MILEAGE_REQUIRED', '司机发车需里程照片与公里数', 'Y', 'Y', 'TMS配送',
        'Y：确认发车时必须填写发车公里数并拍摄1张里程照片；N：不强制。')
ON DUPLICATE KEY UPDATE param_name = VALUES(param_name),
                        default_value = VALUES(default_value),
                        remark = VALUES(remark);
