package com.orderplatform.aiagent.api;

import com.orderplatform.aiagent.api.dto.AskRequest;
import com.orderplatform.aiagent.api.dto.AskResponse;
import com.orderplatform.aiagent.service.AiSupportAgentService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's identity comes from the validated JWT's subject claim, never
 * from the request body - this is what makes OrderTools' per-customer
 * scoping (see AiSupportAgentService/OrderTools) actually trustworthy rather
 * than just a self-reported field.
 */
@RestController
@RequestMapping("/assistant")
public class AssistantController {

    private final AiSupportAgentService agentService;

    public AssistantController(AiSupportAgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/ask")
    public AskResponse ask(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AskRequest request) {
        String answer = agentService.ask(jwt.getSubject(), jwt.getTokenValue(), request.question());
        return new AskResponse(answer);
    }
}
