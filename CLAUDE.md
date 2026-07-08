# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Event-driven order fulfillment platform (generic e-commerce domain: order intake -> inventory
reservation -> fulfillment notification). This is a portfolio build, not a production system —
the full multi-day build plan (architecture, security posture, day-by-day sequencing) lives at
`C:\Users\yakra\.claude\plans\rippling-kindling-snowflake.md`. Read it before making architectural
changes; most of the planned end state (minikube deployment, Linkerd mTLS, DECISIONS.md) is not
built yet.

## Commands

Java 21 is required (the build sets `maven.compiler.release=21`). The default `java`/`mvn` on PATH
may resolve to an older JDK — if so, set `JAVA_HOME` to the Temurin 21 install before running Maven:

```
JAVA_HOME="/c/Users/yakra/tools/jdk-21.0.11+10"
PATH="$JAVA_HOME/bin:$PATH"
```

- Build everything (reactor order handles inter-module deps): `mvn -DskipTests install`
- Compile only: `mvn -DskipTests compile`
- Run all tests: `mvn test`
- Run a single test class: `mvn -pl <module> test -Dtest=ClassName`
- Run one service: `mvn -pl <module> spring-boot:run` (e.g. `-pl order-service`)
- Start local infra (Kafka, kafka-ui, Postgres, Couchbase, Elasticsearch): `docker compose -f infra/docker-compose.yml up -d`
  (first run only: copy `infra/.env.example` to `infra/.env` if you want non-default credentials;
  `couchbase-init` bootstraps the cluster/bucket/indexes automatically and is safe to re-run)
- Generate the JWT signing key pair (first run only, or to invalidate all issued tokens):
  `sh infra/generate-jwt-keys.sh` — writes `infra/keys/jwt-private.pem`/`jwt-public.pem`, gitignored,
  never committed. Every service that validates JWTs points at the same public key file via a
  relative `../infra/keys/jwt-public.pem` path, so it only works when run the standard way
  (`mvn -pl <module> spring-boot:run` from the repo root).
- Log in for a bearer token: `curl -X POST http://localhost:8080/auth/login -d '{"customerId":"cust-001"}'`
  — `cust-001` through `cust-005` are the only seeded demo customers (`DemoCustomerRegistry` in
  api-gateway); anything else gets a 401. No password — see the Architecture section on why.

Ollama must be running on the host separately (`ollama serve`, or the desktop app) with a
tool-calling-capable model pulled — `llama3.2:3b` is the default (`OLLAMA_MODEL` env var to
override), already verified to support tool calling via Ollama's `/api/chat`.
- Serve the web client: `python -m http.server 8085 --directory web-client` (plain static files, no
  build step) — port `8085` matters, it's what api-gateway's CORS config currently allows.

Local ports: api-gateway `8080`, order-service `8081`, inventory-service `8082`,
notification-service `8083`, ai-support-agent `8084`, web-client `8085`, kafka-ui `8090`, Kafka
broker `9092`, Postgres `5433` (mapped off the default 5432 to avoid clashing with any other local
Postgres), Couchbase console `8091`, Elasticsearch `9200`, Ollama `11434` (host, not docker-compose).
All external traffic is meant to go through the gateway (`/api/orders/**`, `/api/inventory/**`,
`/api/notifications/**`, `/api/assistant/**`), not directly to a service port.

## Architecture

**Multi-module Maven build.** The root `pom.xml` pins versions deliberately — do not bump without
checking train compatibility: Spring Boot `3.5.16`, Spring Cloud `2025.0.3` (the 2025.1.x train
targets Spring Boot 4.x, not this project), Spring AI `1.1.8` (the 1.x line targets Boot 3.x; 2.x
targets Boot 4.x). Boot 3.5.x was chosen over the newer 4.1.0 release specifically to avoid building
on a major version with less mature tooling/ecosystem support around it.

**`events-common`** holds the shared Kafka event contracts (Java `record`s) and topic-name constants
(`EventTopics`). Every producer/consumer depends on it so services never hand-roll or directly couple
to each other's DTOs.

**Event flow** (Kafka, JSON payloads):
```
order-service      POST /orders          -> order.created
inventory-service   consumes order.created -> reserves/rejects stock -> inventory.reserved | inventory.rejected
order-service       consumes inventory outcome -> order.confirmed | order.cancelled
notification-service consumes order.confirmed/order.cancelled -> records a notification
```
Every service that produces or consumes a topic declares its own `NewTopic` beans in a
`config.KafkaTopicConfig` class (deliberately duplicated per service) so topics exist regardless of
which service happens to start first.

Kafka JSON deserialization is locked down via `spring.json.trusted.packages: com.orderplatform.events`
plus `ErrorHandlingDeserializer` wrapping `JsonDeserializer` in every consumer config — don't widen
the trusted-packages list or drop the error-handling wrapper. This blocks arbitrary-class
deserialization from a forged Kafka header and stops a poison-pill message from crash-looping a
consumer.

**Per-service package layout** (consistent across order/inventory/notification-service):
`domain` (plain domain objects) / `repository` (persistence) / `service` (business logic, e.g.
`InventoryReservationService`) / `messaging` (Kafka listeners/publishers) / `api` (REST controllers +
DTOs) / `config` (Kafka topic declarations).

**Persistence**: order-service uses Couchbase (bucket `orders`, single collection, documents keyed
`order::<id>`/`outbox::<id>`/`processed::<id>` and distinguished by key prefix rather than a
per-document `type` field); inventory-service uses Postgres via Flyway-managed migrations
(`db/migration/V*__*.sql`) with JPA/Hibernate. `notification-service` is still an in-memory
`ConcurrentHashMap` — that one's a deliberate simplification, not a placeholder awaiting a Day 2-style
upgrade (nothing in the plan calls for giving it a real store).

**Outbox pattern, implemented two different ways on purpose**: inventory-service uses the classic
relational outbox (an `outbox_event` table written in the same DB transaction as the stock update,
relayed by a poller); order-service uses Couchbase's native multi-document ACID transactions
(`cluster.transactions().run(ctx -> ...)`) to write the `Order` document and its outbox document
atomically instead. Both solve the same dual-write problem; comparing the two approaches is
deliberate ADR material, not accidental inconsistency. In both services, only the outbox relay
(`OutboxRelay`/`OutboxPoller` in inventory-service, `OutboxRelay` in order-service) actually calls
`KafkaTemplate` — domain code never publishes to Kafka directly.

**Idempotent consumption**: inventory-service checks a `processed_event` table (unique on Kafka
event id) before reserving stock; order-service checks a `processed::<eventId>` Couchbase document
inside the same transaction as the status update. Either way, a redelivered Kafka message becomes a
no-op rather than double-applying an effect.

**Inventory reservation** (`InventoryReservationService.reserveLines`) is all-or-nothing across order
lines and runs in its own `REQUIRES_NEW` transaction: a failure on any line rolls back that whole
nested transaction, so the database itself undoes any earlier lines already decremented in the same
call — no manual compensating-release logic. Concurrent reservations against the same sku surface as
`ObjectOptimisticLockingFailureException` (via `StockItem`'s `@Version` column);
`InventoryOrderProcessor` retries up to 3 times before giving up.

**Spring `@Transactional` self-invocation gotcha**: `OutboxRelay`/`OutboxPoller` in inventory-service
are deliberately two separate beans, not one class with the scheduled method calling a `@Transactional`
method on itself — a self-invocation bypasses Spring's transactional proxy entirely and silently runs
with no transaction (this broke the pessimistic-locked outbox query once already; don't reintroduce
it by merging them back into one class).

**Order search (CQRS-lite read model)**: `order-service`'s `search` package keeps an Elasticsearch
index (`OrderSearchDocument`, index `orders`) in sync via `OrderSearchIndexer`, a Kafka consumer in
its own group (`order-service-search-indexer`) subscribed to `order.created`/`order.confirmed`/
`order.cancelled` — the same topics order-service itself publishes to via its outbox, consumed a
second time here. This is eventually consistent with Couchbase (the source of truth) by design; ES
is never written to from request-handling code. `GET /orders/search` (`OrderSearchService`) requires
`customerId` (never an unscoped cross-customer query) with optional `status`/`from`/`to` filters,
capped at 50 results.

**Elasticsearch date field gotcha**: `OrderSearchIndexer`'s status-update path does a partial update
via a raw `Document` (`Document.create(); doc.put("updatedAt", ...)`), which bypasses Spring Data
Elasticsearch's own entity-mapping conversion. Writing `Instant.toString()` directly emits nanosecond
precision when present, which Spring Data ES's own reader can't parse back (`ConversionException` on
the next search) - always `.truncatedTo(ChronoUnit.MILLIS)` before stringifying an `Instant` for a
manual partial update. The full-document path (`operations.save(...)`) doesn't have this problem
since it goes through the entity mapper on both write and read.

**ai-support-agent** (Spring AI + local Ollama, `llama3.2:3b` by default) exposes
`POST /assistant/ask {question}` (no `customerId` field - see JWT section below). **The actual
security boundary is in `OrderTools`, not the prompt**: a new `OrderTools` instance is constructed
per request with `customerId` captured in its constructor, and only `orderId`/`status` are
parameters the model can supply - the model can never control whose orders it's searching, so even
a successful prompt injection can only change which order/status is queried, never the customer.
`getOrderStatus` also independently re-checks that the returned order's `customerId` matches, as
defense in depth. Verified live: cross-customer lookup of a real order ID correctly returns
"not found" rather than leaking it.

**Token-forwarding gotcha**: `OrderTools` calls order-service via `OrderServiceClient`, which is a
separate internal HTTP call, not something the gateway's own auth automatically covers. The first
JWT integration pass forgot this - `OrderServiceClient` called order-service with no
`Authorization` header at all, order-service's defense-in-depth check correctly rejected it with
401, and the *tool call itself* failed, which Spring AI surfaced to the model as "no access" - a
working-as-designed authz failure that looked, on the surface, like a wrong answer. Fixed by
forwarding the original caller's raw JWT (`Jwt.getTokenValue()`, threaded through
`AssistantController` → `AiSupportAgentService` → `OrderTools` → `OrderServiceClient`) rather than
minting a separate service-account credential - this preserves "acting on behalf of this specific
customer" all the way through instead of adding a second, looser trust boundary. If a future tool
needs to call another service, it needs the same treatment - a service-to-service call is not
automatically authenticated just because the inbound request was.

`GuardedChatCaller` wraps every Ollama call with a Resilience4j `CircuitBreaker` + `TimeLimiter`
(`resilience4j.circuitbreaker.instances.ollama` / `resilience4j.timelimiter.instances.ollama` in
`application.yml`) so a slow/hung local model fails fast instead of piling up stuck requests. Since
the Ollama call is blocking, the `TimeLimiter` wraps it via `CompletableFuture.supplyAsync` on a
virtual-thread executor rather than making the whole call chain reactive.

**Resilience4j version-alignment gotcha**: `resilience4j-spring-boot3` on its own resolved a
mismatched `resilience4j-spring6` version and failed at startup with
`ClassNotFoundException: RxJava3OnClasspathCondition`. Fixed by importing `resilience4j-bom` in the
root `pom.xml`'s `dependencyManagement` so every resilience4j artifact resolves to the same version -
don't add a resilience4j dependency anywhere without that BOM already covering it.

**api-gateway** runs Spring Cloud Gateway on WebFlux via `spring-cloud-starter-gateway-server-webflux`
— the current non-deprecated artifact (the older `spring-cloud-starter-gateway` /
`spring-cloud-gateway-server` is deprecated as of the 2025.0.x train). Routes live under
`spring.cloud.gateway.server.webflux.routes` (not the older `spring.cloud.gateway.routes` key). It
strips the `/api` prefix and proxies to each service via a `*_SERVICE_URL` env var, defaulting to
localhost for local dev. Its filter/route Java API (`GlobalFilter`, `GatewayFilterChain`, etc.) still
lives under the classic `org.springframework.cloud.gateway.filter` package even after the 2025.0.x
artifact rename - the actual route/filter implementation is in the (unrenamed)
`spring-cloud-gateway-server` jar underneath; `-server-webflux` is a thin marker module selecting the
WebFlux runtime.

**JWT auth**: api-gateway is the only service that issues tokens (`POST /auth/login`, not proxied -
served directly by api-gateway's own `AuthController`, not routed to a downstream service) and the
only place with the RSA private key. `DemoCustomerRegistry` seeds 5 fixed demo customer ids
(`cust-001`..`cust-005`) with no password check at all - this is explicitly not a real identity
provider. Every other route requires a valid RS256 JWT (`GatewaySecurityConfig`, checked against
`infra/keys/jwt-public.pem`); order-service and ai-support-agent independently re-validate the same
token against the same public key as defense in depth, rather than trusting a gateway-forwarded
header. **The caller's identity always comes from the JWT's `sub` claim
(`@AuthenticationPrincipal Jwt jwt`, then `jwt.getSubject()`) - never from a request body/query
param.** This is why `CreateOrderRequest`/`AskRequest` have no `customerId` field, and why
`GET /orders/search` takes no `customerId` param: as of Day 5 there is nowhere left in the API for a
client to *claim* an identity, only prove one via the token. `GET /orders/{id}` also now 404s (not
403, matching the "don't confirm it exists" convention used throughout) when the authenticated
caller doesn't own the order - a real authz gap that only became fixable once real identity existed.

**Rate limiting**: `RateLimitingGlobalFilter` (api-gateway) enforces a fixed per-IP, per-second
budget using Resilience4j's `RateLimiter` directly (not Spring Cloud Gateway's built-in
`RequestRateLimiter`, which is Redis-coupled) - fully in-memory, correct for one gateway instance,
and explicitly not something a scaled-out gateway could rely on without a shared store. Verified
live: 40 concurrent requests against a 20/sec budget produced 429s once the budget was exceeded.

**CORS**: locked to a single configurable origin (`orderplatform.cors.allowed-origin`, `http://localhost:8085`
- the web client's actual serving port, not a placeholder anymore). **CORS preflight gotcha**: Spring
Security's `.cors(...)` DSL only adds CORS response headers - it does *not* implicitly permit the
browser's `OPTIONS` preflight request through `.authorizeExchange()`. Without an explicit
`.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()`, every preflight (sent with no `Authorization`
header, by definition) hits `.anyExchange().authenticated()` and gets rejected, which silently breaks
every browser-based call to a protected route even though the same call works fine from curl. Caught
this before it became a real bug, while building the web client, not after.

**web-client** (`web-client/`) is plain HTML/CSS/JS with no build step or framework - a login panel
(pick one of the 5 seeded demo customers), an order form, an orders table with a status filter, and
an AI assistant chat panel, all calling only `http://localhost:8080` (the gateway) via `fetch`.
`authedFetch` in `app.js` is the one place that attaches the bearer token and handles a 401 by
clearing the session and bouncing back to the login screen - every other function goes through it
rather than calling `fetch` directly. Session (token + customerId) lives in `sessionStorage`, not
`localStorage` - cleared when the tab closes, matching the token's short (1 hour) lifetime. Verified
in an actual browser (not just curl): login, place-order happy path, place-order rejection path
(insufficient stock, reason displayed), order search/filtering, the AI chat round-trip through the
full gateway → ai-support-agent → Ollama chain, and the invalid-token edge case (corrupting the
stored token correctly triggers the 401 → logout → "session expired" flow).

## Conventions to keep consistent

- **This code is a portfolio artifact meant to be read by strangers (interviewers/reviewers), not
  just internal teammates — comment more liberally than typical terse production code.** Every
  public class gets a short Javadoc stating its role in the bounded context (what it owns, not a
  restatement of its name). Non-obvious business logic and trade-offs get an inline comment
  explaining the *why*: e.g. the all-or-nothing rollback in `InventoryReservationService.reserve`,
  the `spring.json.trusted.packages` lockdown, the `NewTopic` bean duplication across services, the
  `ErrorHandlingDeserializer` wrapping. Straightforward getters/simple mappings don't need comments
  just to hit a quota — the goal is a reader understanding *intent*, not comment density.
- Constructor injection only — no field injection.
- DTOs and Kafka events are Java `record`s.
- Bean Validation (`jakarta.validation`) enforced at the REST boundary; a `@RestControllerAdvice`
  (`GlobalExceptionHandler`) returns field-level messages on validation failures, a 404 for
  `NoResourceFoundException` (unmatched routes), and a generic message with no stack trace on
  anything else. `@RequestParam`/`@PathVariable` constraints (needs `@Validated` on the controller
  class) raise `ConstraintViolationException`, not `MethodArgumentNotValidException` — that's only
  for `@RequestBody`. A required-but-missing `@RequestParam` is a third, separate exception
  (`MissingServletRequestParameterException`), and an unparseable enum/type `@RequestParam` is a
  fourth (`MethodArgumentTypeMismatchException`) — all four are handled distinctly.
- No `:latest` image tags anywhere in `infra/docker-compose.yml` — every image is pinned to a
  specific version.
