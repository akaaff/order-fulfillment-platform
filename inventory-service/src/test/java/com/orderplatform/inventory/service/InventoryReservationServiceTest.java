package com.orderplatform.inventory.service;

import com.orderplatform.events.OrderLine;
import com.orderplatform.inventory.domain.StockItem;
import com.orderplatform.inventory.repository.StockItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InventoryReservationService}. These exercise
 * reserveLines() in isolation against a mocked repository - the actual
 * REQUIRES_NEW transactional rollback behaviour (the DB undoing earlier
 * lines in the same call when a later line fails) is Spring/JPA/database
 * machinery that a plain Mockito test can't observe; here we only confirm
 * the exception propagates uncaught so the caller's transaction is marked
 * for rollback.
 */
@ExtendWith(MockitoExtension.class)
class InventoryReservationServiceTest {

    @Mock
    private StockItemRepository stockItemRepository;

    private InventoryReservationService reservationService;

    @Test
    void unknownSkuThrowsInsufficientStockExceptionMentioningTheSku() {
        reservationService = new InventoryReservationService(stockItemRepository);
        when(stockItemRepository.findById("unknown-sku")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.reserveLines(List.of(new OrderLine("unknown-sku", 1))))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("unknown-sku");
    }

    @Test
    void insufficientQuantityThrowsInsufficientStockException() {
        reservationService = new InventoryReservationService(stockItemRepository);
        StockItem item = new StockItem("sku-1", 2);
        when(stockItemRepository.findById("sku-1")).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> reservationService.reserveLines(List.of(new OrderLine("sku-1", 5))))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("sku-1");
    }

    @Test
    void successfulReservationDecrementsQuantityAcrossMultipleLines() {
        reservationService = new InventoryReservationService(stockItemRepository);
        StockItem itemA = new StockItem("sku-a", 10);
        StockItem itemB = new StockItem("sku-b", 5);
        when(stockItemRepository.findById("sku-a")).thenReturn(Optional.of(itemA));
        when(stockItemRepository.findById("sku-b")).thenReturn(Optional.of(itemB));

        reservationService.reserveLines(List.of(
                new OrderLine("sku-a", 3),
                new OrderLine("sku-b", 5)
        ));

        // Real StockItem instances (not mocks) let us assert final state directly
        // rather than verifying setQuantity() call arguments.
        assertThat(itemA.getQuantity()).isEqualTo(7);
        assertThat(itemB.getQuantity()).isEqualTo(0);
    }

    @Test
    void failurePartwayThroughPropagatesExceptionUncaught() {
        reservationService = new InventoryReservationService(stockItemRepository);
        StockItem itemA = new StockItem("sku-a", 10);
        StockItem itemC = new StockItem("sku-c", 1);
        when(stockItemRepository.findById("sku-a")).thenReturn(Optional.of(itemA));
        when(stockItemRepository.findById("sku-c")).thenReturn(Optional.of(itemC));

        // Line 2 of 3 (sku-c) is insufficient; the exception must propagate
        // uncaught so Spring's @Transactional(REQUIRES_NEW) proxy rolls back
        // the whole call - actual DB rollback of the sku-a decrement already
        // applied in-process is Spring/JPA behaviour outside pure unit test
        // scope and is not asserted here.
        assertThatThrownBy(() -> reservationService.reserveLines(List.of(
                new OrderLine("sku-a", 3),
                new OrderLine("sku-c", 100),
                new OrderLine("sku-never-reached", 1)
        ))).isInstanceOf(InsufficientStockException.class);
    }
}
