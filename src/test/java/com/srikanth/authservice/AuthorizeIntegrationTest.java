package com.srikanth.authservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real integration test: a throwaway Postgres (schema applied from the
 * Python service's own db/001_schema.sql + db/002_nullable_invalid_card_auth.sql,
 * the same files its docker-compose.yml mounts) and a throwaway Kafka broker,
 * driven through a real HTTP request against the full Spring context.
 *
 * Uses the JDK's own HttpClient instead of TestRestTemplate/WebTestClient,
 * since neither reliably resolves on this Spring Boot 4.1.1 classpath --
 * @Value("${local.server.port}") is the oldest, most version-stable way to
 * get the random port.
 *
 * Replaces the test removed pending the Testcontainers/Docker Desktop 4.80
 * environment-detection issue (see README) -- fixed by bumping
 * testcontainers.version to 1.21.4 (see DockerSmokeTest).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthorizeIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../card-issuer-processor/db/001_schema.sql"),
                    "/docker-entrypoint-initdb.d/001_schema.sql")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../card-issuer-processor/db/002_nullable_invalid_card_auth.sql"),
                    "/docker-entrypoint-initdb.d/002_nullable_invalid_card_auth.sql");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @DynamicPropertySource
    static void kafkaProps(DynamicPropertyRegistry registry) {
        registry.add("kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("kafka.topic", () -> "card.transactions");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper mapper = new ObjectMapper();

    String cardToken;

    @BeforeEach
    void seedFundedCard() throws Exception {
        long accountId = jdbc.queryForObject(
                "INSERT INTO accounts(account_type) VALUES ('customer') RETURNING account_id", Long.class);
        long suspenseId = jdbc.queryForObject(
                "INSERT INTO accounts(account_type) VALUES ('suspense') RETURNING account_id", Long.class);
        long entryId = jdbc.queryForObject(
                "INSERT INTO journal_entries(entry_type, transaction_id, idempotency_key) " +
                        "VALUES ('adjustment', gen_random_uuid(), ?) RETURNING entry_id",
                Long.class, "seed-" + UUID.randomUUID());

        // journal_entry_balanced is a DEFERRABLE INITIALLY DEFERRED constraint
        // trigger -- it's checked at COMMIT, not after each row. JdbcTemplate's
        // individual update() calls each auto-commit on their own connection, so
        // the first line alone would commit and fail the balance check before
        // the second line exists. One manual transaction over both lines (same
        // pattern the Python seed script uses with its own psycopg connection)
        // makes both lines exist before the deferred check runs.
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (var ps = conn.prepareStatement(
                    "INSERT INTO journal_lines(entry_id, account_id, amount_minor) VALUES (?,?,?)")) {
                ps.setLong(1, entryId);
                ps.setLong(2, suspenseId);
                ps.setLong(3, -500000);
                ps.executeUpdate();
                ps.setLong(1, entryId);
                ps.setLong(2, accountId);
                ps.setLong(3, 500000);
                ps.executeUpdate();
            }
            conn.commit();
        }

        cardToken = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO card_vault(card_token, pan_encrypted, pan_last4) VALUES (?::uuid, ?, ?)",
                cardToken, "fake".getBytes(), "4242");
        jdbc.update("INSERT INTO cards(card_token, account_id, card_type, status, expires_on) " +
                        "VALUES (?::uuid,?,?,?,?)",
                cardToken, accountId, "virtual", "active", LocalDate.of(2027, 1, 1));
    }

    @Test
    void approves_a_funded_card() throws Exception {
        var response = authorize(cardToken, 1000, "it-approve-1");
        assertThat(response.statusCode()).isEqualTo(200);
        Map<?, ?> body = mapper.readValue(response.body(), Map.class);
        assertThat(body.get("status")).isEqualTo("approved");
    }

    @Test
    void retrying_same_key_and_body_returns_the_identical_result() throws Exception {
        var first = authorize(cardToken, 1000, "it-retry-1");
        var second = authorize(cardToken, 1000, "it-retry-1");
        Map<?, ?> firstBody = mapper.readValue(first.body(), Map.class);
        Map<?, ?> secondBody = mapper.readValue(second.body(), Map.class);
        assertThat(secondBody.get("auth_id")).isEqualTo(firstBody.get("auth_id"));
    }

    @Test
    void retrying_same_key_with_a_different_body_is_rejected() throws Exception {
        authorize(cardToken, 1000, "it-conflict-1");
        var second = authorize(cardToken, 2000, "it-conflict-1");
        assertThat(second.statusCode()).isEqualTo(409);
    }

    @Test
    void unknown_card_token_declines_with_14_and_an_audit_row() throws Exception {
        String fakeToken = UUID.randomUUID().toString();
        var response = authorize(fakeToken, 1000, "it-unknown-1");

        assertThat(response.statusCode()).isEqualTo(200);
        Map<?, ?> body = mapper.readValue(response.body(), Map.class);
        assertThat(body.get("status")).isEqualTo("declined");
        assertThat(body.get("declineCode")).isEqualTo("14");

        String attempted = jdbc.queryForObject(
                "SELECT attempted_card_token FROM authorizations WHERE idempotency_key = ?",
                String.class, "it-unknown-1");
        assertThat(attempted).isEqualTo(fakeToken);
    }

    private HttpResponse<String> authorize(String token, long amountMinor, String idempotencyKey) throws Exception {
        Map<String, Object> body = Map.of(
                "cardToken", token, "amountMinor", amountMinor, "merchantId", "demo_merchant", "mcc", "5812");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/authorize"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
