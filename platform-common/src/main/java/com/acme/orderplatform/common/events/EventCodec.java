package com.acme.orderplatform.common.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class EventCodec {

    private final ObjectMapper mapper;

    public EventCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public EventEnvelope decode(String json) {
        try {
            EventEnvelope envelope = mapper.readValue(json, EventEnvelope.class);
            if (envelope.eventId() == null || envelope.eventType() == null || envelope.aggregateId() == null) {
                throw new EventDecodingException("Envelope is missing eventId, eventType or aggregateId", null);
            }
            return envelope;
        } catch (JsonProcessingException e) {
            throw new EventDecodingException("Unreadable event envelope", e);
        }
    }

    public <T> T payload(EventEnvelope envelope, Class<T> type) {
        try {
            return mapper.treeToValue(envelope.payload(), type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new EventDecodingException("Payload of " + envelope.eventType() + " is not a valid " + type.getSimpleName(), e);
        }
    }

    public String encode(EventEnvelope envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize event " + envelope.eventId(), e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
