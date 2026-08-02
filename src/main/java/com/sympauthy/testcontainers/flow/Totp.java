package com.sympauthy.testcontainers.flow;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

/**
 * A TOTP (RFC 6238) code generator matching the parameters SympAuthy's {@code TotpManager} verifies
 * with: HMAC-SHA1, 6 digits, a 30-second step from the Unix epoch, and an RFC 4648 Base32-encoded
 * secret. No external dependency: {@link Mac} (HMAC-SHA1) + a small Base32 decoder.
 *
 * <p>The interactive flow uses this to enroll TOTP automatically (compute a valid code from the secret
 * the enroll step returns). It is public so a test that captured the secret at enrollment can compute a
 * code to answer a later sign-in challenge (see {@link TotpChallengeHandler}).
 */
public final class Totp {

    private static final int DIGITS = 6;
    private static final long TIME_STEP_SECONDS = 30L;
    private static final int[] POWERS_OF_TEN = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000};
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    /** The current 6-digit TOTP code for the given Base32 secret. */
    public static String code(String base32Secret) {
        return codeAt(base32Secret, System.currentTimeMillis() / 1000L);
    }

    /** The 6-digit TOTP code for the given Base32 secret at the given Unix time (seconds). */
    static String codeAt(String base32Secret, long epochSeconds) {
        return generate(decodeBase32(base32Secret), epochSeconds / TIME_STEP_SECONDS);
    }

    private static String generate(byte[] key, long counter) {
        byte[] hash = hmacSha1(key, ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
        // RFC 4226 §5.4 dynamic truncation.
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int code = binary % POWERS_OF_TEN[DIGITS];
        return String.format("%0" + DIGITS + "d", code);
    }

    private static byte[] hmacSha1(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 is required for TOTP but unavailable", e);
        }
    }

    /** Decodes an RFC 4648 Base32 string (padding and case insensitive) to bytes. */
    static byte[] decodeBase32(String encoded) {
        StringBuilder bits = new StringBuilder();
        for (int i = 0; i < encoded.length(); i++) {
            char c = Character.toUpperCase(encoded.charAt(i));
            if (c == '=' || Character.isWhitespace(c)) {
                continue;
            }
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Not a Base32 character: '" + c + "'");
            }
            String chunk = Integer.toBinaryString(value);
            bits.append("00000".substring(chunk.length())).append(chunk);
        }
        int fullBytes = bits.length() / 8;
        byte[] bytes = new byte[fullBytes];
        for (int i = 0; i < fullBytes; i++) {
            bytes[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
        }
        return bytes;
    }
}
