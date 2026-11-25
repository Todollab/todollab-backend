package com.todollab.common.util;

import java.util.Locale;

/**
 * Normalizes email addresses consistently across auth, profile, and invite flows.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("E-mail não pode ser vazio.");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
