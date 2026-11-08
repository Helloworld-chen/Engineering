package cn.edu.campus.shared;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Order(0)
@ConditionalOnProperty(name = "market.demo", havingValue = "true")
public class DemoSeed implements ApplicationRunner {
  private final JdbcTemplate db;
  private final PasswordEncoder encoder;

  public DemoSeed(JdbcTemplate db, PasswordEncoder encoder) {
    this.db = db;
    this.encoder = encoder;
  }

  public void run(ApplicationArguments args) {
    seed();
  }

  public void seed() {
    for (String name : new String[] {"buyer", "seller", "other", "admin", "support"}) {
      String role = name.equals("admin") ? "ADMIN" : name.equals("support") ? "SUPPORT" : "USER";
      db.update(
          "INSERT IGNORE INTO users VALUES(?,?,?,?,?)",
          name,
          name,
          encoder.encode("Campus123!"),
          switch (name) {
            case "buyer" -> "陈同学";
            case "seller" -> "林同学";
            case "admin" -> "商品管理员";
            case "support" -> "售后客服";
            default -> "周同学";
          },
          role);
      db.update(
          "INSERT IGNORE INTO accounts VALUES(?,?)", name, name.equals("seller") ? 5000 : 20000);
    }
    String[] names = {"九成新机械键盘", "线性代数教材与笔记", "宿舍可调光台灯", "入门羽毛球拍", "闲置笔记本支架", "便携保温杯"};
    String[] cats = {"数码", "图书", "生活", "运动", "数码", "生活"};
    String[] owners = {"seller", "seller", "seller", "seller", "buyer", "other"};
    long[] prices = {12800, 2800, 3600, 6900, 2400, 1800};
    for (int i = 0; i < names.length; i++)
      db.update(
          "INSERT IGNORE INTO"
              + " products(id,seller_id,title,description,category,price,images,audit_status,sale_status,created_at)"
              + " VALUES(?,?,?,?,?,?,'[]','APPROVED','AVAILABLE',?)",
          "demo-product-" + i,
          owners[i],
          names[i],
          "校园自用闲置，功能正常。商品情况请以照片和卖家说明为准。此条为课程演示数据。",
          cats[i],
          prices[i],
          System.currentTimeMillis() - i * 3600000L);
  }
}
