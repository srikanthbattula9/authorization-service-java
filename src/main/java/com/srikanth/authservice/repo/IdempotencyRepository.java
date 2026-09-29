package com.srikanth.authservice.repo;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.security.MessageDigest;
import java.util.Optional;

/**
 * Recovery-point idempotency, ported from app/idempotency.py in the Python
 * service. A retry with the same key and the same request body resumes from
 * the last recorded phase instead of restarting. A retry with the same key
 * and a DIFFERENT body is rejected (RequestMismatch). A concurrent retry
 * while the key is locked is rejected (RequestInProgress).
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static class RequestMismatch extends RuntimeException {
        public RequestMismatch(String message) { super(message); }
    }

    public static class RequestInProgress extends RuntimeException {
        public RequestInProgress(String message) { super(message); }
    }

    public record Begun(boolean isNew, String recoveryPoint) {}

    private static String hash(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(body.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Call at the start of a request, inside the same transaction that will do the work. */
    public Begun begin(String idempotencyKey, String path, String requestBodyJson) {
        String requestHash = hash(requestBodyJson);

        var row = jdbc.query(
                "SELECT request_hash, recovery_point, locked_at FROM idempotency_keys " +
                "WHERE idempotency_key = ? FOR UPDATE",
                rs -> rs.next()
                        ? Optional.of(new Object[]{rs.getString(1), rs.getString(2), rs.getTimestamp(3)})
                        : Optional.<Object[]>empty(),
                idempotencyKey
        );

        if (row.isEmpty()) {
            jdbc.update(
                    "INSERT INTO idempotency_keys (idempotency_key, request_path, request_hash, " +
                    "recovery_point, locked_at) VALUES (?, ?, ?, 'started', now())",
                    idempotencyKey, path, requestHash
            );
            return new Begun(true, "started");
        }

        Object[] r = row.get();
        String storedHash = (String) r[0];
        String recoveryPoint = (String) r[1];
        java.sql.Timestamp lockedAt = (java.sql.Timestamp) r[2];

        if (!storedHash.equals(requestHash)) {
            throw new RequestMismatch("idempotency key " + idempotencyKey + " reused with a different request body");
        }
        if (lockedAt != null && !recoveryPoint.equals("finished")) {
            throw new RequestInProgress("idempotency key " + idempotencyKey + " is already being processed");
        }

        jdbc.update(
                "UPDATE idempotency_keys SET locked_at = now(), last_run_at = now() WHERE idempotency_key = ?",
                idempotencyKey
        );
        return new Begun(false, recoveryPoint);
    }

    public void advance(String idempotencyKey, String recoveryPoint) {
        jdbc.update(
                "UPDATE idempotency_keys SET recovery_point = ? WHERE idempotency_key = ?",
                recoveryPoint, idempotencyKey
        );
    }

    public void finish(String idempotencyKey) {
        jdbc.update(
                "UPDATE idempotency_keys SET recovery_point = 'finished', locked_at = NULL WHERE idempotency_key = ?",
                idempotencyKey
        );
    }
}
