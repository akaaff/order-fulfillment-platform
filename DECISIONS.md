# Architecture Decision Records

Lightweight ADRs for the non-obvious design choices in this project — the ones with a real
trade-off, not the ones with an obviously-correct answer. Each entry: context, decision,
consequences. Written after the fact (not literally decided in this order during the build), but
each reflects a real fork in the road that came up.

---

## ADR 1: Kafka for the order fulfillment flow, not synchronous REST calls

**Context**: order intake → inventory reservation → fulfillment notification could have been wired
as a chain of synchronous REST calls (order-service calls inventory-service, waits for the
response, then calls notification-service). That's simpler to trace end-to-end and has no
eventual-consistency window.

**Decision**: use Kafka events (`order.created` → `inventory.reserved`/`inventory.rejected` →
`order.confirmed`/`order.cancelled` → notification) instead.

**Consequences**:
- Order-service and inventory-service don't need to be simultaneously available — a synchronous
  chain means inventory-service's downtime is order-service's downtime too. With Kafka, an order
  can be accepted even if inventory-service is briefly down; it just settles once the consumer
  catches up.
- Adding notification-service (or any future consumer — a fraud-check service, an analytics
  pipeline) doesn't require order-service or inventory-service to change at all; they just add a
  new consumer group on the same topics. A synchronous chain would need every new step wired into
  the caller.
- The cost: no single request/response gives you the final order state — you get `PENDING`
  immediately, and the real outcome arrives asynchronously. The API and the web client both have
  to account for this (polling/re-fetching the order, not just reading the POST response).
- This also means failures are harder to trace end-to-end by hand — a stuck order requires
  checking Kafka consumer lag / the outbox tables, not just reading one stack trace. Structured
  logging with correlation IDs (propagated via Kafka headers) is what makes this tractable rather
  than a black box.
- **Alternative considered**: synchronous REST chain with a saga/compensating-transaction pattern
  for partial failures. Rejected for this scope — it recreates most of the complexity of the event
  approach (compensating actions instead of consumers) without the decoupling benefit.

---

## ADR 2: Polyglot persistence — Couchbase (orders), Postgres (inventory), Elasticsearch (search)

**Context**: a simpler build would put everything in one database (e.g. all-Postgres, or
all-Couchbase). This project deliberately uses three different stores for three different
services.

**Decision**: Couchbase for order documents (flexible schema, native multi-document ACID
transactions used for the outbox write), Postgres for inventory (real transactional guarantees and
row-level optimistic locking are the actual requirement — stock counts must never oversell, and a
relational `@Version` column is the natural fit), Elasticsearch for order search/audit (a
read-heavy, filter-heavy access pattern that's a poor fit for either of the other two).

**Consequences**:
- Each store is used for what it's actually good at, rather than forcing one engine to do
  everything adequately. The trade-off is operational: three different backup/monitoring/failure
  stories instead of one, and — concretely, hit during Day 7 — three different sets of Kubernetes
  manifests, probes, and resource tuning to get right on a single-node cluster, which was the
  single biggest source of debugging time in the whole build.
- It also means genuinely comparing two outbox implementations side by side (see ADR 3), which
  wouldn't have been possible with one datastore.
- **Alternative considered**: Postgres for everything (orders as JSONB, inventory as normal rows,
  and either Postgres full-text search or a bolted-on `pg_trgm` index instead of Elasticsearch).
  This is a legitimate, lower-operational-cost choice for a real team without a specific reason to
  run three engines — it's called out here because "just use Postgres for all of it" is often the
  right call, and this project's polyglot choice is a deliberate demonstration of judgment across
  stores, not a claim that polyglot is always correct.

---

## ADR 3: Transactional outbox pattern, implemented two different ways on purpose

**Context**: both order-service and inventory-service need to atomically (a) change their own
state and (b) guarantee a Kafka event eventually gets published for that change — the classic
dual-write problem (DB commit succeeds, Kafka publish fails or vice versa, and the two diverge).

**Decision**: both services use the transactional outbox pattern, but implemented two different
ways: inventory-service uses the classic relational approach (an `outbox_event` table written in
the same DB transaction as the stock update, relayed to Kafka by a separate poller bean); order-
service uses Couchbase's native multi-document ACID transactions to write the `Order` document and
an outbox document atomically in one transaction, relayed by a scheduled job that queries
unpublished documents via N1QL.

**Consequences**:
- Same problem, two solutions, both correct — comparing them is useful precisely because they look
  different: the relational version needs a separate table and a pessimistic-lock query
  (`findByIdForUpdate`) to avoid two poller instances double-publishing; the Couchbase version
  gets the atomicity for free from the transaction API but pays for it with the SDK's own
  connection/topology overhead (documented in the Linkerd section of CLAUDE.md — the SDK needs the
  mgmt port in addition to the KV port, which wasn't obvious until debugging a mesh
  AuthorizationPolicy that only allowed the latter).
- The relational version also carries a sharp edge worth calling out: `OutboxRelay` and
  `OutboxPoller` are deliberately two separate beans rather than one class with the scheduled
  method calling a `@Transactional` method on itself — self-invocation bypasses Spring's
  transactional proxy entirely and silently runs with no transaction, breaking the pessimistic
  lock. This already broke once during the build and is documented in CLAUDE.md specifically so
  it doesn't get "simplified" back into one class later.
- **Alternative considered**: Debezium / Kafka Connect CDC (tail the DB's write-ahead log instead
  of a polling relay). This removes the poller entirely and eliminates publish latency, but adds a
  whole CDC connector deployment (another moving part on an already resource-constrained minikube
  node) for a problem the two implementations here already solve correctly at demo scale.

---

## ADR 4: Accepting eventual consistency in the order search index

**Context**: `OrderSearchIndexer` consumes the same lifecycle events order-service publishes and
maintains a separate Elasticsearch read model. This means `GET /orders/search` can return
stale data for a brief window after an order's status changes — the search API and the
authoritative `GET /orders/{id}` API are not guaranteed to agree at any given instant.

**Decision**: accept this window rather than making search synchronously consistent (e.g. reading
through to Couchbase for the "real" status on every search hit, or writing directly to
Elasticsearch from request-handling code instead of via a consumer).

**Consequences**:
- Search stays fast and doesn't add Elasticsearch to order-service's write-path latency or
  failure surface — an Elasticsearch outage means search results go stale, not that placing an
  order starts failing.
- The real cost showed up directly during Day 7/8 testing: polling `/orders/search` within a
  couple of seconds of creating an order sometimes returned the pre-confirmation `PENDING` status
  even though `GET /orders/{id}` already showed `CONFIRMED` — a genuine, reproducible consequence
  of this decision, not a bug. Any client of the search endpoint (the web client, a future
  reporting integration) has to tolerate this, and it's worth calling out explicitly rather than
  letting it look like unreliability.
- **Alternative considered**: synchronous dual-write (write Couchbase and Elasticsearch in the same
  request). Rejected — it reintroduces the dual-write problem ADR 3 exists to avoid, just for a
  second store instead of Kafka.

---

## ADR 5: Local Ollama for the AI support agent, not a cloud LLM API

**Context**: the AI support agent needs a tool-calling-capable model to answer "where's my order"
questions. A cloud API (Anthropic, OpenAI, etc.) would likely be more capable and require no local
GPU/CPU budget on the demo machine.

**Decision**: use a local Ollama model (`llama3.2:3b`) reached from inside the cluster via
`host.minikube.internal`, instead of an external API.

**Consequences**:
- No API key management, no per-request cost, and the whole demo runs offline/air-gapped after
  initial setup — genuinely useful properties for a portfolio artifact a reviewer might run
  themselves without wanting to provision their own API key.
- The trade-off is real: a 3B local model is meaningfully less reliable at tool-calling than a
  frontier cloud model, and this surfaced directly — the AI assistant occasionally responded with
  a vague, non-answer instead of clearly stating an order's status (observed during Day 7/8
  verification), which a larger model would likely handle better. `GuardedChatCaller`'s circuit
  breaker and timeout exist specifically because a local model's latency and failure
  characteristics are worse and more variable than a well-provisioned cloud API's.
- **Alternative considered**: a cloud LLM API with the same tool-calling contract (`OrderTools`
  wouldn't need to change at all — it's already provider-agnostic through Spring AI's abstraction).
  This would be the better choice for a production system where answer quality matters more than
  offline capability or per-request cost; noted here so the trade-off is explicit, not implied to
  be a universal win for local models.

---

## ADR 6: JWT validated centrally at the gateway, with defense-in-depth re-validation downstream

**Context**: the gateway could be the sole point of authentication (validate the JWT once, then
forward requests to backends over a trusted internal network with no further auth check), or every
service could independently validate every inbound JWT.

**Decision**: both — api-gateway validates and issues tokens; order-service and ai-support-agent
*also* independently re-validate the same JWT against the same public key, rather than trusting a
gateway-forwarded identity header.

**Consequences**:
- A misconfigured or bypassed gateway (or, concretely, a caller that reaches a backend directly
  rather than through the gateway) is not a total authentication bypass — each service still
  enforces its own check. This is the same config repeated three times, which is cheap.
- The cost is genuinely paying for JWT parsing/signature verification more than once per request,
  and keeping the public key deployment (the `jwt-keys` Secret, mounted into three separate
  services) in sync — a key rotation touches every consumer, not just the gateway.
- This decision compounds with the Linkerd mTLS layer added in Day 8: mTLS proves *which service*
  is calling (workload identity), while the JWT re-validation proves *which customer* the call is
  acting on behalf of — the two are answering different questions, and neither substitutes for the
  other. A service reached over a legitimately meshed, mTLS-authenticated connection still has no
  business honoring a forged or expired customer JWT.
- **Alternative considered**: gateway-only validation, with downstream services trusting a
  `X-Authenticated-Customer-Id` header the gateway sets after validating the JWT. Simpler and
  cheaper per-request, but makes every downstream service's authorization only as strong as network
  trust in the gateway being the only path in — exactly the assumption Linkerd's AuthorizationPolicy
  work in Day 8 was written to avoid relying on implicitly.

---

## ADR 7: Linkerd for mTLS, not Istio, and not NetworkPolicies alone

**Context**: three ways to secure pod-to-pod traffic were on the table: NetworkPolicies only (L3/L4
allow-lists, no encryption or identity), a full-featured service mesh (Istio, Envoy-based, wide
feature set), or a lighter mesh (Linkerd, Rust-based data plane, narrower feature set focused on
mTLS/observability/basic traffic policy).

**Decision**: Linkerd, layered *on top of* NetworkPolicies rather than instead of them.

**Consequences**:
- NetworkPolicies alone would have been free (no extra control plane, no sidecars) but give no
  encryption and no caller identity — and, concretely, minikube's default CNI doesn't even enforce
  them, so on this cluster they're currently just documented intent, not an active control (see
  CLAUDE.md). Linkerd's AuthorizationPolicy became the actually-enforced layer as a direct result.
- Linkerd's sidecar footprint turned out to be small in practice (~3-6MiB RSS per proxy, measured
  directly during Day 8) — a real, load-bearing fact on a single-node minikube cluster that had
  already hit genuine OOM limits in Day 7, and part of why Linkerd was chosen over Istio's
  heavier Envoy-based data plane and wider default resource footprint.
- The real cost wasn't the mesh itself, but everything downstream of adding it: every stateful
  workload using `emptyDir` storage (Couchbase, Postgres, Kafka) lost its data on the pod
  recreation the sidecar injection required, needing re-bootstrap steps each time (documented in
  CLAUDE.md); binary protocols (Kafka, Postgres, Couchbase's KV port) needed explicit
  opaque-ports configuration in two separate places to avoid protocol-sniffing overhead; and
  making `AuthorizationPolicy` meaningful required giving every workload its own ServiceAccount
  (everything had silently shared `default`, which would have made identity-based rules
  meaningless). None of this is Linkerd-specific pain — Istio's AuthorizationPolicy has the same
  ServiceAccount-identity prerequisite — but it's real cost attributable to adding *a* mesh, not
  nothing.
- **Alternative considered**: Istio. More mature ecosystem and a broader feature set (traffic
  splitting, richer telemetry via Envoy) that this project doesn't need — the deciding factor here
  was reliability on a resource-constrained single-node cluster over feature breadth, which
  is itself a defensible trade-off in the other direction on a larger/multi-node cluster with more
  headroom.
