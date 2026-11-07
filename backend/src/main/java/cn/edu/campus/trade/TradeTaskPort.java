package cn.edu.campus.trade;

/** Trusted M08 in-process tasks only; never expose as a browser endpoint. */
public interface TradeTaskPort {
  void deadline(String orderId, String kind, long generation, long dueAt);
}
