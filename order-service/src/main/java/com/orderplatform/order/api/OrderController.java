package com.orderplatform.order.api;

import com.orderplatform.events.OrderLine;
import com.orderplatform.order.api.dto.CreateOrderRequest;
import com.orderplatform.order.api.dto.OrderResponse;
import com.orderplatform.order.domain.Order;
import com.orderplatform.order.messaging.OrderEventPublisher;
import com.orderplatform.order.repository.OrderRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher eventPublisher;

    public OrderController(OrderRepository orderRepository, OrderEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        List<OrderLine> lines = request.lines().stream()
                .map(line -> new OrderLine(line.sku(), line.quantity()))
                .toList();

        Order order = new Order(UUID.randomUUID(), request.customerId(), lines, Instant.now());
        orderRepository.save(order);
        eventPublisher.publishOrderCreated(order);

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
