-- M06 处置结论结构化
--
-- 原来 reports 只有 status（待处理/已处理）与 result（自由文本），列表无法展示"确认违规/不成立"
-- 这类可统计的结论。补一列 decision，处置时写入；历史数据保持 NULL，表示未区分结论。

ALTER TABLE reports ADD COLUMN decision VARCHAR(20) NULL;

CREATE INDEX idx_reports_decision ON reports(decision);
