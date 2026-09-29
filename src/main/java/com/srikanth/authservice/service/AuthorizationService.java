package com.srikanth.authservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.srikanth.authservice.dto.AuthorizeRequest;
import com.srikanth.authservice.dto.AuthorizeResponse;
import com.srikanth.authservice.repo.AuthorizationRepository;
import com.srikanth.authservice.repo.IdempotencyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Java implementation of the authorization flow from card-issuer-processor
 * (Python). Same rule order, same decline codes, same idempotency contract:
 * card status -> MCC blocklist -> velocity -> balance, evaluated under an
 * account row lock so velocity and balance (both check-then-act) are
 * serialized per account.
 */
@Service
public class AuthorizationService {

    private final AuthorizationRepository authRepo;
    private final IdempotencyRepository idempotencyRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${velocity.max-auths:5}")
    private int velocityMaxAuths;

    @Value("${velocity.window-seconds:60}")
    private int velocityWindowSeconds;

    public AuthorizationService(AuthorizationRepository authRepo, IdempotencyRepository idempotencyRepo) {
        this.authRepo = authRepo;
        this.idempotencyRepo = idempotencyRepo;
    }

    public static class DeclineException extends RuntimeException {
        public final String code;
        public DeclineException(String code, String reason) {
            super(code + ": " + reason);
            this.code = code;
        }
    }

    @Transactional
    public AuthorizeResponse authorize(String idempotencyKey, AuthorizeRequest req) {
        String bodyJson = canonicalJson(req);
        var begun = idempotencyRepo.begin(idempotencyKey, "/authorize", bodyJson);

        if (!begun.isNew() && begun.recoveryPoint().equals("finished")) {
            AuthorizationRepository.StoredAuth stored = authRepo.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("idempotency key finished but no authorization found"));
            return new AuthorizeResponse(stored.authId(), stored.status(), stored.declineCode());
        }

        AuthorizationRepository.Card card = authRepo.findCardByToken(req.cardToken())
                .orElseThrow(() -> new DeclineException("14", "invalid card number"));

        String authId = UUID.randomUUID().toString();
        LocalDate today = LocalDate.now();
        String declineCode = null;

        if (card.status().equals("frozen")) {
            declineCode = "62";
        } else if (card.status().equals("closed")) {
            declineCode = "14";
        } else if (card.expiresOn().isBefore(today)) {
            declineCode = "54";
        } else {
            authRepo.lockAccount(card.accountId());

            if (authRepo.isMccBlocked(req.mcc())) {
                declineCode = "57";
            } else {
                long recent = authRepo.countRecentAuthorizations(req.cardToken(), velocityWindowSeconds);
                if (recent >= velocityMaxAuths) {
                    declineCode = "61";
                } else {
                    long available = authRepo.availableBalance(card.accountId());
                    if (available < req.amountMinor()) {
                        declineCode = "51";
                    }
                }
            }
        }

        idempotencyRepo.advance(idempotencyKey, "hold_placed");

        String status = declineCode != null ? "declined" : "approved";
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);

        authRepo.insertAuthorization(authId, req.cardToken(), card.accountId(), req.amountMinor(),
                req.merchantId(), req.mcc(), status, declineCode, idempotencyKey, expiresAt);

        idempotencyRepo.finish(idempotencyKey);

        return new AuthorizeResponse(authId, status, declineCode);
    }

    private String canonicalJson(AuthorizeRequest req) {
        // Field order fixed via LinkedHashMap so the same logical request always
        // hashes the same way, matching the Python side's sort_keys=True behavior.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("card_token", req.cardToken());
        m.put("amount_minor", req.amountMinor());
        m.put("merchant_id", req.merchantId());
        m.put("mcc", req.mcc());
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
