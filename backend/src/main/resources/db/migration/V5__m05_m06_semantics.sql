-- M05 / M06 语义补充
--
-- 1) 审核结论必须能对应到"被审的那一版"，否则卖家改稿后无法判断结论是否仍然有效。
-- 2) 商品列表按公开可见性过滤，补一条覆盖索引，避免 count 与 list 两次全表扫描。
-- 3) 举报按 (商品, 举报人, 状态) 建索引，供"同一用户对同一商品只保留一条待处理举报"的判定使用。

ALTER TABLE moderation_reviews ADD COLUMN reviewed_version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_reviews_product ON moderation_reviews(product_id, reviewed_version);
CREATE INDEX idx_reports_lookup ON reports(product_id, reporter_id, status);
CREATE INDEX idx_products_public ON products(audit_status, sale_status, created_at, id);
