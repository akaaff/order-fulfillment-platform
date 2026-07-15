package com.orderplatform.order.domain;

import com.orderplatform.events.OrderLine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@link Order} aggregate. Order is an immutable record with
 * a static factory ({@code create}) plus two "with-a-field-changed" transition
 * methods ({@code confirm}/{@code cancel}) that each return a brand new instance
 * rather than mutating in place - these tests pin down both the immutability
 * guarantees and the (deliberately unguarded) transition behavior.
 */
class OrderTest {

    private final UUID orderId = UUID.randomUUID();
    private final String customerId = "cust-001";
    private final Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void createProducesPendingOrderWithNoCancellationReason() {
        List<OrderLine> lines = new ArrayList<>(List.of(new OrderLine("sku-1", 2)));

        Order order = Order.create(orderId, customerId, lines, createdAt);

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.cancellationReason()).isNull();
        assertThat(order.orderId()).isEqualTo(orderId);
        assertThat(order.customerId()).isEqualTo(customerId);
        assertThat(order.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void createDefensivelyCopiesTheInputListSoLaterMutationOfTheOriginalDoesNotLeak() {
        List<OrderLine> mutableLines = new ArrayList<>(List.of(new OrderLine("sku-1", 2)));

        Order order = Order.create(orderId, customerId, mutableLines, createdAt);

        // Mutate the ORIGINAL list after construction - List.copyOf inside create()
        // means the Order's own lines list must be unaffected by this.
        mutableLines.add(new OrderLine("sku-2", 5));
        mutableLines.clear();

        assertThat(order.lines()).containsExactly(new OrderLine("sku-1", 2));
    }

    @Test
    void createReturnsAnImmutableLinesList() {
        Order order = Order.create(orderId, customerId, List.of(new OrderLine("sku-1", 1)), createdAt);

        assertThatThrownBy(() -> order.lines().add(new OrderLine("sku-2", 1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> order.lines().remove(0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void confirmReturnsNewOrderWithConfirmedStatusAndOtherFieldsUnchanged() {
        Order original = Order.create(orderId, customerId, List.of(new OrderLine("sku-1", 1)), createdAt);

        Order confirmed = original.confirm();

        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.cancellationReason()).isNull();
        assertThat(confirmed.orderId()).isEqualTo(original.orderId());
        assertThat(confirmed.customerId()).isEqualTo(original.customerId());
        assertThat(confirmed.lines()).isEqualTo(original.lines());
        assertThat(confirmed.createdAt()).isEqualTo(original.createdAt());
        // Records are immutable - confirm() must not have mutated the original.
        assertThat(original.status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void cancelReturnsNewOrderWithCancelledStatusAndReasonSet() {
        Order original = Order.create(orderId, customerId, List.of(new OrderLine("sku-1", 1)), createdAt);

        Order cancelled = original.cancel("out of stock");

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancellationReason()).isEqualTo("out of stock");
        assertThat(cancelled.orderId()).isEqualTo(original.orderId());
        assertThat(cancelled.customerId()).isEqualTo(original.customerId());
        assertThat(cancelled.lines()).isEqualTo(original.lines());
        assertThat(cancelled.createdAt()).isEqualTo(original.createdAt());
    }

    /**
     * Documents real (if arguably gap-y) behavior: confirm()/cancel() perform no
     * guard against the order's current status. Calling cancel() on an order that
     * is already CONFIRMED still succeeds and silently produces a CANCELLED order -
     * there is no "can't cancel a confirmed order" check anywhere in the aggregate.
     * This test is not asserting that this SHOULD throw, only that it currently doesn't.
     */
    @Test
    void cancelOnAnAlreadyConfirmedOrderStillSucceedsWithNoStatusGuard() {
        Order confirmed = Order.create(orderId, customerId, List.of(new OrderLine("sku-1", 1)), createdAt)
                .confirm();

        Order cancelled = confirmed.cancel("changed my mind");

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancellationReason()).isEqualTo("changed my mind");
    }

    /** Same gap, other direction: confirm() on an already-CANCELLED order still succeeds. */
    @Test
    void confirmOnAnAlreadyCancelledOrderStillSucceedsWithNoStatusGuard() {
        Order cancelled = Order.create(orderId, customerId, List.of(new OrderLine("sku-1", 1)), createdAt)
                .cancel("out of stock");

        Order reConfirmed = cancelled.confirm();

        assertThat(reConfirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reConfirmed.cancellationReason()).isNull();
    }
}
