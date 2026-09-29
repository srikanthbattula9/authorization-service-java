package com.srikanth.authservice.web;

import com.srikanth.authservice.dto.AuthorizeRequest;
import com.srikanth.authservice.dto.AuthorizeResponse;
import com.srikanth.authservice.repo.IdempotencyRepository;
import com.srikanth.authservice.service.AuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthorizeController {

    private final AuthorizationService authorizationService;

    public AuthorizeController(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @PostMapping("/authorize")
    public ResponseEntity<?> authorize(
            @RequestBody AuthorizeRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        try {
            AuthorizeResponse response = authorizationService.authorize(idempotencyKey, request);
            return ResponseEntity.ok(response);
        } catch (AuthorizationService.DeclineException e) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(new AuthorizeResponse(null, "declined", e.code));
        } catch (IdempotencyRepository.RequestMismatch e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (IdempotencyRepository.RequestInProgress e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }
}
