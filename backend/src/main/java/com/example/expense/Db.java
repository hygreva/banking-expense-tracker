package com.example.expense;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
@Component
public class Db {
 final JdbcTemplate jdbc;
 public Db(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public List<Map<String,Object>> list(String sql,Object... args){
  return jdbc.queryForList(sql,args).stream().map(row->{ Map<String,Object> out=new LinkedHashMap<>();row.forEach((k,v)->{
   StringBuilder c=new StringBuilder();boolean up=false;for(char x:k.toCharArray()){if(x=='_')up=true;else{c.append(up?Character.toUpperCase(x):x);up=false;}}
   out.put(c.toString(),v instanceof java.sql.Timestamp t?t.toInstant():v instanceof java.sql.Date d?d.toLocalDate():v);
  });return out;}).toList();
 }
 public Map<String,Object> one(String sql,Object... args){var r=list(sql,args);if(r.isEmpty())throw new Problem(404,"NOT_FOUND");return r.get(0);}
 public int exec(String sql,Object... args){return jdbc.update(sql,args);}
 public long count(String sql,Object... args){return jdbc.queryForObject(sql,Long.class,args);}
 public static UUID id(Map<String,Object> r,String key){return UUID.fromString(r.get(key).toString());}
 public static long number(Map<String,Object> r,String key){return ((Number)r.get(key)).longValue();}
 public void lockUser(UUID id){one("select user_id from fraud_subject where user_id=? for update",id);}
 public void audit(UUID actor,String action,UUID resource){exec("insert into audit_event values(?,?,?,?,now())",UUID.randomUUID(),actor,action,resource);}
 public void event(UUID user,String type,String message){exec("insert into outbox_event(id,user_id,type,message) values(?,?,?,?)",UUID.randomUUID(),user,type,message);}
}
