package com.mfstechnologies.mymobi.validation;

import java.security.SecureRandom;

/**
 * Generates random numeric codes for verification codes, OTPs, etc. —
 * equivalent of generateFiveDigitCode() / generateApprovalCode() from
 * the Node.js version. Uses SecureRandom rather than Math.random()'s
 * Java equivalent, since these codes gate account access.
 */
public final class CodeGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private CodeGenerator() {
    }

    public static String generateFiveDigitCode() {
        return String.valueOf(10000 + RANDOM.nextInt(90000));
    }

    public static String generateSixDigitCode() {
        return String.valueOf(100000 + RANDOM.nextInt(900000));
    }
}