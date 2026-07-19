package com.mfstechnologies.mymobi.validation;

import java.security.SecureRandom;

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

    private static final String REF_NO_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    public static String generateLoanRefNo() {
        StringBuilder ref = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            ref.append(REF_NO_CHARS.charAt(RANDOM.nextInt(REF_NO_CHARS.length())));
        }
        return ref.toString();
    }

    public static String generateDocumentToken() {
        StringBuilder token = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            token.append(REF_NO_CHARS.charAt(RANDOM.nextInt(REF_NO_CHARS.length())));
        }
        return token.toString();
    }
}