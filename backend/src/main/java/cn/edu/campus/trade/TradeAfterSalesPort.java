package cn.edu.campus.trade;
/** M07 calls only after persisting a validated decision in the shared transaction. */
public interface TradeAfterSalesPort { void applyCaseDecision(String orderId,String decisionId); }
