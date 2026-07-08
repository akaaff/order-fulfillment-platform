package com.orderplatform.order.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** A single requested sku/quantity pair as submitted by a client, before it becomes an OrderLine event field. */
public record OrderLineRequest(

        @NotBlank
        @Size(max = 64)
        String sku,

        @Positive
        int quantity
) {
}
