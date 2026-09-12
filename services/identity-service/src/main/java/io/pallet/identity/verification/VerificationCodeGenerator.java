package io.pallet.identity.verification;

import java.security.SecureRandom;

/**
 * Generates the 8-character OTP mailed to a self-registered account. {@code SecureRandom}, never
 * {@code Random}/{@code ThreadLocalRandom} — this is a credential, not a UI id.
 */
public final class VerificationCodeGenerator {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private VerificationCodeGenerator() {}

    public static String generate() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
