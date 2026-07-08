package com.orderplatform.gateway.ratelimit;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed per-second request budget per client IP, enforced in-memory - simple
 * and sufficient for a single-instance demo gateway, not something a
 * production multi-instance deployment could rely on (each instance would
 * enforce its own independent budget). A Redis-backed limiter shared across
 * instances is the production-scale answer; noted rather than built here.
 * The per-IP limiter map also grows unboundedly for the life of the process -
 * acceptable for a demo, not for a long-lived production gateway.
 */
@Component
public class RateLimitingGlobalFilter implements GlobalFilter, Ordered {

    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();
    private final RateLimiterConfig config;

    public RateLimitingGlobalFilter(@Value("${orderplatform.rate-limit.requests-per-second}") int requestsPerSecond) {
        this.config = RateLimiterConfig.custom()
                .limitForPeriod(requestsPerSecond)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String key = clientKey(exchange);
        RateLimiter limiter = limiters.computeIfAbsent(key, k -> RateLimiter.of(k, config));

        if (limiter.acquirePermission()) {
            return chain.filter(exchange);
        }

        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        return exchange.getResponse().setComplete();
    }

    private String clientKey(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
