package com.example.expense;
import java.util.*;
import java.security.Principal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
 final Db db;final Who who;AuthController(Db db,Who who){this.db=db;this.who=who;}
 @GetMapping("/csrf") Map<String,String> csrf(CsrfToken t){return Map.of("token",t.getToken(),"headerName",t.getHeaderName());}
 @GetMapping("/me") Map<String,Object> me(Principal p){var id=who.id(p);var u=db.one("select id,email,display_name,timezone from app_user where id=?",id);u.put("roles",db.list("select role from user_role where user_id=?",id).stream().map(r->r.get("role")).toList());return u;}
}
