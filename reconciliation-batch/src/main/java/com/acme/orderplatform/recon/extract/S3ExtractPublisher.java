package com.acme.orderplatform.recon.extract;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.nio.file.Path;
import java.util.Map;

/**
 * Uploads with KMS encryption and an S3-verified SHA-256 checksum, so a corrupted upload is rejected
 * by S3 itself. Business metadata (record count, checksum, business date) travels on the object.
 * Credentials come from the pod's IAM role (IRSA), never from config.
 */
public class S3ExtractPublisher implements ExtractPublisher {

    private final S3Client s3;
    private final String bucket;

    public S3ExtractPublisher(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public String publish(Path file, String key, String contentType, Map<String, String> metadata) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                        .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                        .metadata(metadata)
                        .build(),
                RequestBody.fromFile(file));
        return "s3://" + bucket + "/" + key;
    }
}
