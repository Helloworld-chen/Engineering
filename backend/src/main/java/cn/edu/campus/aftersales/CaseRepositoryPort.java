package cn.edu.campus.aftersales;

import java.util.*;

/** M07-owned persistence. Called only after the product/order locks. */
public interface CaseRepositoryPort {
  List<Map<String, Object>> lockForOrder(String orderId);

  List<Map<String, Object>> forOrder(String orderId);

  void save(Map<String, Object> row);

  Map<String, Object> decision(String decisionId);
}
