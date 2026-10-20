package com.example.expense;
import java.util.*;import java.time.*;
import org.springframework.stereotype.Component;import org.springframework.boot.CommandLineRunner;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;import org.springframework.beans.factory.annotation.Value;import org.springframework.transaction.annotation.Transactional;
@Component @ConditionalOnProperty(name="app.demo-enabled",havingValue="true")
public class DemoSeed implements CommandLineRunner {
 final Db db;final Ledger ledger;final PasswordEncoder encoder;final String password;
 DemoSeed(Db db,Ledger ledger,PasswordEncoder encoder,@Value("${app.demo-password}") String password){this.db=db;this.ledger=ledger;this.encoder=encoder;this.password=password;}
 @Override @Transactional public void run(String... args){
  db.jdbc.execute("select pg_advisory_xact_lock(8798345)");if(db.count("select count(*) from app_user")>0)return;
  if(password.length()<12)throw new IllegalArgumentException("DEMO_PASSWORD must have at least 12 characters");
  UUID system=UUID.randomUUID();db.exec("insert into account(id,display_name,kind,recipient_ref) values(?,'Demo funding','SYSTEM',?)",system,UUID.randomUUID());
  for(String name:List.of("alice","bob","reviewer")){
   UUID user=UUID.randomUUID();db.exec("insert into app_user(id,email,password_hash,display_name) values(?,?,?,?)",user,name+"@demo.local",encoder.encode(password),name.substring(0,1).toUpperCase()+name.substring(1));
   db.exec("insert into user_role values(?,?)",user,name.equals("reviewer")?"FRAUD_REVIEWER":"CUSTOMER");db.exec("insert into fraud_subject values(?)",user);
   if(name.equals("reviewer"))continue;
   UUID account=UUID.randomUUID();db.exec("insert into account(id,owner_id,display_name,kind,recipient_ref) values(?,?,'Everyday checking','CHECKING',?)",account,user,UUID.nameUUIDFromBytes((name+"-recipient").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
   ledger.post(null,system,account,2500000,"DEMO_FUNDING");
   for(String category:List.of("Groceries","Housing","Transport","Salary")){
    UUID cid=UUID.randomUUID();String kind=category.equals("Salary")?"INCOME":"EXPENSE";db.exec("insert into category values(?,?,?,?)",cid,user,category,kind);
    for(int month=0;month<3;month++){
     var at=LocalDate.now(ZoneId.of("America/New_York")).withDayOfMonth(1).minusMonths(month).atStartOfDay(ZoneId.of("America/New_York")).toInstant();
     db.exec("insert into manual_activity(id,user_id,category_id,kind,amount_minor,occurred_at,note) values(?,?,?,?,?,?,?)",UUID.randomUUID(),user,cid,kind,category.equals("Salary")?500000:category.equals("Housing")?160000:18000+month*2500,java.sql.Timestamp.from(at),"Demo observation");
    }
    if(!category.equals("Salary"))db.exec("insert into budget(id,user_id,category_id,month_start,limit_minor) values(?,?,?,?,?)",UUID.randomUUID(),user,cid,java.sql.Date.valueOf(LocalDate.now(ZoneId.of("America/New_York")).withDayOfMonth(1)),category.equals("Housing")?180000:40000);
   }
  }
  UUID alice=Db.id(db.one("select id from app_user where email='alice@demo.local'"),"id");UUID bobAccount=Db.id(db.one("select a.id from account a join app_user u on u.id=a.owner_id where u.email='bob@demo.local'"),"id");
  db.exec("insert into beneficiary(id,user_id,target_account_id,alias,created_at) values(?,?,?,'Bob',now()-interval '2 days')",UUID.randomUUID(),alice,bobAccount);
 }
}
