package com.acme.orderplatform.common.events;

/** A message that can never be processed (malformed). Not retried: it goes straight to the dead-letter topic. */
public class EventDecodingException extends RuntimeException {
    public EventDecodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
