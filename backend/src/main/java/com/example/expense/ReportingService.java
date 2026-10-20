package com.example.expense;
import java.util.*;import java.time.*;
import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.*;
@Service public class ReportingService {
 final Db db;ReportingService(Db db){this.db=db;}
 record Period(Instant start,Instant end,ZoneId zone){}
 Period period(UUID user,LocalDate from,LocalDate to){
  if(from==null||to==null||!to.isAfter(from)||java.time.temporal.ChronoUnit.DAYS.between(from,to)>366)throw new Problem(400,"INVALID_PERIOD");
  ZoneId zone=ZoneId.of(db.one("select timezone from app_user where id=?",user).get("timezone").toString());return new Period(from.atStartOfDay(zone).toInstant(),to.atStartOfDay(zone).toInstant(),zone);
 }
 Object ts(Instant at){return java.sql.Timestamp.from(at);}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Map<String,Object> summary(UUID u,LocalDate from,LocalDate to){
  var p=period(u,from,to);
  var categories=db.list("select c.id as category_id,c.name as label,a.kind,sum(a.amount_minor)::bigint as amount_minor from manual_activity a join category c on c.id=a.category_id where a.user_id=? and a.deleted_at is null and a.occurred_at>=? and a.occurred_at<? group by c.id,c.name,a.kind order by c.name",u,ts(p.start),ts(p.end));
  var totals=db.one("select coalesce(sum(amount_minor) filter(where kind='INCOME'),0)::bigint as income_minor,coalesce(sum(amount_minor) filter(where kind='EXPENSE'),0)::bigint as expense_minor from manual_activity where user_id=? and deleted_at is null and occurred_at>=? and occurred_at<?",u,ts(p.start),ts(p.end));
  totals.put("netMinor",Db.number(totals,"incomeMinor")-Db.number(totals,"expenseMinor"));
  var rows=db.list("select to_char(occurred_at at time zone ?, 'YYYY-MM') as month,coalesce(sum(amount_minor) filter(where kind='INCOME'),0)::bigint as income_minor,coalesce(sum(amount_minor) filter(where kind='EXPENSE'),0)::bigint as expense_minor from manual_activity where user_id=? and deleted_at is null and occurred_at>=? and occurred_at<? group by 1 order by 1",p.zone.toString(),u,ts(p.start),ts(p.end));
  var flow=new ArrayList<Map<String,Object>>();
  for(YearMonth m=YearMonth.from(from);!m.isAfter(YearMonth.from(to.minusDays(1)));m=m.plusMonths(1)){
   String month=m.toString();var row=rows.stream().filter(r->month.equals(r.get("month"))).findFirst().orElseGet(()->new LinkedHashMap<>(Map.of("month",month,"incomeMinor",0L,"expenseMinor",0L)));
   row.put("netMinor",Db.number(row,"incomeMinor")-Db.number(row,"expenseMinor"));flow.add(row);
  }
  return Map.of("from",from,"to",to,"timezone",p.zone.toString(),"currency","USD","totals",totals,"spendingByCategory",categories.stream().filter(c->"EXPENSE".equals(c.get("kind"))).toList(),"cashFlowByMonth",flow,"generatedAt",Instant.now());
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public List<Map<String,Object>> activities(UUID u,LocalDate from,LocalDate to){
  var p=period(u,from,to);return db.list("select a.id,a.category_id,c.name as category_name,a.kind,a.amount_minor,a.currency,a.occurred_at,a.note,a.version from manual_activity a join category c on c.id=a.category_id where a.user_id=? and a.deleted_at is null and a.occurred_at>=? and a.occurred_at<? order by a.occurred_at desc,a.id desc limit 10001",u,ts(p.start),ts(p.end));
 }
 public List<Map<String,Object>> budgets(UUID u,YearMonth month){
  var p=period(u,month.atDay(1),month.plusMonths(1).atDay(1));
  return db.list("select b.id,b.category_id,c.name as category_name,b.limit_minor,b.version,coalesce(sum(a.amount_minor),0)::bigint as spent_minor from budget b join category c on c.id=b.category_id left join manual_activity a on a.category_id=b.category_id and a.user_id=b.user_id and a.deleted_at is null and a.occurred_at>=? and a.occurred_at<? where b.user_id=? and b.month_start=? group by b.id,c.name order by c.name",ts(p.start),ts(p.end),u,java.sql.Date.valueOf(month.atDay(1)));
 }
 public String csv(List<Map<String,Object>> rows){
  if(rows.size()>10000)throw new Problem(422,"EXPORT_LIMIT_USE_SHORTER_PERIOD");StringBuilder out=new StringBuilder("id,kind,category,amountMinor,currency,occurredAt,note\r\n");
  for(var r:rows){for(String key:List.of("id","kind","categoryName","amountMinor","currency","occurredAt","note")){
   String v=Objects.toString(r.get(key),"");if(v.matches("^[\\s]*[=+@-].*"))v="'"+v;out.append('"').append(v.replace("\"","\"\"")).append('"');if(!key.equals("note"))out.append(',');
  }out.append("\r\n");}return out.toString();
 }
}
