# syntax=docker/dockerfile:1
#
# One shared build stage compiles the whole Maven reactor once; each service
# gets its own thin runtime target that just copies its own jar out of that
# shared build. Avoids recompiling events-common (and re-resolving shared
# dependencies) once per service the way five independent Dockerfiles would.
# Build a specific service with: docker build --target <service-name> -t <tag> .
# (build context must be the repo root - every module's source is needed to
# satisfy the multi-module reactor, even though only one module's jar is used).

FROM maven:3.9.16-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

COPY pom.xml .
COPY events-common/pom.xml events-common/
COPY api-gateway/pom.xml api-gateway/
COPY order-service/pom.xml order-service/
COPY inventory-service/pom.xml inventory-service/
COPY notification-service/pom.xml notification-service/
COPY ai-support-agent/pom.xml ai-support-agent/

COPY events-common/src events-common/src
COPY api-gateway/src api-gateway/src
COPY order-service/src order-service/src
COPY inventory-service/src inventory-service/src
COPY notification-service/src notification-service/src
COPY ai-support-agent/src ai-support-agent/src

# dependency-check.skip: `install` runs Maven's `verify` phase too, which is
# where dependency-check-maven is bound (see the root pom.xml) - without this
# flag, every image build also tries to run a full NVD-backed CVE scan
# inside the ephemeral build container (no cached database, no resilience to
# NVD's rate limiting), which is what actually caused this Dockerfile's build
# to stall/fail for hours during the security pass that added the plugin.
# Dependency scanning belongs in CI/local `mvn verify`, not baked into every
# image build.
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests -Ddependency-check.skip=true install

# Shared non-root runtime base - each service stage below inherits this
# rather than repeating the user setup. Explicit numeric uid/gid (10001), not
# just a named user: Kubernetes's securityContext.runAsNonRoot: true (set on
# every app Deployment - see CLAUDE.md's security-hardening section) can't
# verify a non-numeric USER against the image and fails admission with
# CreateContainerConfigError otherwise ("cannot verify user is non-root").
FROM eclipse-temurin:21-jre-alpine-3.22 AS runtime-base
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
USER 10001:10001
WORKDIR /app

FROM runtime-base AS api-gateway
COPY --from=build /workspace/api-gateway/target/api-gateway-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime-base AS order-service
COPY --from=build /workspace/order-service/target/order-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime-base AS inventory-service
COPY --from=build /workspace/inventory-service/target/inventory-service-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime-base AS notification-service
COPY --from=build /workspace/notification-service/target/notification-service-*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime-base AS ai-support-agent
COPY --from=build /workspace/ai-support-agent/target/ai-support-agent-*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
