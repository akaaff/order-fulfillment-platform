package com.orderplatform.gateway.auth;

import java.time.Instant;

public record LoginResponse(String token, String customerId, Instant expiresAt) {
}
