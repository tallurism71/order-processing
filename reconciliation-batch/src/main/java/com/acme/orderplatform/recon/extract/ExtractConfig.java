package com.acme.orderplatform.recon.extract;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.file.Path;

@Configuration
public class ExtractConfig {

    @Bean
    @ConditionalOnProperty(name = "app.extract.target", havingValue = "s3")
    ExtractPublisher s3ExtractPublisher(@Value("${app.extract.s3.bucket}") String bucket,
                                        @Value("${app.extract.s3.region:us-east-1}") String region) {
        return new S3ExtractPublisher(S3Client.builder().region(Region.of(region)).build(), bucket);
    }

    @Bean
    @ConditionalOnProperty(name = "app.extract.target", havingValue = "local", matchIfMissing = true)
    ExtractPublisher localExtractPublisher(@Value("${app.extract.local-dir:./extracts}") String dir) {
        return new LocalExtractPublisher(Path.of(dir));
    }
}
