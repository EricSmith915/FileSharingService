package com.fileshare.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

    private DoSpaces doSpaces = new DoSpaces();
    private Jwt jwt = new Jwt();
    private Hmac hmac = new Hmac();
    private Upload upload = new Upload();

    @Getter
    @Setter
    public static class DoSpaces {
        private String accessKey;
        private String secretKey;
        private String endpoint;
        private String region;
        private String bucket;
        private int presignedUrlTtlMinutes = 60;
    }

    @Getter
    @Setter
    public static class Jwt {
        private String secret;
        private long expirationMs = 86400000L;
    }

    @Getter
    @Setter
    public static class Hmac {
        private String secret;
        private long defaultLinkTtlSeconds = 3600L;
    }

    @Getter
    @Setter
    public static class Upload {
        private long defaultChunkSizeBytes = 10_485_760L;        // 10 MB
        private long minChunkSizeBytes = 5_242_880L;             // 5 MB (S3 min)
        private long maxFileSizeBytes = 53_687_091_200L;         // 50 GB
    }
}
