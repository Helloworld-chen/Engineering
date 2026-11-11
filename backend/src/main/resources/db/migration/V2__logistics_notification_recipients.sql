-- Exact legacy logistics messages only. Flyway runs before dispatch starts.
-- Preserve unrelated or unverifiable inbox rows; convert wrong-recipient events
-- to silent refreshes so pending/retried events cannot recreate bad notifications.
DELETE n FROM notifications n
JOIN outbox e ON e.id=n.id AND e.recipient_id=n.recipient_id
  AND e.aggregate_id=n.aggregate_id AND BINARY e.message=BINARY n.message
JOIN orders o ON o.id=e.aggregate_id
WHERE o.buyer_id <> o.seller_id AND (
    (e.recipient_id=o.seller_id AND (
      (e.event_type='SHIPPED' AND BINARY e.message=BINARY '卖家已发货，10秒后自动送达') OR
      (e.event_type='DELIVERED' AND BINARY e.message=BINARY '商品已送达，48小时内可确认收货或申请售后')
    )) OR
    (e.recipient_id=o.buyer_id AND (
      (e.event_type='SHIPPED' AND BINARY e.message=BINARY '买家已寄回商品，10秒后自动送达') OR
      (e.event_type='DELIVERED' AND BINARY e.message=BINARY '退货已送达，卖家有24小时验收')
    ))
  );

UPDATE outbox e JOIN orders o ON o.id=e.aggregate_id
SET e.event_type='BUSINESS_CHANGED', e.message=''
WHERE o.buyer_id <> o.seller_id AND (
    (e.recipient_id=o.seller_id AND (
      (e.event_type='SHIPPED' AND BINARY e.message=BINARY '卖家已发货，10秒后自动送达') OR
      (e.event_type='DELIVERED' AND BINARY e.message=BINARY '商品已送达，48小时内可确认收货或申请售后')
    )) OR
    (e.recipient_id=o.buyer_id AND (
      (e.event_type='SHIPPED' AND BINARY e.message=BINARY '买家已寄回商品，10秒后自动送达') OR
      (e.event_type='DELIVERED' AND BINARY e.message=BINARY '退货已送达，卖家有24小时验收')
    ))
  );
