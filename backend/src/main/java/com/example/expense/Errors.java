package com.example.expense;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.dao.*;
@RestControllerAdvice
public class Errors {
 @ExceptionHandler(Problem.class) ResponseEntity<?> problem(Problem e){return ResponseEntity.status(e.status).body(Map.of("code",e.code,"status",e.status));}
 @ExceptionHandler({MethodArgumentNotValidException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,IllegalArgumentException.class})
 ResponseEntity<?> invalid(Exception e){return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","status",400));}
 @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> conflict(Exception e){return ResponseEntity.status(409).body(Map.of("code","CONSTRAINT_CONFLICT","status",409));}
 @ExceptionHandler(CannotAcquireLockException.class) ResponseEntity<?> locked(Exception e){return ResponseEntity.status(503).header("Retry-After","1").body(Map.of("code","RETRY_SAME_KEY","status",503));}
}
