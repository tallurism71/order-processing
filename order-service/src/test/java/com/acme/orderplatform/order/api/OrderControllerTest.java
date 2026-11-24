package com.acme.orderplatform.order.api;

import com.acme.orderplatform.common.web.GlobalExceptionHandler;
import com.acme.orderplatform.common.web.ResourceNotFoundException;
import com.acme.orderplatform.order.domain.OrderStatus;
import com.acme.orderplatform.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import(GlobalExceptionHandler.class)
class OrderControllerTest {

    private static final String BODY = """
            {"customerId":"c-1","currency":"USD","lines":[{"sku":"SKU-1001","quantity":2,"unitPrice":10.00}]}
            """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OrderService service;

    private OrderDtos.OrderResponse sample(UUID id) {
        return new OrderDtos.OrderResponse(id, "c-1", OrderStatus.PENDING, new BigDecimal("20.00"), "USD", null,
                List.of(new OrderDtos.LineResponse("SKU-1001", 2, new BigDecimal("10.00"))), 0, Instant.now(), Instant.now());
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.placeOrder(any(), eq("key-00000001"))).thenReturn(new OrderService.Creation(sample(id), true));

        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "key-00000001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/orders/" + id))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void retryWithSameKeyReturns200() throws Exception {
        when(service.placeOrder(any(), any())).thenReturn(new OrderService.Creation(sample(UUID.randomUUID()), false));

        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "key-00000001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    void invalidBodyReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "key-00000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"\",\"currency\":\"usd\",\"lines\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.currency").exists())
                .andExpect(jsonPath("$.errors.lines").exists());
        verifyNoInteractions(service);
    }

    @Test
    void missingIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void unknownOrderIs404ProblemDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getOrder(id)).thenThrow(new ResourceNotFoundException("Order", id));

        mvc.perform(get("/api/v1/orders/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void pageSizeIsCapped() throws Exception {
        mvc.perform(get("/api/v1/orders").param("customerId", "c-1").param("size", "5000"))
                .andExpect(status().isBadRequest());
    }
}
