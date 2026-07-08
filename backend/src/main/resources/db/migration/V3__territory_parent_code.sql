-- ============================================
-- V3: 片区表加 parent_code，支持树状层级
-- ============================================
ALTER TABLE base_territory ADD COLUMN parent_code VARCHAR(50);
