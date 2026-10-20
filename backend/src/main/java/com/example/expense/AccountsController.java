package com.example.expense;
import java.util.*;import java.security.Principal;import java.time.Instant;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;import org.springframework.security.access.prepost.PreAuthorize;
@RestController @RequestMapping("/api/v1") @PreAuthorize("hasRole('CUSTOMER')")
public class AccountsController {
 final Db db;final Who who;final AccountService service;
 AccountsController(Db db,Who who,AccountService service){this.db=db;this.who=who;this.service=service;}
 record Create(@NotBlank @Size(max=60) String displayName,@NotBlank String kind){}
 record Beneficiary(@NotNull UUID recipientRef,@NotBlank @Size(max=60) String alias){}
 @GetMapping("/accounts") Object list(Principal p){return db.list("select id,display_name,kind,currency,balance_minor,status,recipient_ref,version from account where owner_id=? order by created_at,id",who.id(p));}
 @PostMapping("/accounts") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) Object create(Principal p,@Valid @RequestBody Create r){return service.create(who.id(p),r.kind,r.displayName);}
 @PostMapping("/accounts/{id}/close") Object close(Principal p,@PathVariable UUID id){return service.close(who.id(p),id);}
 @GetMapping("/beneficiaries") Object beneficiaries(Principal p){return db.list("select b.id,b.alias,b.created_at from beneficiary b where user_id=? order by alias",who.id(p));}
 @PostMapping("/beneficiaries") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) Object add(Principal p,@Valid @RequestBody Beneficiary r){return service.beneficiary(who.id(p),r.recipientRef,r.alias);}
 @GetMapping("/accounts/{id}/transactions") Object history(Principal p,@PathVariable UUID id,@RequestParam(defaultValue="25") @Min(1) @Max(100) int limit,@RequestParam(required=false) String cursor){
  service.own(who.id(p),id,false);Instant at=Instant.parse("9999-01-01T00:00:00Z");UUID last=new UUID(-1,-1);
  if(cursor!=null){try{var parts=new String(Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).split("\\|",-1);if(parts.length!=3||!parts[0].equals(id.toString()))throw new IllegalArgumentException();at=Instant.parse(parts[1]);last=UUID.fromString(parts[2]);}catch(Exception e){throw new Problem(400,"INVALID_CURSOR");}}
  var rows=db.list("select e.id,e.amount_minor,e.created_at,j.type,t.memo,t.id as transfer_id from ledger_entry e join journal j on j.id=e.journal_id left join transfer t on t.id=j.transfer_id where e.account_id=? and (e.created_at,e.id)<(?,?) order by e.created_at desc,e.id desc limit ?",id,java.sql.Timestamp.from(at),last,limit+1);
  String next="";if(rows.size()>limit){rows=new ArrayList<>(rows.subList(0,limit));var end=rows.get(rows.size()-1);next=Base64.getUrlEncoder().withoutPadding().encodeToString((id+"|"+end.get("createdAt")+"|"+end.get("id")).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  return Map.of("items",rows,"nextCursor",next);
 }
}
