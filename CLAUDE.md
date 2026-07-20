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

**Stale-UI-state gotcha (found via manual testing, not the earlier automated pass)**: switching
demo customers (log out, log back in as someone else) looked like every customer saw the same
orders and the same AI conversation - but the backend was never the problem (re-verified: each
customer's `/orders/search` call correctly returned only their own data). The orders table happened
to self-heal because `refreshOrders()` overwrites it on every login, but `#chat-log` only ever
*appends* messages and `#order-result` only gets overwritten by the next "place order" click -
neither was ever cleared on logout, so the previous customer's chat transcript and last order
confirmation stayed visible and bled into the next customer's session. Fixed with
`resetSessionUiState()`, called from both `logout()` and `authedFetch`'s 401 handler. Lesson: a
correctly-scoped API is not the same thing as a correctly-reset UI - check what's still on screen
after a session boundary, not just what the next network call returns.

## Kubernetes deployment (minikube)

The full stack — all 6 app services plus Kafka, Couchbase, Postgres, and Elasticsearch — runs
in-cluster on minikube, not just the app services against docker-compose infra. This was a
deliberate scope choice (the harder path was picked over keeping infra on docker-compose) and it
surfaced real K8s operational lessons worth knowing before touching `infra/k8s/`.

**Dockerfiles**: a single root `Dockerfile` builds all 6 images via a shared Maven build stage
(`FROM maven:3.9.16-eclipse-temurin-21-alpine AS build`, not a JDK-only image — the JDK-only alpine
image has no `mvn` binary) feeding multiple thin `eclipse-temurin:21-jre-alpine` runtime targets,
one per service, run non-root (`adduser -S app -G app`). Build a single service with
`docker build --target <service-name> -t <service-name>:latest .` (e.g. `--target order-service`).
`web-client` gets its own trivial `nginx:alpine` Dockerfile instead, since it's static files, not a
JVM app.

**Kustomize layout**: `infra/k8s/base/` is registry-agnostic (no `imagePullPolicy`, works against
any registry); `infra/k8s/overlays/minikube/` adds the one genuinely minikube-specific patch —
`imagePullPolicy: Never` on the 6 app-service Deployments, via explicit JSON6902 patches, one per
Deployment name — so images built directly into minikube's internal Docker daemon are used as-is
rather than pulled from a registry that doesn't exist. Apply with
`kubectl apply -k infra/k8s/overlays/minikube`. To build images so minikube can see them:
`eval $(minikube docker-env)` first (must be re-run in every new shell/Bash call — it doesn't
persist), then the same `docker build --target <service>` commands.

**Secrets**: `infra/k8s/create-secrets.sh` creates the `jwt-keys` (from the same
`infra/keys/jwt-*.pem` files generated locally), `postgres-credentials`, and
`couchbase-credentials` K8s Secrets imperatively from local files/literals — never checked into a
static YAML manifest with embedded values. Run it once per fresh cluster before applying the
Kustomize overlay.

**Kafka headless-Service deadlock (the trickiest bug here)**: this is a single-pod
broker+controller (KRaft combined mode), which must reach itself via the `kafka` Service name to
register as controller — but a normal `ClusterIP` Service only routes to pods that are already
`Ready`, and the pod can't become `Ready` until that self-connection succeeds. Fixed with two
changes to the Service, both required: `clusterIP: None` (headless) plus
`publishNotReadyAddresses: true` (a headless Service's DNS still defaults to Ready-only otherwise).
Don't "simplify" this back to a normal Service — it will silently deadlock on a fresh cluster.

**Probe lessons (apply to any new stateful workload added here)**:
- Exec probes (`pg_isready`, `kafka-broker-api-versions.sh`) default to a 1-second timeout, which
  a busy single-node cluster (concurrent large image pulls) blows through even for a genuinely
  healthy container — set an explicit `timeoutSeconds`, or better, prefer a `tcpSocket` probe when
  "is the port accepting connections" is an adequate proxy for health (it is for Kafka here, and
  it's what replaced the exec probe entirely after even an 8s timeout wasn't enough).
- Slow-booting containers (Elasticsearch's plugin/security init can take 2+ minutes under
  contention) need a `startupProbe`, not just a lenient `livenessProbe` — a startupProbe suspends
  liveness/readiness checks until it first succeeds, which is the only way to avoid killing a
  container that's still legitimately starting.
- An OOMKilled container (`kubectl get pod ... -o jsonpath='{.status.containerStatuses[0].lastState.terminated}'`
  showing `"reason":"OOMKilled"`) means the memory *limit* is below the process's real footprint,
  not just its heap — Elasticsearch's `-Xmx512m` needed a `1280Mi` limit, not 768Mi, because heap is
  only part of an ES container's memory (Lucene mmap, Netty buffers, thread stacks add up).

**minikube resource sizing**: `minikube start --memory=X` cannot resize an already-created
profile — `minikube delete` + recreate is required, which also destroys previously-built images
(they live in minikube's own internal Docker daemon) and all applied cluster state. Budget for
this before resizing: re-enable addons (`ingress`), rebuild all 6 images, re-run
`create-secrets.sh`, and re-apply the Kustomize overlay from scratch.

**Networking from Windows host**: minikube's internal node IP is not directly reachable from the
Windows host in this setup. Verify Ingress-routed behavior via
`kubectl port-forward -n ingress-nginx svc/ingress-nginx-controller <local-port>:80` plus
`curl --resolve order-platform.local:<local-port>:127.0.0.1 http://order-platform.local:<local-port>/...`
rather than trying to hit the minikube IP or relying on `minikube tunnel` (which needs elevated
privileges this environment doesn't have).

**NetworkPolicies exist** (`infra/k8s/base/networkpolicies.yaml`, default-deny-ingress plus
explicit per-relationship allows) **but are not currently enforced** — minikube's default "bridge"
CNI doesn't implement `NetworkPolicy`. They're written and applied so the intent is documented and
they'll take effect once a policy-enforcing CNI (e.g. Calico) is installed, deferred to the
security-hardening pass.

## Service mesh mTLS (Linkerd)

Every pod in `order-platform` gets a `linkerd-proxy` sidecar auto-injected (namespace-level
`linkerd.io/inject: enabled` annotation in `infra/k8s/base/namespace.yaml`), giving every
service-to-service call in the cluster mutual TLS with a verified peer identity — **enforced right
now**, unlike the NetworkPolicies above, since it doesn't depend on the CNI at all; each proxy
enforces it locally regardless of what the network layer does. The two layers are deliberately
complementary: NetworkPolicies are coarse L3/L4 "which pod IPs can reach which" (currently inert
here); Linkerd's `AuthorizationPolicy` is identity-based L7/mTLS "which verified workload identity
can reach which" (active today). Verified via `linkerd check --proxy -n order-platform` (clean) and
by reading each proxy's own metrics
(`linkerd diagnostics proxy-metrics -n order-platform po/<pod>`, grep for `tls="true"` plus a
`server_id=...serviceaccount.identity.linkerd.cluster.local` on outbound connections) — this
confirms real mTLS on both HTTP traffic (Couchbase's REST ports, Elasticsearch) and the
binary/opaque ones (Kafka, Postgres) alike, not just a superficial check.

**Linkerd install prerequisites** (edge-26.6.3, since Linkerd no longer ships a separate "stable"
channel — edge is the current recommended install): the Gateway API CRDs must exist *before*
`linkerd install --crds` (`kubectl apply --server-side -f
https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.2.1/standard-install.yaml`), and
`linkerd install` itself needs `--set proxyInit.runAsRoot=true` on minikube's `docker` driver
(without it: "there are nodes using the docker container runtime and proxy-init container must run
as root user").

**Deliberately did not install the `linkerd-viz` extension** (the Prometheus-backed
dashboard/metrics-history extension) — Day 7 already hit real OOM/resource-pressure limits on this
single-node cluster, and Prometheus's footprint isn't justified just to prove mTLS is working when
`linkerd check --proxy` and `diagnostics proxy-metrics` prove it directly, for free, using tools
already installed. In practice each `linkerd-proxy` sidecar only costs ~3-6MiB RSS at idle — the
mesh itself was never the resource risk here, viz would have been.

**Opaque ports for binary protocols**: Kafka (9092/9093), Postgres (5432), and Couchbase's KV port
(11210) all speak binary wire protocols, not HTTP — without `config.linkerd.io/opaque-ports`,
Linkerd's protocol-sniffing adds a per-connection detection delay. This needs setting in **two
places, not one**: the Service's annotation (read by *calling* proxies doing outbound discovery) and
the same annotation directly on the pod template (read by that workload's *own* inbound proxy) —
Service-level alone left `linkerd check --proxy` still flagging the pod until the workload was
recreated with the pod-level annotation too. Couchbase's other ports (8091 mgmt, 8093 query) are
HTTP-based REST/N1QL APIs and don't need this.

**ServiceAccount-per-workload is required, not optional, for AuthorizationPolicy to mean anything**:
every Deployment originally ran under the auto-mounted `default` ServiceAccount, and Linkerd derives
each pod's mesh identity from its ServiceAccount
(`<name>.<namespace>.serviceaccount.identity.linkerd.cluster.local`) — with everything on `default`,
every workload would present the *same* identity, making "only api-gateway may call order-service"
impossible to express. `infra/k8s/base/serviceaccounts.yaml` gives every workload (plus the
couchbase-init Job, on its own distinct identity) a dedicated ServiceAccount with zero RBAC
permissions attached — they exist purely as mesh identities, nothing in this app calls the
Kubernetes API.

**`AuthorizationPolicy.spec.requiredAuthenticationRefs` is AND'd across entries, not OR'd** — every
listed authentication must be satisfied by the *same* connection, so it cannot be used to mean "any
of these callers is fine." Where a `Server` legitimately needs to accept more than one caller
identity (e.g. order-service is called by both api-gateway and ai-support-agent), that whole set
must live inside a *single* `MeshTLSAuthentication`'s `identityRefs` list instead — a list within one
object genuinely is OR-matched. Got this backwards on the first pass (two separate
`MeshTLSAuthentication` refs on one policy), which would have made the route uncallable by anyone;
`infra/k8s/base/authorization-policies.yaml`'s `order-service-authorized-clients` and
`couchbase-admin-clients` are the corrected, combined form.

**Couchbase's mgmt port (8091) is needed by order-service itself, not just the bootstrap Job**: the
Couchbase Java SDK always opens a connection to 8091 for cluster topology/bucket-config polling as
part of its normal startup, even though actual document reads/writes go over the KV port (11210).
Scoping `couchbase-mgmt-server`'s `AuthorizationPolicy` to only `couchbase-init`'s identity looked
reasonable by the port's *name* but broke order-service's own startup (restart-looping on
`linkerd-proxy` logging `"unauthorized request on route"` against 8091) — found by reading the
*couchbase* pod's own proxy logs (the rejecting side), not order-service's. Fixed by allowing both
identities on that Server, matching what the query Server (8093) already needed for the same reason
(order-service's `OutboxRelay` polls via N1QL at runtime; the bootstrap Job also runs N1QL once for
index creation).

**Couchbase and Postgres both use `emptyDir` storage (ephemeral by design, see the minikube
deployment section above)** — every time either pod is recreated (a mesh-injection rollout, an
opaque-ports pod-template change, anything that replaces the pod rather than just restarting the
container), Couchbase loses its bootstrapped cluster/bucket/users and Postgres starts from a truly
empty data directory. Postgres self-reinitializes from `POSTGRES_USER`/`POSTGRES_PASSWORD` env vars
on every fresh start, so it needs nothing further. Couchbase does not: `infra/k8s/base/couchbase-init-job.yaml`'s
Job must be deleted and recreated (`kubectl delete job couchbase-init` then re-`apply -k`) after
every Couchbase pod recreation, or order-service will fail SASL authentication against a
bucket/user that no longer exists. This bit us three separate times while rolling out the mesh and
its policies — worth remembering before any future change that touches Couchbase's Deployment spec.

## Observability (Prometheus + Grafana)

Added late (post-Day 10) for demo purposes, but wired as a permanent part of the stack, not a
throwaway — provisioned declaratively (ConfigMap-mounted YAML/JSON), non-root, resource-limited,
and covered by the same NetworkPolicy/Linkerd `AuthorizationPolicy` model as every other workload.

**Scraping is static-config, not Kubernetes service-discovery**: `infra/k8s/base/prometheus.yaml`'s
`prometheus.yml` hardcodes 5 fixed `scrape_configs` targets (one per app service,
`/actuator/prometheus`) rather than using `kubernetes_sd_configs` — there are only 5 known targets
and service-discovery would need extra RBAC (list/watch on pods) for no real benefit at this scale.

**`/actuator/prometheus` needed explicit exposure + security allowlisting in every service**: adding
`micrometer-registry-prometheus` and `management.endpoints.web.exposure.include: ...,prometheus`
wasn't enough on its own — `order-service` and `ai-support-agent`'s Spring Security config, and
api-gateway's `GatewaySecurityConfig`, only `permitAll()`'d `/actuator/health` and `/actuator/info`;
`/actuator/prometheus` 401'd until added to the same allowlist. `inventory-service` and
`notification-service` have no Spring Security dependency at all, so nothing needed there.

**Grafana provisioning is declarative, not UI-clicked**: datasource (`grafana-datasources` ConfigMap)
and dashboard (`grafana-dashboard.json`, mounted via Kustomize's `configMapGenerator` so edits get a
fresh hash-suffixed ConfigMap and an automatic Deployment volume-reference update + rollout) are both
files in git — the dashboard a reviewer sees is exactly the JSON in the repo, not manually-clicked
state that would be lost on pod recreation. `GF_AUTH_ANONYMOUS_ENABLED=true` (Viewer role) so the
demo dashboard is viewable with zero login friction.

**Spring Cloud Gateway's proxied-request metrics live under a separate metric family**:
`http_server_requests_seconds_*` only covers requests api-gateway handles directly (e.g. its own
`/auth/login` controller) — requests it *proxies* through to backends are recorded under
`spring_cloud_gateway_requests_seconds_*` (tagged by `routeId`) instead. The dashboard's "HTTP
request rate" panel alone would show api-gateway near-zero despite real traffic; a dedicated
"Gateway proxied requests, by route" panel using the correct metric family covers this.

**Kafka consumption-rate metric name**: `kafka_consumer_fetch_manager_records_consumed_total`, not
the more guessable `kafka_consumer_records_consumed_total` (doesn't exist) — found by grepping a
running service's actual `/actuator/prometheus` output for `kafka_*_total` rather than assuming.

**Prometheus's own inbound is deliberately NOT given a restrictive `AuthorizationPolicy`** — same
reasoning as api-gateway/web-client: it's reached via `kubectl port-forward` for demo viewing, which
hits the same iptables inbound interception as any other connection, so a restrictive policy would
block the demo path itself. Only Grafana (a normal in-mesh Service call) is authorized as a caller.

**Real memory usage stayed well within configured limits after adding both** (checked via
`eval $(minikube docker-env) && docker stats --no-stream`): grafana ~102/256MiB, prometheus
~67/384MiB — no OOM, no tweaking needed. Node-level configured-limit allocation is tight (~89%)
but that's pre-existing (Kafka in particular already ran close to its own limit before this
addition), not something Prometheus/Grafana caused.

## Testing, dependency scanning, and NetworkPolicy enforcement (Day 9)

**Unit tests**: 58 JUnit5/Mockito tests across the four modules with meaningful logic
(inventory-service: `InventoryReservationService`, `InventoryOrderProcessor`, `OutboxWriter`,
`OutboxRelay`, `OutboxPoller`; order-service: the `Order` domain aggregate, its own
`OutboxWriter`/`OutboxRelay`, `GlobalExceptionHandler`; api-gateway: `JwtIssuer`; ai-support-agent:
`OrderTools` — including the cross-customer-leak case explicitly — and its own
`GlobalExceptionHandler`). `notification-service` has no test-worthy logic of its own yet. Run with
`mvn test` at the root or `mvn -pl <module> -am test` per module.

**OWASP Dependency-Check** is wired into the parent POM's `verify` phase
(`org.owasp:dependency-check-maven`, `failBuildOnCVSS=9`). Two important gotchas:
- **Never run `mvn install` (only `mvn install` — `package` is fine) inside a Docker build without
  `-Ddependency-check.skip=true`.** `install` runs through `verify`, where this plugin is bound;
  without the skip flag, every image build also tries to download the full NVD dataset inside the
  ephemeral, uncached build container - this is what actually caused hours of apparently-stalled
  Docker builds during this pass, not a Docker or network problem. The root `Dockerfile`'s build RUN
  line carries this flag now; don't remove it.
- The **Sonatype OSS Index Analyzer** (a supplementary check beyond NVD) now requires
  authentication for what used to be an anonymous API - it's disabled in the plugin config
  (`ossindexAnalyzerEnabled: false`) rather than worked around with credentials, since NVD alone is
  sufficient for this project's scope.
- This pass caught and fixed two real **Critical** (CVSS ≥ 9) findings, not just Highs: Couchbase's
  client vendors (shades) `netty-transport` *inside* `core-io-3.8.3.jar` itself - not a normal
  transitive dependency, so not fixable via a `dependencyManagement` override of
  `io.netty:netty-transport` directly. Fixed by bumping `com.couchbase.client:java-client` in the
  root pom's `dependencyManagement` - one minor version (3.9.2) wasn't enough (its vendored netty
  still carried the CVEs), 3.12.1 (latest available) was required. Also bumped
  `tomcat.version` to `10.1.57` via Spring Boot's own documented override property (a same-line
  patch, not an independent library swap). Both changes were verified against the *real* deployed
  cluster (order-service's Couchbase transactions still work, full stack still settles orders end
  to end), not just left as an unverified version bump - see SECURITY.md for the full record.

**NetworkPolicy enforcement**: minikube's default CNI doesn't implement `NetworkPolicy` at all, so
through Day 7-8 these were structurally correct but inert. This pass switched CNI specifically to
make that layer real:
- **Calico was tried first and abandoned.** It exhibited a SNAT/hairpin-NAT interaction with
  kube-proxy (Service-routed traffic having its source IP rewritten to the *node's* IP before
  reaching the destination pod) that broke Linkerd's mTLS identity resolution entirely for any
  Service-routed call - Linkerd's inbound proxy saw the node IP, not a recognized meshed pod
  identity, and denied everything. Two targeted Calico config changes (`natOutgoing: false`,
  `ipipMode: Never`) were tested and didn't resolve it; root-causing further would have meant deep
  iptables tracing with no guaranteed payoff. **Cilium (`minikube start --cni=cilium`) doesn't
  exhibit this** and is what the cluster actually runs today.
- **Verifying "is it actually enforced" needs a protocol-aware probe, not a bare TCP check.** `nc -z`
  against a mesh-protected port will report "connected" even when the request is ultimately denied,
  because Linkerd's inbound proxy always accepts the raw TCP handshake before evaluating
  authorization - only a real request (`curl`, checking the actual HTTP status/response) proves
  anything. This cost real debugging time before the distinction was clear.
- **Enforcement being real immediately surfaced a genuine, previously-invisible gap**:
  `networkpolicies.yaml` covered every steady-state service relationship but never accounted for
  the one-shot `couchbase-init` Job, whose pod only carries the Job controller's auto-added
  `job-name` label, not an `app` label matching any existing rule. Fixed by adding an explicit
  `app: couchbase-init` label to the Job's pod template (`couchbase-init-job.yaml`) plus a
  dedicated `allow-couchbase-init-to-couchbase` policy, kept separate from the existing
  order-service-to-couchbase-and-es rule since couchbase-init has no business reaching
  Elasticsearch. This class of gap is structurally undetectable while NetworkPolicy is inert -
  concrete evidence for doing this enforcement pass at all, not just a checkbox exercise.

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
