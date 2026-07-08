package com.orderplatform.order.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record OrderLineRequest(

        @NotBlank
        @Size(max = 64)
        String sku,

        @Positive
        int quantity
) {
}
