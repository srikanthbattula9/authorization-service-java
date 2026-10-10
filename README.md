# authorization-service-java

Java/Spring Boot implementation of the authorization flow from
[card-issuer-processor](../card-issuer-processor) (Python). Same rule order,
same decline codes, same idempotency contract, read and written against the
same Postgres tables the Python service uses.

This is not a rewrite of the whole system. It is one flow, ported honestly,
to demonstrate the same logic in a second language and stack.

## What's here

- `POST /authorize`: card status -> MCC blocklist -> velocity limit -> balance,
  in that order, matching the Python service exactly.
- Decline codes: 14 (invalid card), 54 (expired), 62 (frozen), 57 (blocked
  merchant category), 61 (velocity), 51 (insufficient funds).
- Recovery-point idempotency: a retry with the same key and body returns the
  original result; a retry with the same key and a different body is rejected
  with HTTP 409; a concurrent retry while the key is locked is also rejected.
- The account row is locked (`SELECT ... FOR UPDATE`) before the MCC,
  velocity, and balance checks, the same way the Python service closes the
  check-then-act race on concurrent authorizations.

## What's deliberately not here

Capture, void, refund, and reconciliation live only in the Python service.
One flow done properly, not four done halfway.

## Known differences from the Python service

- **Wire format**: this service uses camelCase JSON field names
  (`cardToken`, `amountMinor`), matching Java naming conventions. The Python
  service uses snake_case (`card_token`, `amount_minor`). Same logic,
  different shape on the wire.
- **Idempotency storage**: the Python service stores the cached response
  body directly on the `idempotency_keys` row. This service looks the result
  up from `authorizations` by `idempotency_key` instead, since that row
  already holds everything needed. Same outcome, different mechanism.
- **Shared database, not owned**: this service does not create or migrate
  the schema. It expects the tables the Python service's `db/001_schema.sql`
  creates, plus migration `db/002_nullable_invalid_card_auth.sql` (nullable
  `card_token` and `account_id`, and the `attempted_card_token` column),
  already applied. Without 002 the unknown-card insert below fails.

## Decline contract (matches the Python service)

Every authorization decline returns HTTP 200 with `status: declined` and a
`decline_code`. That includes a card token that matches no card: it is
declined with code 14, and the attempt is recorded in `authorizations` with a
NULL `card_token` and `account_id` and the raw value received in
`attempted_card_token`, so repeated attempts against fake tokens stay visible.
HTTP 409 is reserved for idempotency conflicts (same key with a different
body, or a concurrent request on the same key).

## Verified

- Approved a real authorization against a funded test card.
- Retried the identical request with the same idempotency key: returned the
  identical `auth_id`, no second hold created.
- Retried the same key with a different amount: rejected with HTTP 409 and
  a clear mismatch message.
- Sent an authorization with a card token that matches no card: HTTP 200,
  `status: declined`, `declineCode: 14`. The resulting `authorizations` row has
  an empty `card_token` and `account_id` and the raw token in
  `attempted_card_token`.

## Status

| Module | State |
|---|---|
| `/authorize` endpoint: card status, expiry, MCC blocklist, velocity limit, balance (codes 62/14/54/57/61/51), unknown-card audit record, row-locked account access | done, manually verified against a live Postgres instance; rule order covered by 8 unit tests |
| Recovery-point idempotency (begin/advance/finish) | done, manually verified incl. mismatch rejection |
| Kafka producer (authorization.decided events on the shared card.transactions topic) | done, verified live against the Python consumer script |
| Automated integration test (Testcontainers: Postgres + Kafka, full Spring context, real HTTP) | done -- 4 tests: approve, idempotent retry, conflicting retry rejected (409), unknown-card decline with audit row |
| Unit tests (rule order, mocked repositories, no database/Docker required) | done, 8 tests, CI on every push |

### Integration test

Previously blocked locally by `Could not find a valid Docker environment`,
even though Docker Desktop was running and reachable by the Docker CLI
directly -- a version-negotiation mismatch between docker-java (the client
library Testcontainers depends on) and Docker Desktop 4.80's engine (API
1.55), which was a very recent release at the time. Fixed by bumping
`testcontainers.version` from 1.21.3 to 1.21.4.

`AuthorizeIntegrationTest` now runs for real: a throwaway Postgres container
seeded from the Python service's own `db/001_schema.sql` and
`db/002_nullable_invalid_card_auth.sql`, a throwaway Kafka broker, and a real
HTTP request through the whole Spring context. Four tests: approve a funded
card, idempotent retry returns the identical result, a conflicting retry is
rejected with 409, and an unknown card token declines with code 14 and an
audit row carrying the raw `attempted_card_token`.

## Running

```bash
# requires the Python service's Postgres + Redpanda containers already running
# (docker compose up -d in ../card-issuer-processor)
./mvnw spring-boot:run   # starts on :8081

curl -X POST http://127.0.0.1:8081/authorize \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-1" \
  -d '{"cardToken": "<a card_token from ../card-issuer-processor/scripts/cards.json>", "amountMinor": 1000, "merchantId": "coffee_shop", "mcc": "5812"}'
```

## Stack

Java 21, Spring Boot 4.1.1, Spring Data JDBC (hand-written SQL, not JPA),
PostgreSQL, Apache Kafka client, publishing authorization.decided events to the same card.transactions topic the Python service uses, verified live with a shared consumer,
Maven, JUnit 5.
