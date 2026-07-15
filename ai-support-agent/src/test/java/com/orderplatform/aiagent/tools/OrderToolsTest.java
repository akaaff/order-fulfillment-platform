package com.orderplatform.aiagent.tools;

import com.orderplatform.aiagent.client.OrderSearchResult;
import com.orderplatform.aiagent.client.OrderServiceClient;
import com.orderplatform.aiagent.client.OrderView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderTools}, the actual security boundary of this
 * service (see the class-level Javadoc on OrderTools and the "ai-support-agent"
 * section of the repo's CLAUDE.md). The most important test here is
 * {@code getOrderStatus_ordersBelongingToAnotherCustomer_areNeverLeaked} -
 * it proves that an order returned by the client but owned by a different
 * customerId than this OrderTools instance was constructed with never makes
 * it into the response string, only a generic not-found message does.
 *
 * <p>OrderTools is deliberately not a Spring bean (it's built fresh per
 * request with a resolved customerId), so it's constructed directly here
 * with a mocked OrderServiceClient - no Spring context needed.
 */
@ExtendWith(MockitoExtension.class)
class OrderToolsTest {

    private static final String CUSTOMER_ID = "cust-001";
    private static final String OTHER_CUSTOMER_ID = "cust-999";
    private static final String RAW_TOKEN = "raw-jwt-token";

    @Mock
    private OrderServiceClient orderServiceClient;

    private OrderTools orderTools;

    @BeforeEach
    void setUp() {
        orderTools = new OrderTools(CUSTOMER_ID, RAW_TOKEN, orderServiceClient);
    }

    // ---- getOrderStatus ----

    @Test
    void getOrderStatus_orderBelongsToThisCustomer_returnsDescription() {
        OrderView order = new OrderView("order-1", CUSTOMER_ID, "CONFIRMED", null);
        when(orderServiceClient.findById("order-1", RAW_TOKEN)).thenReturn(Optional.of(order));

        String result = orderTools.getOrderStatus("order-1");

        assertThat(result).isEqualTo("Order order-1 is CONFIRMED");
    }

    @Test
    void getOrderStatus_orderBelongsToThisCustomerAndIsCancelled_includesReason() {
        OrderView order = new OrderView("order-2", CUSTOMER_ID, "CANCELLED", "out of stock");
        when(orderServiceClient.findById("order-2", RAW_TOKEN)).thenReturn(Optional.of(order));

        String result = orderTools.getOrderStatus("order-2");

        assertThat(result).isEqualTo("Order order-2 is CANCELLED (reason: out of stock)");
    }

    /**
     * Critical cross-customer-leak test: the client returns a real order,
     * but it belongs to a different customer than this OrderTools instance
     * was constructed for. getOrderStatus's own ownership filter must catch
     * this (defense in depth even if the upstream call were ever mis-scoped)
     * and the order's real status/id must never appear in the result.
     */
    @Test
    void getOrderStatus_orderBelongsToDifferentCustomer_isFilteredOutAndNeverLeaked() {
        OrderView otherCustomersOrder = new OrderView("order-3", OTHER_CUSTOMER_ID, "CONFIRMED", null);
        when(orderServiceClient.findById("order-3", RAW_TOKEN)).thenReturn(Optional.of(otherCustomersOrder));

        String result = orderTools.getOrderStatus("order-3");

        assertThat(result).isEqualTo("No order found with that ID for this customer.");
        // Explicitly assert none of the other customer's real order data leaked through.
        assertThat(result).doesNotContain("order-3");
        assertThat(result).doesNotContain("CONFIRMED");
        assertThat(result).doesNotContain(OTHER_CUSTOMER_ID);
    }

    @Test
    void getOrderStatus_orderDoesNotExist_returnsNotFoundMessage() {
        when(orderServiceClient.findById("missing-order", RAW_TOKEN)).thenReturn(Optional.empty());

        String result = orderTools.getOrderStatus("missing-order");

        assertThat(result).isEqualTo("No order found with that ID for this customer.");
    }

    // ---- searchMyOrders ----

    @Test
    void searchMyOrders_noResults_returnsNoOrdersFoundMessage() {
        when(orderServiceClient.search("PENDING", RAW_TOKEN)).thenReturn(List.of());

        String result = orderTools.searchMyOrders("PENDING");

        assertThat(result).isEqualTo("No orders found.");
    }

    @Test
    void searchMyOrders_singleResult_returnsSingleDescription() {
        OrderSearchResult order = new OrderSearchResult("order-4", "PENDING", null);
        when(orderServiceClient.search(any(), eq(RAW_TOKEN))).thenReturn(List.of(order));

        String result = orderTools.searchMyOrders(null);

        assertThat(result).isEqualTo("Order order-4 is PENDING");
    }

    @Test
    void searchMyOrders_multipleResults_returnsNewlineJoinedDescriptions() {
        OrderSearchResult confirmed = new OrderSearchResult("order-5", "CONFIRMED", null);
        OrderSearchResult cancelled = new OrderSearchResult("order-6", "CANCELLED", "customer requested");
        when(orderServiceClient.search(any(), eq(RAW_TOKEN))).thenReturn(List.of(confirmed, cancelled));

        String result = orderTools.searchMyOrders(null);

        assertThat(result).isEqualTo(
                "Order order-5 is CONFIRMED\n" +
                "Order order-6 is CANCELLED (reason: customer requested)"
        );
    }

    /**
     * This class trusts that orderServiceClient.search(...) is already
     * customer-scoped server-side (via the forwarded JWT) - it does no
     * customer filtering of its own for search. All this test verifies is
     * that the status filter the model supplied is passed through unchanged,
     * not silently altered or dropped.
     */
    @Test
    void searchMyOrders_passesStatusThroughUnchangedToClient() {
        when(orderServiceClient.search(anyString(), anyString())).thenReturn(List.of());

        orderTools.searchMyOrders("CONFIRMED");

        verify(orderServiceClient).search("CONFIRMED", RAW_TOKEN);
    }

    @Test
    void searchMyOrders_blankStatus_passedThroughUnchanged() {
        when(orderServiceClient.search(eq(""), eq(RAW_TOKEN))).thenReturn(List.of());

        orderTools.searchMyOrders("");

        verify(orderServiceClient).search("", RAW_TOKEN);
    }
}
