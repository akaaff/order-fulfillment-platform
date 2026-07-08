package com.orderplatform.aiagent.tools;

import com.orderplatform.aiagent.client.OrderSearchResult;
import com.orderplatform.aiagent.client.OrderServiceClient;
import com.orderplatform.aiagent.client.OrderView;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only tools exposed to the model, scoped to one customer for the
 * lifetime of a single request - a new instance is built per request with
 * customerId resolved server-side (see AiSupportAgentService), never as a
 * model-supplied argument. This is the actual security boundary: even a
 * successful prompt injection can only ever cause these methods to be called
 * with a different orderId/status, never a different customerId, so one
 * customer's conversation can't be tricked into leaking another customer's
 * orders. getOrderStatus double-checks ownership itself as a second layer,
 * in case a future refactor ever adds a tool that skips searchMyOrders'
 * customerId-scoped query entirely.
 */
public class OrderTools {

    private final String customerId;
    private final OrderServiceClient orderServiceClient;

    public OrderTools(String customerId, OrderServiceClient orderServiceClient) {
        this.customerId = customerId;
        this.orderServiceClient = orderServiceClient;
    }

    @Tool(description = "Get the current status of one of the caller's own orders by its order ID. "
            + "Returns a not-found message if the order does not exist or does not belong to the caller.")
    public String getOrderStatus(@ToolParam(description = "The order ID (UUID) to look up") String orderId) {
        return orderServiceClient.findById(orderId)
                .filter(order -> customerId.equals(order.customerId()))
                .map(this::describe)
                .orElse("No order found with that ID for this customer.");
    }

    @Tool(description = "Search the caller's own orders, optionally filtered by status "
            + "(PENDING, CONFIRMED, or CANCELLED). Leave status blank to list all of the caller's orders.")
    public String searchMyOrders(
            @ToolParam(description = "Optional status filter: PENDING, CONFIRMED, or CANCELLED. Blank for all.", required = false)
            String status
    ) {
        List<OrderSearchResult> results = orderServiceClient.search(customerId, status);
        if (results.isEmpty()) {
            return "No orders found.";
        }
        return results.stream()
                .map(this::describe)
                .collect(Collectors.joining("\n"));
    }

    private String describe(OrderView order) {
        String reason = order.cancellationReason() != null ? " (reason: " + order.cancellationReason() + ")" : "";
        return "Order " + order.orderId() + " is " + order.status() + reason;
    }

    private String describe(OrderSearchResult order) {
        String reason = order.cancellationReason() != null ? " (reason: " + order.cancellationReason() + ")" : "";
        return "Order " + order.orderId() + " is " + order.status() + reason;
    }
}
