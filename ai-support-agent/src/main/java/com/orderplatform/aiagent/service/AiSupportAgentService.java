package com.orderplatform.aiagent.service;

import com.orderplatform.aiagent.chat.GuardedChatCaller;
import com.orderplatform.aiagent.client.OrderServiceClient;
import com.orderplatform.aiagent.tools.OrderTools;
import org.springframework.stereotype.Service;

@Service
public class AiSupportAgentService {

    private static final String SYSTEM_PROMPT = """
            You are a customer support assistant for an online store. You can only
            answer questions about the caller's own orders, using the tools provided -
            you have no other knowledge of orders, inventory, shipping, or policies,
            and must never invent information. If a tool returns no result, say so
            plainly instead of guessing. Politely decline anything unrelated to the
            caller's own orders. You can only look up order status and search orders -
            you cannot cancel, refund, modify, or take any action on an order, and must
            never suggest that you can.
            """;

    private final GuardedChatCaller chatCaller;
    private final OrderServiceClient orderServiceClient;

    public AiSupportAgentService(GuardedChatCaller chatCaller, OrderServiceClient orderServiceClient) {
        this.chatCaller = chatCaller;
        this.orderServiceClient = orderServiceClient;
    }

    /**
     * customerId must already be a trusted, server-resolved identity by the
     * time it reaches this method - see AssistantController for the current
     * (temporary, pre-Day-5) caveat on how that trust is established.
     */
    public String ask(String customerId, String question) {
        OrderTools tools = new OrderTools(customerId, orderServiceClient);
        return chatCaller.call(SYSTEM_PROMPT, question, tools);
    }
}
