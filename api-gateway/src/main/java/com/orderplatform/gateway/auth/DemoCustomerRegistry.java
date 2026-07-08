package com.orderplatform.gateway.auth;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Stands in for a real identity provider - there is no password check
 * anywhere in this system. /auth/login only issues a token for one of these
 * fixed demo customer ids, so at least an arbitrary string can't mint a
 * token for an identity that was never "created".
 */
@Component
public class DemoCustomerRegistry {

    private static final Set<String> DEMO_CUSTOMER_IDS = Set.of(
            "cust-001", "cust-002", "cust-003", "cust-004", "cust-005"
    );

    public boolean isValidCustomer(String customerId) {
        return DEMO_CUSTOMER_IDS.contains(customerId);
    }
}
