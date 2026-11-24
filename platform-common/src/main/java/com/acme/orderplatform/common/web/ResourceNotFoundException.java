package com.acme.orderplatform.common.web;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resource, Object id) {
        super(resource + " " + id + " was not found");
    }
}
