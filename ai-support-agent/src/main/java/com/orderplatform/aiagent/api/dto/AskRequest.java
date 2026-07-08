package com.orderplatform.aiagent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * customerId is a stand-in for a JWT claim until Day 5 wires up gateway auth -
 * see AssistantController. Treat this endpoint as not yet safe to expose
 * publicly: right now any caller can claim to be any customerId.
 */
public record AskRequest(

        @NotBlank
        @Size(max = 128)
        String customerId,

        @NotBlank
        @Size(max = 1000)
        String question
) {
}
