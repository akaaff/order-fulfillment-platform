package com.orderplatform.order.api;

import com.orderplatform.events.OrderLine;
import com.orderplatform.order.api.dto.CreateOrderRequest;
import com.orderplatform.order.api.dto.OrderResponse;
import com.orderplatform.order.domain.Order;
import com.orderplatform.order.repository.OrderRepository;
import com.orderplatform.order.service.OrderCreationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Order intake and lookup. Creation is fire-and-forget from the caller's
 * perspective - the response reflects PENDING status only; the eventual
 * CONFIRMED/CANCELLED outcome arrives asynchronously via Kafka and is only
 * visible on a follow-up GET.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderRepository orderRepository;
    private final OrderCreationService orderCreationService;

    public OrderController(OrderRepository orderRepository, OrderCreationService orderCreationService) {
        this.orderRepository = orderRepository;
        this.orderCreationService = orderCreationService;
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
}
