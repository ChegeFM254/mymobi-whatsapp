package com.mfstechnologies.mymobi.validation;

import java.util.regex.Pattern;

/**
 * Field validation rules - direct port of isValidUpn / isValidNationalId
 * / isValidMobileNumber from the Node.js version. Kept as static methods
 * on a small utility class (no state, no dependencies) so they're
 * trivially unit-testable, matching how they were tested in the Node
 * version's test suite.
 */
public final class FieldValidators {

    // Up to 11 digits, must start with 1 or 2.
    private static final Pattern UPN_PATTERN = Pattern.compile("^[12]\\d{0,10}$");

    // Exactly 8 digits, cannot start with 0.
    private static final Pattern NATIONAL_ID_PATTERN = Pattern.compile("^[1-9]\\d{7}$");

    // 10 digits starting with 0 (e.g. 0722730336), or 12 digits starting with 254 (e.g. 254722730336).
    private static final Pattern MOBILE_NUMBER_PATTERN = Pattern.compile("^(0\\d{9}|254\\d{9})$");

    // Standard, reasonably strict email shape: something@something.tld
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w.+-]+@[\\w-]+\\.[a-zA-Z]{2,}$");

    private FieldValidators() {
    }

    public static boolean isValidUpn(String text) {
        return text != null && UPN_PATTERN.matcher(text).matches();
    }

    public static boolean isValidNationalId(String text) {
        return text != null && NATIONAL_ID_PATTERN.matcher(text).matches();
    }

    public static boolean isValidMobileNumber(String text) {
        return text != null && MOBILE_NUMBER_PATTERN.matcher(text).matches();
    }

    public static boolean isValidEmail(String text) {
        return text != null && EMAIL_PATTERN.matcher(text).matches();
    }

    public static boolean isValidFiveDigitCode(String text) {
        return text != null && text.matches("^\\d{5}$");
    }
}
