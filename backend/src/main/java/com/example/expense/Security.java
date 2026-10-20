package com.example.expense;
import java.io.IOException;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
@Configuration @EnableMethodSecurity
public class Security {
 @Bean PasswordEncoder encoder(){return new BCryptPasswordEncoder(12);}
 @Bean UserDetailsService users(Db db){return email->{
  var rows=db.list("select * from app_user where email=?",email.trim().toLowerCase(Locale.ROOT));
  if(rows.isEmpty())throw new UsernameNotFoundException("Invalid credentials");var u=rows.get(0);
  var roles=db.list("select role from user_role where user_id=?",u.get("id")).stream().map(r->"ROLE_"+r.get("role")).toArray(String[]::new);
  return User.withUsername(u.get("email").toString()).password(u.get("passwordHash").toString()).authorities(roles).disabled(!"ACTIVE".equals(u.get("status"))).build();
 };}
 @Bean SecurityFilterChain chain(HttpSecurity http,Db db,PlatformTransactionManager tm) throws Exception {
  http.authorizeHttpRequests(a->a.requestMatchers("/api/v1/auth/csrf","/api/v1/auth/login","/actuator/health/**").permitAll().anyRequest().authenticated())
   .formLogin(f->f.loginProcessingUrl("/api/v1/auth/login").usernameParameter("email").successHandler((q,s,a)->{s.setContentType("application/json");s.getWriter().write("{\"ok\":true}");})
    .failureHandler((q,s,e)->{s.setStatus(401);s.setContentType("application/json");s.getWriter().write("{\"code\":\"INVALID_CREDENTIALS\"}");}))
   .logout(l->l.logoutUrl("/api/v1/auth/logout").logoutSuccessHandler((q,s,a)->s.setStatus(204)))
   .exceptionHandling(e->e.authenticationEntryPoint((q,s,x)->s.sendError(401)).accessDeniedHandler((q,s,x)->s.sendError(403)))
   .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'self'; frame-ancestors 'none'")))
   .addFilterBefore(new SessionAndRateFilter(db,tm),UsernamePasswordAuthenticationFilter.class);
  return http.build();
 }
 static class SessionAndRateFilter extends OncePerRequestFilter {
  final Db db;final TransactionTemplate tx;
  SessionAndRateFilter(Db db,PlatformTransactionManager tm){this.db=db;this.tx=new TransactionTemplate(tm);}
  protected void doFilterInternal(HttpServletRequest q,HttpServletResponse s,FilterChain c)throws ServletException,IOException{
   var session=q.getSession(false);
   if(session!=null && System.currentTimeMillis()-session.getCreationTime()>8*60*60*1000L){session.invalidate();s.sendError(401);return;}
   if(q.getMethod().equals("POST")&&q.getRequestURI().equals("/api/v1/auth/login")){
    String email=Optional.ofNullable(q.getParameter("email")).orElse("").trim().toLowerCase(Locale.ROOT);
    if(email.length()>254){s.sendError(400);return;}
    boolean allowed=Boolean.TRUE.equals(tx.execute(t->{
     // Both per identity and per remote address limits are shared by all replicas.
     var keys=new ArrayList<>(List.of("email:"+email,"ip:"+q.getRemoteAddr()));Collections.sort(keys);
     boolean ok=true;
     for(var key:keys){
      db.exec("insert into login_bucket values(?,now(),0) on conflict do nothing",key);
      var b=db.one("select * from login_bucket where key=? for update",key);
      var start=(java.time.Instant)b.get("windowStart");
      if(start.isBefore(java.time.Instant.now().minusSeconds(300)))db.exec("update login_bucket set window_start=now(),attempts=1 where key=?",key);
      else {db.exec("update login_bucket set attempts=attempts+1 where key=?",key);if(Db.number(b,"attempts")>=20)ok=false;}
     }return ok;
    }));
    if(!allowed){s.setHeader("Retry-After","300");s.sendError(429);return;}
   }
   if(q.getUserPrincipal()!=null && db.count("select count(*) from app_user where email=? and status='ACTIVE'",q.getUserPrincipal().getName())==0){if(session!=null)session.invalidate();s.sendError(401);return;}
   c.doFilter(q,s);
  }
 }
}
