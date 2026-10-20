package com.example.expense;
import java.util.*;
import org.springframework.stereotype.Service;
@Service public class Ledger {
 final Db db;Ledger(Db db){this.db=db;}
 // Caller must hold both account locks inside a database transaction.
 public void post(UUID transfer,UUID source,UUID destination,long amount,String type){
  UUID j=UUID.randomUUID();db.exec("insert into journal(id,transfer_id,type,currency) values(?,?,?,'USD')",j,transfer,type);
  db.exec("insert into ledger_entry(id,journal_id,account_id,amount_minor) values(?,?,?,?),(?,?,?,?)",UUID.randomUUID(),j,source,-amount,UUID.randomUUID(),j,destination,amount);
  db.exec("update account set balance_minor=balance_minor-?,version=version+1 where id=?",amount,source);
  db.exec("update account set balance_minor=balance_minor+?,version=version+1 where id=?",amount,destination);
 }
}
