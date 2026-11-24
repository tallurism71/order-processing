package com.acme.orderplatform.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventCodecTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final EventCodec codec = new EventCodec(mapper);

    @Test
    void roundTripsEnvelopeAndTypedPayload() {
        UUID orderId = UUID.randomUUID();
        var payload = new Events.PaymentFailed(orderId, "declined");
        var envelope = new EventEnvelope(UUID.randomUUID(), EventTypes.PAYMENT_FAILED, 1, "Order", orderId.toString(),
                "corr-1", null, "payment-service", Instant.now(), mapper.valueToTree(payload));

        EventEnvelope decoded = codec.decode(codec.encode(envelope));

        assertThat(decoded.eventId()).isEqualTo(envelope.eventId());
        assertThat(decoded.correlationId()).isEqualTo("corr-1");
        assertThat(codec.payload(decoded, Events.PaymentFailed.class)).isEqualTo(payload);
    }

    @Test
    void malformedMessageIsAPoisonPillNotARetry() {
        assertThatThrownBy(() -> codec.decode("{not json")).isInstanceOf(EventDecodingException.class);
        assertThatThrownBy(() -> codec.decode("{\"eventType\":\"X\"}")).isInstanceOf(EventDecodingException.class);
    }

    @Test
    void payloadUsesBigDecimalForMoney() {
        var line = new Events.OrderCreated.Line("SKU", 2, new BigDecimal("19.99"));
        assertThat(mapper.convertValue(mapper.valueToTree(line), Events.OrderCreated.Line.class).unitPrice())
                .isEqualByComparingTo("19.99");
    }
}
