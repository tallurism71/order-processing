package com.acme.orderplatform.order.api;

import com.acme.orderplatform.order.domain.OrderStatus;
import com.acme.orderplatform.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

/**
 * REST resource for orders. Conventions: versioned path (/api/v1), nouns + HTTP verbs, 201 + Location
 * on create, Idempotency-Key header for safe client retries, paging on collections, RFC 7807 errors.
 * Parameter constraints use Spring MVC's built-in method validation (no class-level @Validated proxy).
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Place an order. Retrying with the same Idempotency-Key returns the original order (200) instead of creating a duplicate.")
    public ResponseEntity<OrderDtos.OrderResponse> create(
            @Parameter(description = "Client-generated unique key, e.g. a UUID")
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9-]{8,128}") String idempotencyKey,
            @Valid @RequestBody OrderDtos.CreateOrderRequest request) {
        OrderService.Creation result = service.placeOrder(request, idempotencyKey);
        if (!result.created()) {
            return ResponseEntity.ok(result.order());
        }
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(result.order().id()).toUri();
        return ResponseEntity.created(location).body(result.order());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one order (served from the Redis cache when possible)")
    public OrderDtos.OrderResponse get(@PathVariable UUID id) {
        return service.getOrder(id);
    }

    @GetMapping
    @Operation(summary = "List a customer's orders, newest first")
    public OrderDtos.PageResponse<OrderDtos.OrderResponse> list(
            @RequestParam String customerId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.listOrders(customerId, status, page, size);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order that is not yet confirmed; reserved stock is released by the saga")
    public OrderDtos.OrderResponse cancel(@PathVariable UUID id,
                                          @Valid @RequestBody(required = false) OrderDtos.CancelOrderRequest request) {
        String reason = request == null || request.reason() == null ? "Cancelled by customer" : request.reason();
        return service.cancelOrder(id, reason);
    }
}
