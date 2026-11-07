package cn.edu.campus.shared;
import cn.edu.campus.notification.TaskRuntimePort;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import jakarta.servlet.http.*;
import java.util.Set;
@Component
public class RecoveryGate implements WebMvcConfigurer {
 private final TaskRuntimePort runtime;private final MutationGate gate;
 public RecoveryGate(TaskRuntimePort runtime,MutationGate gate){this.runtime=runtime;this.gate=gate;}
 public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){
  public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler){
   if(Set.of("GET","HEAD","OPTIONS").contains(req.getMethod()))return true;
   // Lifecycle endpoints acquire the write lock themselves, after checking administrator identity.
   if(Set.of("/api/v1/demo/reset","/api/v1/demo/advance").contains(req.getRequestURI()))return true;
   gate.enter();if(!runtime.ready()){gate.leave();throw new BusinessException("RECOVERING","系统恢复处理中，请稍后重试",503);}req.setAttribute("marketMutationLock",true);return true;
  }
  public void afterCompletion(HttpServletRequest req,HttpServletResponse res,Object handler,Exception e){if(Boolean.TRUE.equals(req.getAttribute("marketMutationLock")))gate.leave();}
 }).addPathPatterns("/api/**");}
}
