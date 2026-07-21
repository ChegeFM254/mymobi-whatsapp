package com.mfstechnologies.mymobi.validation;

import java.util.regex.Pattern;

public final class FieldValidators {

    private static final Pattern UPN_PATTERN = Pattern.compile("^[12]\\d{0,10}$");
    private static final Pattern NATIONAL_ID_PATTERN = Pattern.compile("^[1-9]\\d{7}$");
    private static final Pattern MOBILE_NUMBER_PATTERN = Pattern.compile("^(0\\d{9}|254\\d{9})$");

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

    public static boolean isValidFiveDigitCode(String text) {
        return text != null && text.matches("^\\d{5}$");
    }
}
