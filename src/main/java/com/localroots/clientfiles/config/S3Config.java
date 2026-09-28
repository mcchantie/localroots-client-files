package com.localroots.clientfiles.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(
            @Value("${storage.s3.region}") String region,
            @Value("${storage.s3.endpoint:}") String endpoint,
            @Value("${storage.s3.access-key-id:}") String accessKeyId,
            @Value("${storage.s3.secret-access-key:}") String secretAccessKey,
            @Value("${storage.s3.path-style-access:false}") boolean pathStyleAccess
    ) {
        var builder = S3Client.builder().region(Region.of(region))
                .forcePathStyle(pathStyleAccess);
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        if (!accessKeyId.isBlank() && !secretAccessKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        }
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner(
            @Value("${storage.s3.region}") String region,
            @Value("${storage.s3.endpoint:}") String endpoint,
            @Value("${storage.s3.access-key-id:}") String accessKeyId,
            @Value("${storage.s3.secret-access-key:}") String secretAccessKey,
            @Value("${storage.s3.path-style-access:false}") boolean pathStyleAccess
    ) {
        var builder = S3Presigner.builder().region(Region.of(region))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess).build());
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        if (!accessKeyId.isBlank() && !secretAccessKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        }
        return builder.build();
    }
}
