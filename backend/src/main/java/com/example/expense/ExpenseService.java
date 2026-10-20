package com.example.expense;
import java.util.*;import java.time.*;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
@Service public class ExpenseService {
 final Db db;final ReportingService reports;
 ExpenseService(Db db,ReportingService reports){this.db=db;this.reports=reports;}
 public record Activity(@NotNull UUID categoryId,@NotBlank String kind,@Min(1) @Max(1000000000) long amountMinor,@NotNull Instant occurredAt,@NotNull @Size(max=140) String note,Long version){}
 @Transactional public Map<String,Object> category(UUID user,String name,String kind){
  if(!List.of("EXPENSE","INCOME").contains(kind))throw new Problem(400,"INVALID_KIND");UUID id=UUID.randomUUID();db.exec("insert into category values(?,?,?,?)",id,user,name,kind);return db.one("select * from category where id=?",id);
 }
 @Transactional public Map<String,Object> save(UUID user,UUID id,Activity a){
  if(!List.of("EXPENSE","INCOME").contains(a.kind))throw new Problem(400,"INVALID_KIND");db.lockUser(user);
  db.one("select id from category where id=? and owner_id=? and kind=?",a.categoryId,user,a.kind);
  if(id==null){id=UUID.randomUUID();db.exec("insert into manual_activity(id,user_id,category_id,kind,amount_minor,occurred_at,note) values(?,?,?,?,?,?,?)",id,user,a.categoryId,a.kind,a.amountMinor,java.sql.Timestamp.from(a.occurredAt),a.note);}
  else {var old=db.one("select id,version from manual_activity where id=? and user_id=? and deleted_at is null for update",id,user);if(a.version==null||a.version!=Db.number(old,"version"))throw new Problem(409,"STALE_ACTIVITY");db.exec("update manual_activity set category_id=?,kind=?,amount_minor=?,occurred_at=?,note=?,version=version+1 where id=?",a.categoryId,a.kind,a.amountMinor,java.sql.Timestamp.from(a.occurredAt),a.note,id);}
  db.audit(user,"ACTIVITY_SAVED",id);checkBudgets(user);return db.one("select id,version from manual_activity where id=?",id);
 }
 @Transactional public void delete(UUID u,UUID id,long version){db.lockUser(u);var a=db.one("select version from manual_activity where id=? and user_id=? and deleted_at is null for update",id,u);if(Db.number(a,"version")!=version)throw new Problem(409,"STALE_ACTIVITY");db.exec("update manual_activity set deleted_at=now(),version=version+1 where id=?",id);db.audit(u,"ACTIVITY_DELETED",id);}
 @Transactional public Map<String,Object> budget(UUID u,UUID category,YearMonth month,long limit,Long version){
  if(limit<1||limit>1000000000)throw new Problem(400,"INVALID_LIMIT");db.lockUser(u);db.one("select id from category where id=? and owner_id=? and kind='EXPENSE'",category,u);
  var old=db.list("select id,version from budget where user_id=? and category_id=? and month_start=? for update",u,category,java.sql.Date.valueOf(month.atDay(1)));UUID id;
  if(old.isEmpty()){if(version!=null)throw new Problem(409,"STALE_BUDGET");id=UUID.randomUUID();db.exec("insert into budget(id,user_id,category_id,month_start,limit_minor) values(?,?,?,?,?)",id,u,category,java.sql.Date.valueOf(month.atDay(1)),limit);}
  else {id=Db.id(old.get(0),"id");if(version==null||version!=Db.number(old.get(0),"version"))throw new Problem(409,"STALE_BUDGET");db.exec("update budget set limit_minor=?,version=version+1 where id=?",limit,id);}
  db.audit(u,"BUDGET_SAVED",id);checkBudgets(u);return db.one("select id,version from budget where id=?",id);
 }
 void checkBudgets(UUID u){
  for(var month:db.list("select distinct month_start from budget where user_id=?",u)){
   for(var b:reports.budgets(u,YearMonth.from((LocalDate)month.get("monthStart")))){
    for(int threshold:List.of(80,100))if(Db.number(b,"spentMinor")*100>=Db.number(b,"limitMinor")*threshold){
     if(db.exec("insert into budget_alert(budget_id,threshold) values(?,?) on conflict do nothing",b.get("id"),threshold)>0)db.event(u,"BUDGET","Budget "+b.get("categoryName")+" reached "+threshold+" percent");
    }
   }
  }
 }
}
