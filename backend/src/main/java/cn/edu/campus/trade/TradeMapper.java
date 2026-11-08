package cn.edu.campus.trade;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TradeMapper {
  @Select("SELECT * FROM orders WHERE id=#{id}")
  Map<String, Object> order(String id);

  @Select("SELECT * FROM orders WHERE id=#{id} FOR UPDATE")
  Map<String, Object> lockOrder(String id);

  @Select(
      "SELECT * FROM orders WHERE buyer_id=#{user} OR seller_id=#{user} ORDER BY created_at DESC,id"
          + " DESC")
  List<Map<String, Object>> orders(String user);

  @Insert(
      "INSERT INTO orders(id,product_id,buyer_id,seller_id,amount,status,created_at,pay_deadline)"
          + " VALUES(#{id},#{product_id},#{buyer_id},#{seller_id},#{amount},#{status},#{created_at},#{pay_deadline})")
  void insertOrder(Map<String, Object> o);

  @Update(
      "UPDATE orders SET"
          + " status=#{status},close_reason=#{close_reason},receipt_deadline=#{receipt_deadline},receipt_remaining=#{receipt_remaining},receipt_paused=#{receipt_paused},shipping_blocked=#{shipping_blocked},settlement_blocked=#{settlement_blocked},payment_generation=#{payment_generation},receipt_generation=#{receipt_generation},version=version+1"
          + " WHERE id=#{id}")
  void saveOrder(Map<String, Object> o);

  @Select("SELECT order_id FROM product_holds WHERE product_id=#{id}")
  String hold(String id);

  @Insert("INSERT INTO product_holds VALUES(#{product},#{order})")
  void holdProduct(@Param("product") String product, @Param("order") String order);

  @Delete("DELETE FROM product_holds WHERE product_id=#{product} AND order_id=#{order}")
  void release(@Param("product") String product, @Param("order") String order);

  @Select("SELECT * FROM accounts WHERE user_id=#{id} FOR UPDATE")
  Map<String, Object> lockAccount(String id);

  @Select("SELECT balance FROM accounts WHERE user_id=#{id}")
  Long balance(String id);

  @Update("UPDATE accounts SET balance=balance+#{delta} WHERE user_id=#{id}")
  void addBalance(@Param("id") String id, @Param("delta") long delta);

  @Select("SELECT * FROM escrows WHERE order_id=#{id} FOR UPDATE")
  Map<String, Object> lockEscrow(String id);

  @Select("SELECT * FROM escrows WHERE order_id=#{id}")
  Map<String, Object> escrow(String id);

  @Insert("INSERT INTO escrows VALUES(#{order},#{amount},'HELD')")
  void escrowCreate(@Param("order") String order, @Param("amount") long amount);

  @Update("UPDATE escrows SET status=#{state} WHERE order_id=#{order} AND status='HELD'")
  int escrowState(@Param("order") String order, @Param("state") String state);

  @Insert("INSERT INTO money_operations VALUES(#{id},#{order},#{kind},#{amount},#{now})")
  void money(
      @Param("id") String id,
      @Param("order") String order,
      @Param("kind") String kind,
      @Param("amount") long amount,
      @Param("now") long now);

  @Insert("INSERT INTO money_entries VALUES(#{operation},#{account},#{amount})")
  void entry(
      @Param("operation") String op,
      @Param("account") String account,
      @Param("amount") long amount);

  @Select("SELECT * FROM shipments WHERE order_id=#{order} AND direction=#{direction}")
  Map<String, Object> shipment(@Param("order") String order, @Param("direction") String direction);

  @Select("SELECT * FROM shipments WHERE order_id=#{id} ORDER BY shipped_at")
  List<Map<String, Object>> shipments(String id);

  @Insert(
      "INSERT INTO"
          + " shipments(id,order_id,direction,tracking_number,evidence,status,shipped_at,due_at)"
          + " VALUES(#{id},#{order_id},#{direction},#{tracking_number},#{evidence},'IN_TRANSIT',#{shipped_at},#{due_at})")
  void shipmentCreate(Map<String, Object> s);

  @Update(
      "UPDATE shipments SET status='DELIVERED',delivered_at=due_at WHERE id=#{id} AND"
          + " status='IN_TRANSIT'")
  void delivered(String id);
}
