package cn.edu.campus.identity;

import cn.edu.campus.notification.NotificationPort;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.*;

@Configuration
public class SessionLifecycle {
  static final class SessionEvents implements HttpSessionListener, HttpSessionIdListener {
    private final ObjectProvider<NotificationPort> notifications;

    SessionEvents(ObjectProvider<NotificationPort> notifications) {
      this.notifications = notifications;
    }

    private void close(String id) {
      var n = notifications.getIfAvailable();
      if (n != null) n.closeSession(id);
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent e) {
      close(e.getSession().getId());
    }

    @Override
    public void sessionIdChanged(HttpSessionEvent e, String oldId) {
      close(oldId);
    }
  }

  @Bean
  ServletListenerRegistrationBean<SessionEvents> sessionEvents(
      ObjectProvider<NotificationPort> notifications) {
    return new ServletListenerRegistrationBean<>(new SessionEvents(notifications));
  }
}
