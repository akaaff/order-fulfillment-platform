# Security Posture

A short, honest account of what's actually enforced in this project, what's scanned, and what's
knowingly deferred — written for a reviewer, not a compliance checklist. See `DECISIONS.md` for the
reasoning behind some of these choices (ADR 6 and 7 in particular).

## Threat model, briefly

This is a portfolio demo, not a production system handling real customer data or money. The threat
model that shaped these decisions: a curious reviewer running the demo locally, and the general
discipline of not shipping anything that would be an obvious finding in a real security review
(hardcoded secrets, wide-open network policy, root containers, unvalidated input). It does not
attempt to defend against a sophisticated attacker with cluster-admin access, supply-chain
compromise of a base image, or physical access to the host.

## What's enforced today

- **AuthN/AuthZ**: RS256 JWTs, issued only by api-gateway, re-validated independently by
  order-service and ai-support-agent (defense in depth — see DECISIONS.md ADR 6). No route lets a
  client *claim* an identity; the caller's identity always comes from the JWT `sub` claim.
- **mTLS + identity-based authorization**: every pod in `order-platform` is meshed via Linkerd
  (namespace-level auto-injection). Every workload has its own dedicated ServiceAccount (not the
  shared `default`), and `infra/k8s/base/authorization-policies.yaml` restricts each backend/
  datastore to only the specific caller identities that legitimately need it. Verified via
  `linkerd check --proxy` and by reading each proxy's own metrics for `tls="true"` with a verified
  `server_id`. See CLAUDE.md's Linkerd section for the full list of gotchas hit getting here.
- **NetworkPolicies, now actually enforced**: minikube originally ran the default "bridge" CNI,
  which doesn't implement `NetworkPolicy` at all — the policies in
  `infra/k8s/base/networkpolicies.yaml` were structurally correct but inert. The cluster was
  recreated with `--cni=cilium` specifically to make this layer real, not just documented intent
  (Calico was tried first; both it and Cilium run kube-proxy alongside them by default on minikube,
  and Calico specifically hit an unresolved SNAT/hairpin-NAT interaction that broke Linkerd's mTLS
  identity resolution for Service-routed traffic — reverted in favor of Cilium, which didn't
  exhibit it). Enforcement is verified two ways, not just asserted: (1) a scratch pod with no
  matching label/ServiceAccount gets a protocol-aware 504 from Linkerd's own proxy trying to reach a
  protected backend (raw `nc -z` TCP checks are **not** valid here - the mesh's inbound proxy always
  accepts the TCP handshake before deciding on authorization, so only a real protocol-level probe
  like `curl` proves anything); (2) enabling real enforcement immediately surfaced a genuine,
  previously-invisible gap - `networkpolicies.yaml` covered every steady-state service relationship
  but never accounted for the one-shot `couchbase-init` Job's traffic, since its pod only carries
  the Job controller's auto-added `job-name` label, not an `app` label matching any existing rule.
  Fixed by adding an explicit `app: couchbase-init` label to the Job's pod template and a dedicated
  `allow-couchbase-init-to-couchbase` policy - this gap could never have been found under the
  previous inert-CNI setup, which is itself the argument for doing this enforcement pass at all.
- **Rate limiting**: per-IP fixed budget at api-gateway (Resilience4j `RateLimiter`), verified live
  under concurrent load (40 requests against a 20/sec budget produced 429s once exceeded).
- **CORS**: locked to a single configurable origin, not a wildcard; the preflight-`OPTIONS`-needs-
  explicit-permitAll gotcha (Spring Security's `.cors()` doesn't imply this) is documented in
  CLAUDE.md and was caught before it shipped as a real bug.
- **Non-root containers, explicit and K8s-enforced, not just Dockerfile-implicit**: every
  self-built image (`api-gateway`, `order-service`, `inventory-service`, `notification-service`,
  `ai-support-agent`, `web-client`) runs as a non-root user, and every one of their Deployments sets
  `securityContext.runAsNonRoot: true` plus, per-container, `allowPrivilegeEscalation: false`,
  `readOnlyRootFilesystem: true`, and `capabilities.drop: ["ALL"]`. This was **not** already true
  before this pass — a Trivy config scan of `infra/k8s/` found every single Deployment relying on
  the default (unset) securityContext, meaning Kubernetes itself wasn't enforcing anything beyond
  whatever the image happened to do. `web-client`'s Dockerfile in particular had no `USER`
  directive at all (fixed by switching its base image to `nginxinc/nginx-unprivileged`, which also
  required moving its listen port from 80 to 8080).
- **Secrets**: nothing committed to git — JWT keys and datastore credentials are created as K8s
  Secrets imperatively (`infra/k8s/create-secrets.sh`) from gitignored local files, never as static
  YAML with embedded values. Confirmed via `trivy fs --scanners secret` against the whole repo (the
  one hit, `infra/keys/jwt-private.pem`, is verified gitignored and absent from all git history —
  see the verification note below) and via `git log`/`git ls-files` directly.
- **Injection defense**: parameterized N1QL (Couchbase) and JPA/parameterized SQL (Postgres)
  everywhere — no string-built queries.
- **Input validation**: Jakarta Bean Validation at every REST boundary; `GlobalExceptionHandler` in
  each service returns field-level messages on failure and never leaks a raw exception message or
  stack trace on an unhandled 500 (this is directly unit-tested — see the test suite).

## What's scanned, and the results

- **Dependency vulnerabilities** (OWASP Dependency-Check, `org.owasp:dependency-check-maven`,
  wired into the parent POM's `verify` phase, `failBuildOnCVSS=9` so only Critical-severity findings
  with no available fix would actually fail a build): see the plugin's HTML/JSON report under each
  module's `target/dependency-check-report.*` after running `mvn verify`. The Sonatype OSS Index
  Analyzer (a supplementary check beyond NVD) is disabled in the plugin config - it now returns 401
  Unauthorized for unauthenticated requests, which would otherwise hard-fail every build. **Two real
  Critical (CVSS ≥ 9) findings were caught and fixed by this pass, not just Highs**: `netty-transport`
  vendored *inside* `core-io-3.8.3.jar` (Couchbase's client - not a normal transitive dependency, so
  not fixable via a `dependencyManagement` override of `io.netty:netty-transport` directly; required
  bumping `com.couchbase.client:java-client` itself, and only the latest available version (3.12.1)
  actually cleared the CVEs - one minor bump to 3.9.2 still carried them), and `tomcat-embed-core`
  10.1.55 (fixed via Spring Boot's own documented `tomcat.version` override property, a same-line
  patch bump to 10.1.57). Both fixes were verified two ways: the existing unit test suite still
  passes against the new Couchbase client, and the full stack was redeployed and re-verified
  end-to-end (login → order → Kafka settlement → search → AI assistant) against the bumped
  dependencies, not just left as an untested version bump. **Do not run `mvn install` (not just
  `test`) inside a Docker build without `-Ddependency-check.skip=true`** - `install` triggers
  Maven's `verify` phase, which is where this plugin is bound; without the skip flag, every image
  build also tries to download the full NVD dataset inside the ephemeral, uncached build container,
  which is slow-to-the-point-of-failure and unrelated to what an image build should be doing (see
  the root `Dockerfile`'s comment on this exact RUN line).
- **Container image vulnerabilities** (Trivy, portable install, `trivy image --severity
  HIGH,CRITICAL` against all 6 built images — reports under `security-reports/`): no CRITICAL
  findings on any image. All 6 share the same handful of Alpine-base-layer HIGH findings
  (`libexpat`, `p11-kit`) with fixed versions already available upstream — these track the pinned
  `eclipse-temurin:21-jre-alpine-3.22` base and go away on the next Alpine patch bump, not something
  to hand-patch in this Dockerfile. `order-service` and `ai-support-agent` additionally surface a
  handful of HIGH findings in their own dependency trees (`jackson-databind`, `netty-*`) — real,
  fixed-upstream CVEs worth tracking via a Dependabot/Renovate-style bump, not exploitable through
  this app's actual usage of those libraries (no polymorphic deserialization of untrusted input, no
  raw HTTP/2 exposure), but not waved away either.
- **IaC misconfiguration** (`trivy config` against the whole repo): this is what surfaced the
  missing-securityContext and missing-Dockerfile-USER findings described above, both since fixed.
- **Secrets in the repo** (`trivy fs --scanners secret`): one hit, the local dev JWT private key —
  confirmed gitignored and never committed (`git check-ignore`, `git ls-files`, `git log --all`
  all confirm this explicitly, not just "probably fine").

## Known, deliberate gaps

- **Third-party infra images (Postgres, Couchbase, Kafka, Elasticsearch) are not given the same
  full container hardening as the six self-built services.** They get `allowPrivilegeEscalation:
  false` (safe — it only blocks *gaining* new privileges, not an entrypoint's own voluntary
  privilege drop) but deliberately not `runAsNonRoot`, `readOnlyRootFilesystem`, or capability
  dropping. Postgres's and Couchbase's official images need to run as root briefly on first boot to
  initialize a fresh data directory (this project's storage is `emptyDir`, so every pod recreation
  is a fresh directory) before dropping privilege internally — asserting `runAsNonRoot: true`
  without first verifying each image's actual entrypoint behavior risks a confident-looking fix that
  quietly breaks bootstrapping. This is flagged as a follow-up requiring per-image verification, not
  silently skipped.
- **TLS termination at the Ingress is a minikube dev cert**, not a real certificate — fine for a
  local demo, explicitly not a production pattern.
- **No secrets manager / sealed-secrets / external-secrets**: K8s `Secret` objects are base64, not
  encrypted at rest by default. Fine for a demo cluster; called out so it doesn't read as "we think
  base64 is encryption."
- **Elasticsearch runs with `xpack.security.enabled: false`** — no auth on the search index itself,
  reachable only because NetworkPolicy + Linkerd AuthorizationPolicy both restrict which pods can
  reach it at all. Acceptable for this demo's blast radius, not how a production ES cluster holding
  real data should be configured.
