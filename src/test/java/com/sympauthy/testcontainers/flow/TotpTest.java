package com.sympauthy.testcontainers.flow;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the TOTP generator matches RFC 6238 (SHA-1, 6 digits, 30s) using the standard test vectors
 * from the RFC's Appendix B — so a code it computes will pass SympAuthy's {@code TotpManager}.
 */
class TotpTest {

    // RFC 6238 Appendix B uses the ASCII seed "12345678901234567890"; its Base32 encoding is:
    private static final String SEED_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesRfc6238Sha1Vectors() {
        // RFC 6238 Appendix B lists 8-digit TOTPs; the 6-digit code is the last 6 digits (94287082 → 287082).
        assertEquals("287082", Totp.codeAt(SEED_BASE32, 59L));
        assertEquals("081804", Totp.codeAt(SEED_BASE32, 1111111109L));
        assertEquals("050471", Totp.codeAt(SEED_BASE32, 1111111111L));
        assertEquals("005924", Totp.codeAt(SEED_BASE32, 1234567890L));
        assertEquals("279037", Totp.codeAt(SEED_BASE32, 2000000000L));
    }

    @Test
    void decodesBase32IgnoringCaseAndPadding() {
        byte[] expected = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(expected, Totp.decodeBase32(SEED_BASE32));
        assertArrayEquals(expected, Totp.decodeBase32(SEED_BASE32.toLowerCase()));
        // RFC 4648 §10 Base32 test vectors — decoding drops padding and yields the raw bytes.
        assertArrayEquals("f".getBytes(StandardCharsets.US_ASCII), Totp.decodeBase32("MY======"));
        assertArrayEquals("foob".getBytes(StandardCharsets.US_ASCII), Totp.decodeBase32("MZXW6YQ="));
        assertArrayEquals("foobar".getBytes(StandardCharsets.US_ASCII), Totp.decodeBase32("MZXW6YTBOI======"));
    }

    @Test
    void codeIsSixDigits() {
        String code = Totp.code(SEED_BASE32);
        assertEquals(6, code.length(), code);
    }
}
