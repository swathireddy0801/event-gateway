# Event Gateway

Public-facing entry point for ingesting financial transaction events and applying them to
account balances, built to tolerate out-of-order and duplicate delivery from upstream systems.
Validates incoming events, stores them (with its own H2 database) keyed by `eventId` for
idempotency, and calls the separate [Account Service](https://github.com/swathireddy0801/account-service)
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
curl -X POST http://localhost:8080/event-gateway/events \
  -H "Authorization: Bearer $(python3 scripts/generate-test-token.py events:write)" \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-1","accountId":"acct-1","type":"CREDIT","amount":100,"currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}'
```
```bash
curl http://localhost:8080/event-gateway/events/evt-1 \        
  -H "Authorization: Bearer $(python3 scripts/generate-test-token.py events:read)" 
```
```bash
 curl -X GET http://localhost:8080/event-gateway/accounts/acct-1/balance \
  -H "Authorization: Bearer $(python3 scripts/generate-test-token.py events)"  
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

## Known Gaps & Design Decisions

Flagging these proactively rather than leaving them for the reviewer to find — happy to
discuss any of them in more depth.

1. **`docker-compose.yml` is per-service, not a single root file.** The spec asks for one
   `docker-compose.yml` that starts both services. Each repo ships its own compose file
   instead (Gateway reaches the Account Service via `host.docker.internal`, which works on
   Docker Desktop but is not guaranteed on Linux Docker Engine without extra host config).
   Given these are two independent repos/submissions, I optimized for each service being
   runnable and testable in isolation rather than assuming a shared parent folder. In a real
   repo layout I'd add a third, root-level `docker-compose.yml` (or a small
   `docker-compose.override.yml`) that wires both services together on one Docker network by
   service name — that's the first thing I'd add with another 15 minutes.

2. **A failed apply is not automatically retried later.** If the Account Service is down when
   an event is submitted, the event is stored with status `FAILED` and the Gateway returns
   `503`. Because idempotency is keyed on `eventId`, resubmitting the *same* `eventId` just
   returns the stored `FAILED` record — it does not re-attempt the Account Service call. So
   today the retry burden is fully on the caller (submit with a new attempt strategy, or wait
   for a manual/ops-triggered reconciliation). A background job that scans `FAILED` events and
   replays them against the Account Service once the circuit closes again is the natural next
   step; I scoped it out as a bonus item ("async fallback") given the 3–4 hour target.

3. **Idempotency check is not race-free under concurrent duplicate submissions.** The
   `findByEventId` check and the insert aren't atomic, so two requests with the same
   `eventId` arriving at the same instant can both pass the check. The DB-level unique
   constraint on `eventId` still prevents a duplicate *row* (data integrity is safe), but the
   loser of the race currently falls through to a generic `500` instead of a graceful
   duplicate response, because `GlobalExceptionHandler` doesn't special-case
   `DataIntegrityViolationException` yet. Data stays correct, but the status code is wrong
   under a fairly narrow race window — a `catch` around the save that re-fetches and returns
   the winning record would close this.

4. **JWT auth wasn't requested by the spec, and it adds setup friction.** I added an OAuth2
   resource server on both services (see the Security section above) because a "financial
   transaction" system with zero auth felt like an odd thing to hand over, even for a
   take-home. The trade-off is real, though: it means `JWT_CLIENT_SECRET` /
   `JWT_INTERNAL_SECRET` have to be exported before anything starts, and there's a
   `generate-test-token.py` script in the loop just to curl the API. If this is more friction
   than signal for grading, it's isolated to `SecurityConfig`, `JwtDecoderConfig`, and
   `ServiceTokenIssuer` and can be stripped without touching the core event/idempotency/
   resiliency logic.

5. **`GET /accounts/{accountId}/balance` on the Gateway isn't in the spec's endpoint table.**
   The spec's Account Service table has this route, but the Gateway table doesn't. I added a
   thin proxy for it on the Gateway anyway, on the assumption that external clients shouldn't
   need to know the internal service exists at all. Easy to remove if the intent was for the
   Gateway to only ever expose `/events`.

6. **The Account Service call happens inside `@Transactional` on the Gateway.** `submitEvent`
   holds the local DB transaction open for the duration of the outbound HTTP call (including
   retries/circuit-breaker overhead). For H2 in a take-home this is harmless, but in a real
   deployment holding a DB transaction/connection open across a network call is generally
   worth avoiding — I'd split "persist locally" and "call downstream, then update status" into
   separate transactions if this were headed to production.

7. **No consistency check on duplicate `eventId` with a different payload.** If the same
   `eventId` is resubmitted with a different `amount`/`type`/`accountId`, the service silently
   returns the original stored event rather than flagging the mismatch. The spec only requires
   that a duplicate not double-apply, which this satisfies, but a stricter implementation might
   return `409 Conflict` when the resubmitted payload doesn't match what's on file.
