package com.srikanth.authservice.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes the same seven tables the Python service (card-issuer-processor)
 * created. This service does not own the schema; it is a second implementation of
 * the authorization flow against the same system of record.
 */
@Repository
public class AuthorizationRepository {

    private final JdbcTemplate jdbc;

    public AuthorizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Card(String status, long accountId, java.time.LocalDate expiresOn) {}

    public Optional<Card> findCardByToken(String cardToken) {
        return jdbc.query(
                "SELECT status, account_id, expires_on FROM cards WHERE card_token = ?::uuid",
                rs -> rs.next()
                        ? Optional.of(new Card(rs.getString("status"), rs.getLong("account_id"), rs.getDate("expires_on").toLocalDate()))
                        : Optional.empty(),
                cardToken
        );
    }

    public void lockAccount(long accountId) {
        jdbc.queryForObject("SELECT account_id FROM accounts WHERE account_id = ? FOR UPDATE", Long.class, accountId);
    }

    public boolean isMccBlocked(String mcc) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM blocked_mccs WHERE mcc = ?", Integer.class, mcc);
        return count != null && count > 0;
    }

    public long countRecentAuthorizations(String cardToken, int windowSeconds) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM authorizations WHERE card_token = ?::uuid AND status <> 'declined' " +
                "AND approved_at > now() - make_interval(secs => ?::double precision)",
                Long.class, cardToken, (double) windowSeconds
        );
        return count == null ? 0 : count;
    }

    public long availableBalance(long accountId) {
        return jdbc.query(
                "SELECT available_balance_minor FROM account_available_balance WHERE account_id = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L,
                accountId
        );
    }

    public void insertAuthorization(String authId, String cardToken, long accountId, long amountMinor,
                                     String merchantId, String mcc, String status, String declineCode,
                                     String idempotencyKey, Instant expiresAt) {
        jdbc.update(
                "INSERT INTO authorizations (auth_id, card_token, account_id, amount_minor, merchant_id, " +
                "mcc, status, decline_code, idempotency_key, expires_at) " +
                "VALUES (?::uuid,?::uuid,?,?,?,?,?,?,?,?)",
                authId, cardToken, accountId, amountMinor, merchantId, mcc, status, declineCode,
                idempotencyKey, java.sql.Timestamp.from(expiresAt)
        );
    }

    public record StoredAuth(String authId, String status, String declineCode) {}

    public Optional<StoredAuth> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query(
                "SELECT auth_id, status, decline_code FROM authorizations WHERE idempotency_key = ?",
                rs -> rs.next()
                        ? Optional.of(new StoredAuth(rs.getString("auth_id"), rs.getString("status"), rs.getString("decline_code")))
                        : Optional.empty(),
                idempotencyKey
        );
    }
}
