package com.todollab.common.util;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailNormalizerAndHashUtilsTest {

    @Test
    void normalizeTrimsAndLowercasesEmailWithRootLocale() {
        assertThat(EmailNormalizer.normalize("  Alice.Smith+Work@Example.COM  "))
                .isEqualTo("alice.smith+work@example.com");
        assertThat(EmailNormalizer.normalize("INDIANA@EXAMPLE.COM"))
                .isEqualTo("indiana@example.com");
    }

    @Test
    void normalizeRejectsNullAndBlankEmails() {
        assertThatThrownBy(() -> EmailNormalizer.normalize(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailNormalizer.normalize(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailNormalizer.normalize("   \t\n "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sha256HexProducesDeterministicLowercaseHexMatchingKnownVectors() {
        assertThat(HashUtils.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(HashUtils.sha256Hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThatThrownBy(() -> HashUtils.sha256Hex(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generateSecureTokenReturnsUnpaddedUrlSafeRandomBase64Strings() {
        String token1 = HashUtils.generateSecureToken(32);
        String token2 = HashUtils.generateSecureToken(32);

        assertThat(token1).isNotBlank().doesNotContain("=", "+", "/");
        assertThat(token2).isNotBlank().doesNotContain("=", "+", "/");
        assertThat(token1).isNotEqualTo(token2);
        assertThat(Base64.getUrlDecoder().decode(token1)).hasSize(32);

        assertThatThrownBy(() -> HashUtils.generateSecureToken(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HashUtils.generateSecureToken(-8))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
