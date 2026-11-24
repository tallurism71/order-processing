package com.acme.orderplatform.payment.api;

import com.acme.orderplatform.common.web.ResourceNotFoundException;
import com.acme.orderplatform.payment.domain.Payment;
import com.acme.orderplatform.payment.domain.PaymentRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments")
public class PaymentController {

    public record PaymentView(UUID id, UUID orderId, BigDecimal amount, String currency, Payment.Status status,
                              String gatewayReference, String failureReason, Instant createdAt) {
    }

    private final PaymentRepository payments;

    public PaymentController(PaymentRepository payments) {
        this.payments = payments;
    }

    @GetMapping
    public PaymentView byOrder(@RequestParam UUID orderId) {
        return payments.findByOrderId(orderId)
                .map(p -> new PaymentView(p.getId(), p.getOrderId(), p.getAmount(), p.getCurrency(), p.getStatus(),
                        p.getGatewayReference(), p.getFailureReason(), p.getCreatedAt()))
                .orElseThrow(() -> new ResourceNotFoundException("Payment for order", orderId));
    }
}
