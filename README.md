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
  the schema. It expects the same seven tables the Python service's
  `db/001_schema.sql` creates, already running.

## Verified manually (see below for why not automated yet)

- Approved a real authorization against a funded test card.
- Retried the identical request with the same idempotency key: returned the
  identical `auth_id`, no second hold created.
- Retried the same key with a different amount: rejected with HTTP 409 and
  a clear mismatch message.

## Status

| Module | State |
|---|---|
| `/authorize` endpoint, four-rule decline chain, row-locked account access | done, manually verified against a live Postgres instance |
| Recovery-point idempotency (begin/advance/finish) | done, manually verified incl. mismatch rejection |
| Automated integration test (Testcontainers) | blocked locally by a Docker Desktop 4.80 / docker-java compatibility issue (see below); test code was written and removed pending a fix |
| Unit tests (rule order, mocked repositories, no database/Docker required) | done, 8 tests, CI on every push |

### Why there's no integration test yet

An integration test using Testcontainers (spin up a throwaway Postgres
container, apply the schema fresh, run a real HTTP request through the whole
Spring context) was written and does the right thing on paper, but fails
locally with `Could not find a valid Docker environment`, even though Docker
Desktop is running and reachable by the Docker CLI directly. The underlying
call returns a malformed, empty daemon-info response, consistent with a
version-negotiation mismatch between docker-java (the client library
Testcontainers depends on) and Docker Desktop 4.80's engine (API 1.55),
which is a very recent release. Tried Testcontainers 1.20.1, 1.20.4, and
1.21.3; all three fail identically. This is an environment/tooling issue,
not a defect in the authorization logic, which is independently verified by
the manual curl-based checks above against the same live database the
Python service's own test suite uses.

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
