package com.example.expense;
import java.util.*;import java.time.*;import java.security.Principal;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;import org.springframework.http.*;import org.springframework.security.access.prepost.PreAuthorize;
@RestController @RequestMapping("/api/v1") @PreAuthorize("hasRole('CUSTOMER')")
public class ExpensesController {
 final Db db;final Who who;final ExpenseService service;final ReportingService reports;
 ExpensesController(Db db,Who who,ExpenseService service,ReportingService reports){this.db=db;this.who=who;this.service=service;this.reports=reports;}
 record Category(@NotBlank @Size(max=60) String name,@NotBlank String kind){}
 record Budget(@Min(1) @Max(1000000000) long limitMinor,Long version){}
 @GetMapping("/categories") Object categories(Principal p){return db.list("select id,name,kind from category where owner_id=? order by kind,name",who.id(p));}
 @PostMapping("/categories") @ResponseStatus(HttpStatus.CREATED) Object category(Principal p,@Valid @RequestBody Category c){return service.category(who.id(p),c.name,c.kind);}
 @GetMapping("/manual-activities") Object activities(Principal p,@RequestParam LocalDate from,@RequestParam LocalDate to){var r=reports.activities(who.id(p),from,to);if(r.size()>10000)throw new Problem(422,"USE_SHORTER_PERIOD");return r;}
 @PostMapping("/manual-activities") @ResponseStatus(HttpStatus.CREATED) Object create(Principal p,@Valid @RequestBody ExpenseService.Activity a){return service.save(who.id(p),null,a);}
 @PatchMapping("/manual-activities/{id}") Object update(Principal p,@PathVariable UUID id,@Valid @RequestBody ExpenseService.Activity a){return service.save(who.id(p),id,a);}
 @DeleteMapping("/manual-activities/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void delete(Principal p,@PathVariable UUID id,@RequestHeader("If-Match") long version){service.delete(who.id(p),id,version);}
 @GetMapping("/budgets/{month}") Object budgets(Principal p,@PathVariable String month){return reports.budgets(who.id(p),YearMonth.parse(month));}
 @PutMapping("/budgets/{month}/{category}") Object budget(Principal p,@PathVariable String month,@PathVariable UUID category,@Valid @RequestBody Budget b){return service.budget(who.id(p),category,YearMonth.parse(month),b.limitMinor,b.version);}
 @GetMapping("/reports/summary") Object summary(Principal p,@RequestParam LocalDate from,@RequestParam LocalDate to){return reports.summary(who.id(p),from,to);}
 @GetMapping(value="/reports/export",produces="text/csv") ResponseEntity<String> export(Principal p,@RequestParam LocalDate from,@RequestParam LocalDate to){return ResponseEntity.ok().header("Content-Disposition","attachment; filename=expenses.csv").body(reports.csv(reports.activities(who.id(p),from,to)));}
 @GetMapping("/notifications") Object notifications(Principal p){return db.list("select message,created_at from notification where user_id=? order by created_at desc limit 50",who.id(p));}
}
