package com.acme.orderplatform.recon.extract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public class LocalExtractPublisher implements ExtractPublisher {

    private final Path root;

    public LocalExtractPublisher(Path root) {
        this.root = root;
    }

    @Override
    public String publish(Path file, String key, String contentType, Map<String, String> metadata) {
        try {
            Path target = root.resolve(key);
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            return target.toUri().toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
