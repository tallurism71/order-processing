package com.acme.orderplatform.recon.extract;

import java.nio.file.Path;
import java.util.Map;

/** Where extracts land: S3 in AWS, the local file system for development. */
public interface ExtractPublisher {
    /** @return the URI the object was published to (recorded in the audit table). */
    String publish(Path file, String key, String contentType, Map<String, String> metadata);
}
