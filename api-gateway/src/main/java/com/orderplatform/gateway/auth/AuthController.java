package com.orderplatform.gateway.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Deliberately not proxied through a gateway route (no /api prefix) - this
 * is served by the gateway itself, the one endpoint on this service that's
 * public. There is no real identity check here beyond "is this one of the
 * seeded demo customer ids" - see DemoCustomerRegistry.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JwtIssuer jwtIssuer;
    private final DemoCustomerRegistry demoCustomerRegistry;

    public AuthController(JwtIssuer jwtIssuer, DemoCustomerRegistry demoCustomerRegistry) {
        this.jwtIssuer = jwtIssuer;
        this.demoCustomerRegistry = demoCustomerRegistry;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        if (request.customerId() == null || request.customerId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (!demoCustomerRegistry.isValidCustomer(request.customerId())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String token = jwtIssuer.issueToken(request.customerId());
        Instant expiresAt = Instant.now().plus(jwtIssuer.ttl());

        return ResponseEntity.ok(new LoginResponse(token, request.customerId(), expiresAt));
    }
}
