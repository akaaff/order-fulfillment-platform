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

RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests install

# Shared non-root runtime base - each service stage below inherits this
# rather than repeating the user setup.
FROM eclipse-temurin:21-jre-alpine-3.22 AS runtime-base
RUN addgroup -S app && adduser -S app -G app
USER app
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
