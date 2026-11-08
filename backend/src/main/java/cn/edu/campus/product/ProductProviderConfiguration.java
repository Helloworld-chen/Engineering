package cn.edu.campus.product;

import cn.edu.campus.files.FilePort;
import cn.edu.campus.notification.TimePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class ProductProviderConfiguration {
  @Bean
  @ConditionalOnProperty(name = "market.providers.product", havingValue = "demo")
  ProductPort demoProduct(JdbcTemplate db, TimePort clock, FilePort files) {
    return new JdbcProductAdapter(db, clock, files);
  }

  @Bean
  @ConditionalOnProperty(name = "market.providers.product", havingValue = "real")
  ProductPort realProduct(JdbcTemplate db, TimePort clock, FilePort files) {
    return new JdbcProductAdapter(db, clock, files);
  }
}
