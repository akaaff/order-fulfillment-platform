package com.orderplatform.gateway.auth;

/** No password field on purpose - see DemoCustomerRegistry's Javadoc. */
public record LoginRequest(String customerId) {
}
