package com.example.expense;
import java.util.*;import java.util.concurrent.*;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.MockMvc;import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class BankingIntegrationTest {
 static PostgreSQLContainer<?> postgres;
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){
  String external=System.getenv("TEST_DB_URL");
  if(external!=null){r.add("spring.datasource.url",()->external);r.add("spring.datasource.username",()->System.getenv().getOrDefault("TEST_DB_USER","postgres"));r.add("spring.datasource.password",()->System.getenv().getOrDefault("TEST_DB_PASSWORD",""));}
  else {postgres=new PostgreSQLContainer<>("postgres:16-alpine");postgres.start();r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);}
 }
 @Autowired Db db;@Autowired TransferService transfers;@Autowired AccountService accounts;@Autowired Ledger ledger;@Autowired ExpenseService expenses;@Autowired ReportingService reports;@Autowired PlatformTransactionManager tm;@Autowired MockMvc mvc;
 record Fixture(UUID user,UUID source,UUID destination,UUID beneficiary){}
 Fixture fixture(long balance){return new TransactionTemplate(tm).execute(t->{
  UUID u=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID(),system=UUID.randomUUID(),beneficiary=UUID.randomUUID();
  db.exec("insert into app_user(id,email,password_hash,display_name) values(?,?,'not-a-login-hash','Test')",u,u+"@test.local");db.exec("insert into fraud_subject values(?)",u);
  db.exec("insert into user_role values(?,'CUSTOMER')",u);
  db.exec("insert into account(id,owner_id,display_name,kind,recipient_ref) values(?,?,'Source','CHECKING',?),(?,?,'Destination','SAVINGS',?)",a,u,UUID.randomUUID(),b,u,UUID.randomUUID());
  db.exec("insert into account(id,display_name,kind,recipient_ref) values(?,'System','SYSTEM',?)",system,UUID.randomUUID());
  if(balance>0)ledger.post(null,system,a,balance,"DEMO_FUNDING");
  db.exec("insert into beneficiary(id,user_id,target_account_id,alias,created_at) values(?,?,?,'Other',now()-interval '2 days')",beneficiary,u,b);
  return new Fixture(u,a,b,beneficiary);
 });}
 TransferService.Request request(Fixture f,long value){return new TransferService.Request(f.source,f.beneficiary,value,"USD","Test");}
 long balance(UUID a){return db.count("select balance_minor from account where id=?",a);}
 @Test void postingBalancesAndIdempotency(){
  var f=fixture(10000);var key=UUID.randomUUID();var first=transfers.submit(f.user,key,request(f,2500));var retry=transfers.submit(f.user,key,request(f,2500));
  assertEquals(201,first.httpStatus());assertEquals(first.body(),retry.body());assertEquals(7500,balance(f.source));assertEquals(2500,balance(f.destination));assertEquals(1,db.count("select count(*) from transfer where initiator_id=?",f.user));
  var error=assertThrows(Problem.class,()->transfers.submit(f.user,key,request(f,2501)));assertEquals(409,error.status);
 }
 @Test void insufficientFundsDoesNotPost(){var f=fixture(100);var r=transfers.submit(f.user,UUID.randomUUID(),request(f,101));assertEquals(422,r.httpStatus());assertEquals("INSUFFICIENT_FUNDS",r.body().get("reason"));assertEquals(100,balance(f.source));assertEquals(0,balance(f.destination));}
 @Test void rollbackIncludesBothBalancesAndJournal(){
  var f=fixture(10000);long before=db.count("select count(*) from journal");
  assertThrows(IllegalStateException.class,()->new TransactionTemplate(tm).execute(t->{
   db.list("select id from account where id in (?,?) order by id for update",f.source,f.destination);ledger.post(null,f.source,f.destination,1000,"DEMO_FUNDING");throw new IllegalStateException("Injected after posting");
  }));
  assertEquals(10000,balance(f.source));assertEquals(0,balance(f.destination));assertEquals(before,db.count("select count(*) from journal"));
 }
 @Test void heldTransferPostsOnlyOnReview(){
  var f=fixture(1000000);var held=transfers.submit(f.user,UUID.randomUUID(),request(f,600000));assertEquals(202,held.httpStatus());assertEquals(1000000,balance(f.source));
  UUID caseId=Db.id(db.one("select id from fraud_case where transfer_id=?",UUID.fromString(held.body().get("id").toString())),"id");
  UUID reviewer=Db.id(db.one("select id from app_user where email='reviewer@demo.local'"),"id");
  var result=transfers.decide(reviewer,caseId,"APPROVE","Verified simulated request",0);assertEquals("POSTED",result.get("status"));assertEquals(400000,balance(f.source));assertThrows(Problem.class,()->transfers.decide(reviewer,caseId,"APPROVE","Duplicate",0));
 }
 @Test void reviewRechecksFunds(){
  var f=fixture(600000);var held=transfers.submit(f.user,UUID.randomUUID(),request(f,600000));transfers.submit(f.user,UUID.randomUUID(),request(f,100));
  UUID cid=Db.id(db.one("select id from fraud_case where transfer_id=?",UUID.fromString(held.body().get("id").toString())),"id");UUID reviewer=Db.id(db.one("select id from app_user where email='reviewer@demo.local'"),"id");
  assertEquals("INSUFFICIENT_FUNDS",transfers.decide(reviewer,cid,"APPROVE","Approved risk",0).get("reason"));assertEquals(599900,balance(f.source));
 }
 @Test void velocityIsPersisted(){var f=fixture(10000);for(int i=0;i<5;i++)assertEquals(201,transfers.submit(f.user,UUID.randomUUID(),request(f,1)).httpStatus());assertEquals("VELOCITY_LIMIT",transfers.submit(f.user,UUID.randomUUID(),request(f,1)).body().get("reason"));}
 @Test void ledgerDatabaseGuards(){
  assertThrows(DataIntegrityViolationException.class,()->db.exec("update ledger_entry set amount_minor=amount_minor+1"));
  assertThrows(RuntimeException.class,()->new TransactionTemplate(tm).execute(t->{db.exec("insert into journal(id,type,currency) values(?,'DEMO_FUNDING','USD')",UUID.randomUUID());return null;}));
 }
 @Test void reportsExcludeTransfersAndRespectObservationChanges(){
  var f=fixture(10000);transfers.submit(f.user,UUID.randomUUID(),request(f,100));
  var c=expenses.category(f.user,"Food","EXPENSE");UUID cid=Db.id(c,"id");var at=java.time.Instant.parse("2026-10-10T16:00:00Z");
  var item=expenses.save(f.user,null,new ExpenseService.Activity(cid,"EXPENSE",1234,at,"Lunch",null));
  var report=reports.summary(f.user,java.time.LocalDate.parse("2026-10-01"),java.time.LocalDate.parse("2026-11-01"));
  assertEquals(1234,Db.number((Map<String,Object>)report.get("totals"),"expenseMinor"));
  expenses.delete(f.user,Db.id(item,"id"),0);report=reports.summary(f.user,java.time.LocalDate.parse("2026-10-01"),java.time.LocalDate.parse("2026-11-01"));assertEquals(0,Db.number((Map<String,Object>)report.get("totals"),"expenseMinor"));
 }
 @Test void budgetAlertsDeduplicate(){
  var f=fixture(0);UUID c=Db.id(expenses.category(f.user,"Food","EXPENSE"),"id");expenses.budget(f.user,c,java.time.YearMonth.of(2026,10),1000,null);
  expenses.save(f.user,null,new ExpenseService.Activity(c,"EXPENSE",1000,java.time.Instant.parse("2026-10-10T16:00:00Z"),"Lunch",null));
  expenses.save(f.user,null,new ExpenseService.Activity(c,"EXPENSE",1000,java.time.Instant.parse("2026-10-11T16:00:00Z"),"Dinner",null));
  assertEquals(2,db.count("select count(*) from budget_alert a join budget b on b.id=a.budget_id where b.user_id=?",f.user));
 }
 @Test void authenticationCsrfAndOwnership()throws Exception {
  mvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/accounts").with(user("alice@demo.local").roles("CUSTOMER")).contentType("application/json").content("{\"displayName\":\"Test\",\"kind\":\"SAVINGS\"}")).andExpect(status().isForbidden());
  UUID bob=Db.id(db.one("select a.id from account a join app_user u on u.id=a.owner_id where u.email='bob@demo.local'"),"id");
  mvc.perform(get("/api/v1/accounts/"+bob+"/transactions").with(user("alice@demo.local").roles("CUSTOMER"))).andExpect(status().isNotFound());
  mvc.perform(get("/api/v1/fraud-cases").with(user("alice@demo.local").roles("CUSTOMER"))).andExpect(status().isForbidden());
 }
 @Test void realLoginAndSession()throws Exception {
  mvc.perform(post("/api/v1/auth/login").with(csrf()).param("email","alice@demo.local").param("password","Integration-Test-Only-2026")).andExpect(status().isOk()).andExpect(authenticated().withUsername("alice@demo.local"));
 }
 @Test void fractionalMoneyRejected()throws Exception {
  mvc.perform(post("/api/v1/transfers").with(user("alice@demo.local").roles("CUSTOMER")).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"sourceAccountId\":\""+UUID.randomUUID()+"\",\"beneficiaryId\":\""+UUID.randomUUID()+"\",\"amountMinor\":1.5,\"currency\":\"USD\",\"memo\":\"x\"}")).andExpect(status().isBadRequest());
 }
 @Test @Tag("concurrency") void concurrentDebitsNeverOverdraw()throws Exception {
  var f=fixture(10000);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
  try{Callable<TransferService.Result> call=()->{gate.await();return transfers.submit(f.user,UUID.randomUUID(),request(f,8000));};var a=pool.submit(call);var b=pool.submit(call);gate.countDown();var statuses=List.of(a.get(20,TimeUnit.SECONDS).httpStatus(),b.get(20,TimeUnit.SECONDS).httpStatus());assertTrue(statuses.contains(201));assertTrue(statuses.contains(422));assertEquals(2000,balance(f.source));assertEquals(8000,balance(f.destination));}finally{pool.shutdownNow();}
 }
 @Test @Tag("concurrency") void parallelRetryPostsOnce()throws Exception {
  var f=fixture(10000);UUID key=UUID.randomUUID();var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->transfers.submit(f.user,key,request(f,100)));var b=pool.submit(()->transfers.submit(f.user,key,request(f,100)));assertEquals(a.get(20,TimeUnit.SECONDS).body(),b.get(20,TimeUnit.SECONDS).body());assertEquals(9900,balance(f.source));}finally{pool.shutdownNow();}
 }
}
