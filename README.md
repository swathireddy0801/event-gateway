# Event Gateway

Public-facing entry point for ingesting financial transaction events and applying them to
account balances, built to tolerate out-of-order and duplicate delivery from upstream systems.
Validates incoming events, stores them (with its own H2 database) keyed by `eventId` for
idempotency, and calls the separate [Account Service](https://github.com/YOUR_ORG/account-service)
to apply each transaction.

If the Account Service is unreachable, the event is still durably stored (status `FAILED`) and
the Gateway returns `503` — the client can safely retry later because resubmission is idempotent.
Read endpoints (`GET /events/...`) never depend on the Account Service, so they keep working
during an outage.

The two services do not share a database or process state; they only communicate over HTTP.

### Why balance is unaffected by arrival order

Balance is `sum(CREDITs) - sum(DEBITs)`, and addition is commutative — so the *balance* is
correct no matter what order transactions arrive in. Out-of-order delivery only matters for the
**event listing**, which is explicitly sorted by `eventTimestamp` (not insertion order).

## Distributed Tracing

- Generates a UUID trace ID per incoming request (or reuses one supplied via the `X-Trace-Id`
  header), stores it in SLF4J MDC, and echoes it back to the client in the response header.
- Forwards `X-Trace-Id` to the Account Service on every downstream call.
- Emits structured JSON logs (via `logstash-logback-encoder`) that include `traceId`, `timestamp`,
  `level`, `logger`, and `service`, so a single request can be traced across both services' logs.

## Observability

- **Structured logging**: JSON logs on stdout (see `logback-spring.xml`).
- **Health checks**: `GET /health` reports `status` (UP/DOWN based on DB connectivity) plus a
  `checks` breakdown, and Account Service reachability as a diagnostic (without marking the
  Gateway itself DOWN for that — it can still serve most of its own API when the Account Service
  is down).
- **Metrics**: Micrometer counters/timers (`event_gateway.events.received/duplicate/applied/failed`,
  `event_gateway.account_service.call.latency`) plus full Spring Boot Actuator metrics at
  `/actuator/metrics` and `/actuator/prometheus`. Circuit breaker state is exposed at
  `/actuator/health` and `/actuator/circuitbreakers`.

## Resiliency: Circuit Breaker

Wraps the call to the Account Service with a **Resilience4j circuit breaker**
(`resilience4j-spring-boot3`), plus a bounded exponential-backoff retry and connect/read timeouts
on the underlying `RestTemplate`.

Configuration (`application.yml`, tuned lighter in `application-test.yml` for fast tests):
- Sliding window of 10 calls, opens at ≥50% failure rate with a minimum of 5 sampled calls
- Opens for 10s, then allows 3 trial calls in half-open state
- Retry: up to 3 attempts with exponential backoff (200ms → 400ms → 800ms), only on connection
  errors (not on `CallNotPermittedException`, so an open breaker fails fast instead of retrying
  into it)

When the breaker is open or a call fails, `POST /events` returns `503` with the event already
persisted; `GET /accounts/{id}/balance` also returns `503`. `GET /events/...` is unaffected.

## Security

The Gateway is an OAuth2 resource server: every request except `/health` requires a valid JWT
bearer token (`Authorization: Bearer <token>`), validated against `JWT_CLIENT_SECRET` (HS256).
`POST /events` additionally requires an `events:write` scope; other endpoints just need any
valid token.

When calling the Account Service, the Gateway mints its own short-lived internal JWT (signed
with `JWT_INTERNAL_SECRET`, `internal:accounts` scope, 60s TTL) rather than forwarding the
client's token - so a client-facing token can never be replayed directly against the internal
service. `JWT_INTERNAL_SECRET` must be identical on both services.

**Set real secrets in every environment but local dev** - `application.yml` only has dev-only
defaults:

```bash
export JWT_CLIENT_SECRET="<at least 32 random bytes>"
export JWT_INTERNAL_SECRET="<at least 32 random bytes, matching account-service>"
```

For a production identity provider, swap the `clientJwtDecoder` bean in `JwtDecoderConfig` for
`NimbusJwtDecoder.withJwkSetUri(...)` pointed at your IdP instead of a shared secret.

**Getting a test token locally:**

```bash
python3 scripts/generate-test-token.py events:write
```

```bash
curl -X POST http://localhost:8080/events \
  -H "Authorization: Bearer $(python3 scripts/generate-test-token.py events:write)" \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-1","accountId":"acct-1","type":"CREDIT","amount":100,"currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}'
```

## Prerequisites

- Java 17+
- Maven 3.9+
- Docker & Docker Compose (optional)
- A running [Account Service](https://github.com/YOUR_ORG/account-service) instance

## Running with Docker Compose

```bash
docker compose up --build
```

Exposes the service at http://localhost:8080. By default it looks for the Account Service at
`http://host.docker.internal:8081` — override with `ACCOUNT_SERVICE_URL` if needed.

## Running locally without Docker

```bash
mvn spring-boot:run
```

By default the Gateway looks for the Account Service at `http://localhost:8081`. Override with
`ACCOUNT_SERVICE_URL` if needed.

## Running the tests

```bash
mvn test
```
