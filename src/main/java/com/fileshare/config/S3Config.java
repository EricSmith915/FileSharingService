package com.fileshare.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
@RequiredArgsConstructor
public class S3Config {

    private final AppProperties appProperties;

    @Bean
    public S3Client s3Client() {
        AppProperties.DoSpaces spaces = appProperties.getDoSpaces();
        return S3Client.builder()
                .endpointOverride(URI.create(spaces.getEndpoint()))
                .region(Region.of(spaces.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(spaces.getAccessKey(), spaces.getSecretKey())
                ))
                // DO Spaces requires path-style access
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        AppProperties.DoSpaces spaces = appProperties.getDoSpaces();
        return S3Presigner.builder()
                .endpointOverride(URI.create(spaces.getEndpoint()))
                .region(Region.of(spaces.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(spaces.getAccessKey(), spaces.getSecretKey())
                ))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
