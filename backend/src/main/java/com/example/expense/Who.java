package com.example.expense;
import java.util.*;
import java.security.Principal;
import org.springframework.stereotype.Component;
@Component public class Who {
 final Db db;Who(Db db){this.db=db;}
 UUID id(Principal p){if(p==null)throw new Problem(401,"UNAUTHENTICATED");return Db.id(db.one("select id from app_user where email=? and status='ACTIVE'",p.getName()),"id");}
}
