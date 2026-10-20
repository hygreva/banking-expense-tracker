package com.example.expense;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service public class AccountService {
 final Db db;AccountService(Db db){this.db=db;}
 @Transactional public Map<String,Object> create(UUID user,String kind,String name){
  if(!List.of("CHECKING","SAVINGS").contains(kind))throw new Problem(400,"INVALID_KIND");
  UUID id=UUID.randomUUID();db.exec("insert into account(id,owner_id,display_name,kind,recipient_ref) values(?,?,?,?,?)",id,user,name,kind,UUID.randomUUID());db.audit(user,"ACCOUNT_CREATED",id);return own(user,id,false);
 }
 public Map<String,Object> own(UUID u,UUID id,boolean lock){return db.one("select id,owner_id,display_name,kind,currency,balance_minor,status,recipient_ref,version from account where id=? and owner_id=?"+(lock?" for update":""),id,u);}
 @Transactional public Map<String,Object> close(UUID u,UUID id){
  db.lockUser(u);var a=own(u,id,true);if(Db.number(a,"balanceMinor")!=0||db.count("select count(*) from transfer where (source_id=? or destination_id=?) and status='PENDING_REVIEW'",id,id)>0)throw new Problem(409,"ACCOUNT_NOT_EMPTY");
  db.exec("update account set status='CLOSED',version=version+1 where id=?",id);db.audit(u,"ACCOUNT_CLOSED",id);return own(u,id,false);
 }
 @Transactional public Map<String,Object> beneficiary(UUID u,UUID reference,String alias){
  var target=db.one("select id from account where recipient_ref=? and status='ACTIVE' and owner_id is not null",reference);
  UUID id=UUID.randomUUID();db.exec("insert into beneficiary(id,user_id,target_account_id,alias) values(?,?,?,?)",id,u,target.get("id"),alias);
  return db.one("select id,alias,created_at from beneficiary where id=?",id);
 }
}
