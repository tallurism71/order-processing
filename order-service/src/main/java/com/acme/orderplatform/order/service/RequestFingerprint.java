package com.acme.orderplatform.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 of the request body, stored with the idempotency key to detect a key reused for a different order. */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String of(ObjectMapper mapper, Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(request));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not fingerprint request", e);
        }
    }
}
