package com.example.expense;
import java.util.*;import java.time.*;import java.security.MessageDigest;import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
@Service public class TransferService {
 final Db db;final Ledger ledger;final ObjectMapper json;
 TransferService(Db db,Ledger ledger,ObjectMapper json){this.db=db;this.ledger=ledger;this.json=json;}
 public record Request(UUID sourceAccountId,UUID beneficiaryId,long amountMinor,String currency,String memo){}
 public record Result(int httpStatus,Map<String,Object> body){}
 private String hash(Request r){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(r)));}catch(Exception e){throw new IllegalStateException(e);}}
 @SuppressWarnings("unchecked")
 @Transactional(rollbackFor=Exception.class) public Result submit(UUID user,UUID key,Request r){
  if(r.sourceAccountId==null||r.beneficiaryId==null||r.amountMinor<1||r.amountMinor>1000000||!"USD".equals(r.currency)||r.memo==null||r.memo.length()>140)throw new Problem(400,"INVALID_TRANSFER");
  db.lockUser(user);String hash=hash(r);var old=db.list("select * from idempotency_record where user_id=? and key=?",user,key);
  if(!old.isEmpty()){
   if(!hash.equals(old.get(0).get("requestHash")))throw new Problem(409,"IDEMPOTENCY_MISMATCH");
   try{return new Result((int)Db.number(old.get(0),"httpStatus"),json.readValue(old.get(0).get("responseJson").toString(),Map.class));}catch(Exception e){throw new IllegalStateException(e);}
  }
  var b=db.one("select * from beneficiary where id=? and user_id=?",r.beneficiaryId,user);UUID target=Db.id(b,"targetAccountId");
  if(target.equals(r.sourceAccountId))throw new Problem(400,"SAME_ACCOUNT");
  var accounts=lockAccounts(r.sourceAccountId,target);var source=accounts.get(r.sourceAccountId);var dest=accounts.get(target);
  if(!user.equals(source.get("ownerId")))throw new Problem(404,"NOT_FOUND");
  if(!"ACTIVE".equals(source.get("status"))||!"ACTIVE".equals(dest.get("status")))throw new Problem(409,"ACCOUNT_INACTIVE");
  var rule=db.one("select * from fraud_rule_set order by version desc limit 1");
  long attempts=db.count("select count(*) from transfer where initiator_id=? and created_at>now()-interval '10 minutes'",user);
  long daily=db.count("select coalesce(sum(amount_minor),0) from transfer where initiator_id=? and status in ('POSTED','PENDING_REVIEW') and created_at>now()-interval '24 hours'",user);
  String status="POSTED",reason="ALLOWED";
  if(attempts>=Db.number(rule,"velocityCount")){status="REJECTED";reason="VELOCITY_LIMIT";}
  else if(Db.number(source,"balanceMinor")<r.amountMinor){status="REJECTED";reason="INSUFFICIENT_FUNDS";}
  else if(r.amountMinor>Db.number(rule,"amountReview")){status="PENDING_REVIEW";reason="AMOUNT_REVIEW";}
  else if(daily+r.amountMinor>Db.number(rule,"dailyReview")){status="PENDING_REVIEW";reason="DAILY_REVIEW";}
  else if(((Instant)b.get("createdAt")).isAfter(Instant.now().minusSeconds(86400))&&r.amountMinor>Db.number(rule,"newBeneficiaryReview")){status="PENDING_REVIEW";reason="NEW_BENEFICIARY";}
  UUID id=UUID.randomUUID();
  db.exec("insert into transfer(id,initiator_id,source_id,destination_id,amount_minor,currency,memo,status,reason,rule_version,posted_at) values(?,?,?,?,?,'USD',?,?,?,?,case when ?='POSTED' then now() else null end)",id,user,r.sourceAccountId,target,r.amountMinor,r.memo,status,reason,rule.get("version"),status);
  if(status.equals("POSTED"))ledger.post(id,r.sourceAccountId,target,r.amountMinor,"INTERNAL_TRANSFER");
  if(status.equals("PENDING_REVIEW"))db.exec("insert into fraud_case(id,transfer_id) values(?,?)",UUID.randomUUID(),id);
  db.audit(user,"TRANSFER_"+status,id);db.event(user,"TRANSFER","Transfer "+id+" is "+status);
  if(status.equals("POSTED"))db.event(Db.id(dest,"ownerId"),"TRANSFER","An internal transfer was received");
  var response=new LinkedHashMap<String,Object>();response.put("id",id.toString());response.put("status",status);response.put("reason",reason);response.put("amountMinor",r.amountMinor);response.put("currency","USD");
  int code=status.equals("POSTED")?201:status.equals("PENDING_REVIEW")?202:422;
  try{db.exec("insert into idempotency_record(user_id,key,request_hash,http_status,response_json) values(?,?,?,?,?::jsonb)",user,key,hash,code,json.writeValueAsString(response));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
  return new Result(code,response);
 }
 Map<UUID,Map<String,Object>> lockAccounts(UUID a,UUID b){var rows=db.list("select * from account where id in (?,?) order by id for update",a,b);if(rows.size()!=2)throw new Problem(404,"NOT_FOUND");Map<UUID,Map<String,Object>> result=new HashMap<>();rows.forEach(r->result.put(Db.id(r,"id"),r));return result;}
 @Transactional public Map<String,Object> decide(UUID reviewer,UUID caseId,String decision,String reason,long version){
  if(decision==null||!List.of("APPROVE","REJECT").contains(decision)||reason==null||reason.isBlank()||reason.length()>500)throw new Problem(400,"INVALID_DECISION");
  var c=db.one("select * from fraud_case where id=? for update",caseId);var t=db.one("select * from transfer where id=? for update",c.get("transferId"));
  if(reviewer.equals(t.get("initiatorId")))throw new Problem(403,"SELF_REVIEW");
  if(Db.number(c,"version")!=version||!"PENDING".equals(c.get("decision")))throw new Problem(409,"STALE_CASE");
  db.lockUser(Db.id(t,"initiatorId"));UUID source=Db.id(t,"sourceId"),dest=Db.id(t,"destinationId");var accounts=lockAccounts(source,dest);
  String status="REJECTED",outcome="REVIEW_REJECTED";
  if(decision.equals("APPROVE")){
   if(!"ACTIVE".equals(accounts.get(source).get("status"))||!"ACTIVE".equals(accounts.get(dest).get("status")))outcome="ACCOUNT_INACTIVE";
   else if(Db.number(accounts.get(source),"balanceMinor")<Db.number(t,"amountMinor"))outcome="INSUFFICIENT_FUNDS";
   else {status="POSTED";outcome="REVIEW_APPROVED";ledger.post(Db.id(t,"id"),source,dest,Db.number(t,"amountMinor"),"INTERNAL_TRANSFER");}
  }
  db.exec("update transfer set status=?,reason=?,posted_at=case when ?='POSTED' then now() else null end where id=?",status,outcome,status,t.get("id"));
  db.exec("update fraud_case set decision=?,reviewer_id=?,reviewer_reason=?,version=version+1,reviewed_at=now() where id=?",decision,reviewer,reason,caseId);
  db.audit(reviewer,outcome,caseId);db.event(Db.id(t,"initiatorId"),"REVIEW","Reviewed transfer is "+status);
  if(status.equals("POSTED"))db.event(Db.id(accounts.get(dest),"ownerId"),"TRANSFER","An internal transfer was received");
  return db.one("select id,status,reason from transfer where id=?",t.get("id"));
 }
}
