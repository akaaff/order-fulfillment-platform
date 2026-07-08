package com.orderplatform.aiagent.api;

import com.orderplatform.aiagent.api.dto.AskRequest;
import com.orderplatform.aiagent.api.dto.AskResponse;
import com.orderplatform.aiagent.service.AiSupportAgentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEMPORARY (pre-Day-5): customerId comes from the request body, not a
 * validated JWT claim, because gateway-issued auth doesn't exist yet. That
 * means this endpoint currently trusts the caller's self-reported identity,
 * which is exactly what the tool design (OrderTools) assumes never happens -
 * do not deploy this past localhost until Day 5 replaces the body field with
 * an authenticated claim resolved here instead.
 */
@RestController
@RequestMapping("/assistant")
public class AssistantController {

    private final AiSupportAgentService agentService;

    public AssistantController(AiSupportAgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        String answer = agentService.ask(request.customerId(), request.question());
        return new AskResponse(answer);
    }
}
