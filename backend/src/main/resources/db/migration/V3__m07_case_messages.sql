-- M07 售后留言：买卖双方与客服在售后单内异步沟通
--
-- 说明：不做实时聊天，只在售后单内保存留言，由页面轮询刷新；
-- 留言对售后单的三方（买家、卖家、客服）可见，不改变售后单状态与资金。
CREATE TABLE case_messages(
  id VARCHAR(36) PRIMARY KEY,
  case_id VARCHAR(36) NOT NULL,
  sender_id VARCHAR(36) NOT NULL,
  role VARCHAR(20) NOT NULL,
  content VARCHAR(500) NOT NULL,
  created_at BIGINT NOT NULL,
  INDEX idx_case_messages(case_id, created_at)
);
