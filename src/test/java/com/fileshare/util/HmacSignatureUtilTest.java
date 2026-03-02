package com.fileshare.util;

import com.fileshare.exception.InvalidTokenException;
import com.fileshare.exception.TokenExpiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class HmacSignatureUtilTest {

    private HmacSignatureUtil hmacSignatureUtil;
    private static final String SECRET = "test-secret-must-be-at-least-32-characters-long";

    @BeforeEach
    void setUp() {
        hmacSignatureUtil = new HmacSignatureUtil(SECRET);
    }

    @Test
    void createAndValidateToken_success() {
        UUID fileId = UUID.randomUUID();
        String s3Key = "uploads/user1/abc/file.txt";
        long expiresAt = Instant.now().getEpochSecond() + 3600;

        String token = hmacSignatureUtil.createToken(fileId, s3Key, expiresAt);
        HmacSignatureUtil.TokenClaims claims = hmacSignatureUtil.validateToken(token);

        assertThat(claims.fileUploadId()).isEqualTo(fileId);
        assertThat(claims.s3Key()).isEqualTo(s3Key);
        assertThat(claims.expiresAt()).isEqualTo(expiresAt);
        assertThat(claims.version()).isEqualTo("v1");
    }

    @Test
    void validateToken_expiredToken_throwsTokenExpiredException() {
        UUID fileId = UUID.randomUUID();
        long expiredAt = Instant.now().getEpochSecond() - 1;

        String token = hmacSignatureUtil.createToken(fileId, "uploads/user/file.txt", expiredAt);

        assertThatThrownBy(() -> hmacSignatureUtil.validateToken(token))
                .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    void validateToken_tamperedSignature_throwsInvalidTokenException() {
        UUID fileId = UUID.randomUUID();
        long expiresAt = Instant.now().getEpochSecond() + 3600;

        String token = hmacSignatureUtil.createToken(fileId, "uploads/user/file.txt", expiresAt);
        String tamperedToken = token.substring(0, token.length() - 4) + "XXXX";

        assertThatThrownBy(() -> hmacSignatureUtil.validateToken(tamperedToken))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void validateToken_malformedToken_throwsInvalidTokenException() {
        assertThatThrownBy(() -> hmacSignatureUtil.validateToken("not-a-valid-token"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tokensFromDifferentSecrets_areNotCrossVerifiable() {
        HmacSignatureUtil other = new HmacSignatureUtil("different-secret-also-32-characters-long!!");
        UUID fileId = UUID.randomUUID();
        long expiresAt = Instant.now().getEpochSecond() + 3600;

        String tokenFromOther = other.createToken(fileId, "uploads/user/file.txt", expiresAt);

        assertThatThrownBy(() -> hmacSignatureUtil.validateToken(tokenFromOther))
                .isInstanceOf(InvalidTokenException.class);
    }
}
