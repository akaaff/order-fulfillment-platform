package com.orderplatform.order.api;

import com.orderplatform.events.OrderLine;
import com.orderplatform.order.api.dto.CreateOrderRequest;
import com.orderplatform.order.api.dto.OrderResponse;
import com.orderplatform.order.domain.Order;
import com.orderplatform.order.domain.OrderStatus;
import com.orderplatform.order.repository.OrderRepository;
import com.orderplatform.order.search.OrderSearchDocument;
import com.orderplatform.order.search.OrderSearchService;
import com.orderplatform.order.service.OrderCreationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Order intake, lookup, and search. Creation is fire-and-forget from the
 * caller's perspective - the response reflects PENDING status only; the
 * eventual CONFIRMED/CANCELLED outcome arrives asynchronously via Kafka and
 * is only visible on a follow-up GET or search.
 */
@Validated
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderRepository orderRepository;
    private final OrderCreationService orderCreationService;
    private final OrderSearchService orderSearchService;

    public OrderController(
            OrderRepository orderRepository,
            OrderCreationService orderCreationService,
            OrderSearchService orderSearchService
    ) {
        this.orderRepository = orderRepository;
        this.orderCreationService = orderCreationService;
        this.orderSearchService = orderSearchService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        List<OrderLine> lines = request.lines().stream()
                .map(line -> new OrderLine(line.sku(), line.quantity()))
                .toList();

        Order order = orderCreationService.createOrder(request.customerId(), lines);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(OrderResponse.from(order));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable UUID orderId) {
        return orderRepository.findById(orderId)
                .map(OrderResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * customerId is required (not optional) so this can never become an
     * unscoped, cross-customer search - see OrderSearchService.
     */
    @GetMapping("/search")
    public List<OrderSearchDocument> search(
            @RequestParam @NotBlank String customerId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        String statusFilter = status == null ? null : status.name();
        return orderSearchService.search(customerId, statusFilter, from, to);
    }
}
