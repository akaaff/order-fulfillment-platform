package com.orderplatform.aiagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** No customerId field - the caller's identity comes from the validated JWT (see AssistantController). */
public record AskRequest(

        @NotBlank
        @Size(max = 1000)
        String question
) {
}
