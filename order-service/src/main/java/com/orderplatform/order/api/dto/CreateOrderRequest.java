package com.orderplatform.order.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Inbound payload for {@code POST /orders}; validated at this boundary before a domain Order exists. */
public record CreateOrderRequest(

        @NotBlank
        @Size(max = 128)
        String customerId,

        @NotEmpty
        @Size(max = 50)
        @Valid
        List<OrderLineRequest> lines
) {
}
