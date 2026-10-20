package com.example.expense;
import org.springframework.stereotype.Component;import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
@Component @ConditionalOnProperty(name="app.worker-enabled",havingValue="true",matchIfMissing=true)
public class Worker {
 final Db db;private static final Logger log=LoggerFactory.getLogger(Worker.class);Worker(Db db){this.db=db;}
 @Scheduled(fixedDelay=2000) @Transactional public void deliver(){
  // In-app delivery is a local transactional insert, so no remote call or lease is needed.
  for(var e:db.list("select id,user_id,message from outbox_event where delivered_at is null order by created_at limit 100 for update skip locked")){
   db.exec("insert into notification(event_id,user_id,message) values(?,?,?) on conflict do nothing",e.get("id"),e.get("userId"),e.get("message"));
   db.exec("update outbox_event set delivered_at=now() where id=?",e.get("id"));
  }
 }
 @Scheduled(fixedDelay=60000) public void reconcile(){long n=db.count("select count(*) from (select a.id from account a left join ledger_entry e on e.account_id=a.id group by a.id having a.balance_minor<>coalesce(sum(e.amount_minor),0)) drift");if(n>0)log.error("LEDGER_RECONCILIATION_MISMATCH accounts={}",n);}
 @Scheduled(fixedDelay=3600000) public void cleanup(){db.exec("delete from login_bucket where window_start<now()-interval '1 day'");}
}
