package com.example.expense;
import java.util.*;import java.security.Principal;
import org.springframework.web.bind.annotation.*;import org.springframework.http.ResponseEntity;import org.springframework.security.access.prepost.PreAuthorize;
@RestController @RequestMapping("/api/v1")
public class TransfersController {
 final Db db;final Who who;final TransferService service;
 TransfersController(Db db,Who who,TransferService service){this.db=db;this.who=who;this.service=service;}
 @PostMapping("/transfers") @PreAuthorize("hasRole('CUSTOMER')") ResponseEntity<?> submit(Principal p,@RequestHeader("Idempotency-Key") UUID key,@RequestBody TransferService.Request r){var result=service.submit(who.id(p),key,r);return ResponseEntity.status(result.httpStatus()).body(result.body());}
 @GetMapping("/transfers/{id}") @PreAuthorize("hasRole('CUSTOMER')") Object get(Principal p,@PathVariable UUID id){return db.one("select id,status,reason,amount_minor,currency,created_at,posted_at from transfer where id=? and initiator_id=?",id,who.id(p));}
 @GetMapping("/fraud-cases") @PreAuthorize("hasRole('FRAUD_REVIEWER')") Object cases(Principal p){return db.list("select c.id,c.version,c.decision,t.id as transfer_id,t.amount_minor,t.reason,t.created_at from fraud_case c join transfer t on t.id=c.transfer_id where c.decision='PENDING' and t.initiator_id<>? order by t.created_at limit 100",who.id(p));}
 record Decision(String decision,String reason,long version){}
 @PostMapping("/fraud-cases/{id}/decision") @PreAuthorize("hasRole('FRAUD_REVIEWER')") Object decide(Principal p,@PathVariable UUID id,@RequestBody Decision d){return service.decide(who.id(p),id,d.decision,d.reason,d.version);}
}
