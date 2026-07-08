# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Event-driven order fulfillment platform (generic e-commerce domain: order intake -> inventory
reservation -> fulfillment notification). This is a portfolio build, not a production system —
the full multi-day build plan (architecture, security posture, day-by-day sequencing) lives at
`C:\Users\yakra\.claude\plans\rippling-kindling-snowflake.md`. Read it before making architectural
changes; most of the planned end state (gateway-centralized JWT, Elasticsearch search, the
Ollama-backed AI agent, minikube deployment, Linkerd mTLS, DECISIONS.md) is not built yet.

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
- Start local infra (Kafka, kafka-ui, Postgres, Couchbase): `docker compose -f infra/docker-compose.yml up -d`
  (first run only: copy `infra/.env.example` to `infra/.env` if you want non-default credentials;
  `couchbase-init` bootstraps the cluster/bucket/indexes automatically and is safe to re-run)

Local ports: api-gateway `8080`, order-service `8081`, inventory-service `8082`,
notification-service `8083`, ai-support-agent `8084` (scaffold only so far), kafka-ui `8090`,
Kafka broker `9092`, Postgres `5433` (mapped off the default 5432 to avoid clashing with any other
local Postgres), Couchbase console `8091`. All external traffic is meant to go through the gateway
(`/api/orders/**`, `/api/inventory/**`, `/api/notifications/**`), not directly to a service port.

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

**api-gateway** runs Spring Cloud Gateway on WebFlux via `spring-cloud-starter-gateway-server-webflux`
— the current non-deprecated artifact (the older `spring-cloud-starter-gateway` /
`spring-cloud-gateway-server` is deprecated as of the 2025.0.x train). Routes live under
`spring.cloud.gateway.server.webflux.routes` (not the older `spring.cloud.gateway.routes` key). It
strips the `/api` prefix and proxies to each service via a `*_SERVICE_URL` env var, defaulting to
localhost for local dev.

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
  anything else.
- No `:latest` image tags anywhere in `infra/docker-compose.yml` — every image is pinned to a
  specific version.
