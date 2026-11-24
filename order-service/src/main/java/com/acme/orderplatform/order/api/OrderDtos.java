package com.acme.orderplatform.order.api;

import com.acme.orderplatform.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API contracts are separate from JPA entities so the schema can evolve without breaking clients. */
public final class OrderDtos {

    public record CreateOrderRequest(
            @NotBlank @Size(max = 64) String customerId,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO-4217 code") String currency,
            @NotEmpty @Size(max = 100) List<@Valid LineRequest> lines) {
    }

    public record LineRequest(
            @NotBlank @Size(max = 64) String sku,
            @Min(1) @Max(1000) int quantity,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal unitPrice) {
    }

    public record LineResponse(String sku, int quantity, BigDecimal unitPrice) {
    }

    @Schema(description = "Order resource")
    public record OrderResponse(
            UUID id,
            String customerId,
            OrderStatus status,
            BigDecimal totalAmount,
            String currency,
            String failureReason,
            List<LineResponse> lines,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record CancelOrderRequest(@Size(max = 255) String reason) {
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    private OrderDtos() {
    }
}
