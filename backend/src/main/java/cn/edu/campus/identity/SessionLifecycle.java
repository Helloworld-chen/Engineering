package cn.edu.campus.identity;
import cn.edu.campus.notification.NotificationPort;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.beans.factory.ObjectProvider;
@Configuration
public class SessionLifecycle {
 @Bean ServletListenerRegistrationBean<HttpSessionListener> sessionEvents(ObjectProvider<NotificationPort> notifications){return new ServletListenerRegistrationBean<>(new HttpSessionListener(){public void sessionDestroyed(HttpSessionEvent e){var n=notifications.getIfAvailable();if(n!=null)n.closeSession(e.getSession().getId());}});}
}
