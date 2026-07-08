package com.orderplatform.order.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Inbound payload for {@code POST /orders}; validated at this boundary before
 * a domain Order exists. Deliberately has no customerId field - the caller's
 * identity comes from the validated JWT (see OrderController), never from
 * the request body.
 */
public record CreateOrderRequest(

        @NotEmpty
        @Size(max = 50)
        @Valid
        List<OrderLineRequest> lines
) {
}
