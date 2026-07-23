package com.mfstechnologies.mymobi.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FieldValidatorsTest {

    @Test
    void validUpnsStartingWith1Or2AreAccepted() {
        assertThat(FieldValidators.isValidUpn("12345")).isTrue();
        assertThat(FieldValidators.isValidUpn("22345678901")).isTrue(); // 11 digits, starts 2
        assertThat(FieldValidators.isValidUpn("1")).isTrue();
    }

    @Test
    void upnsWithWrongStartingDigitAreRejected() {
        assertThat(FieldValidators.isValidUpn("32345")).isFalse();
        assertThat(FieldValidators.isValidUpn("02345")).isFalse();
    }

    @Test
    void upnsLongerThan11DigitsAreRejected() {
        assertThat(FieldValidators.isValidUpn("123456789012")).isFalse(); // 12 digits
    }

    @Test
    void nonNumericUpnIsRejected() {
        assertThat(FieldValidators.isValidUpn("1abcd")).isFalse();
        assertThat(FieldValidators.isValidUpn("")).isFalse();
        assertThat(FieldValidators.isValidUpn(null)).isFalse();
    }

    @Test
    void nationalIdMustBeExactly8DigitsAndNotStartWithZero() {
        assertThat(FieldValidators.isValidNationalId("12345678")).isTrue();
        assertThat(FieldValidators.isValidNationalId("02345678")).isFalse(); // starts 0
        assertThat(FieldValidators.isValidNationalId("1234567")).isFalse();  // 7 digits
        assertThat(FieldValidators.isValidNationalId("123456789")).isFalse(); // 9 digits
    }

    @Test
    void mobileNumberAcceptsBothConfirmedFormats() {
        // Exact examples confirmed in the original product conversation.
        assertThat(FieldValidators.isValidMobileNumber("0722730336")).isTrue();
        assertThat(FieldValidators.isValidMobileNumber("254722730336")).isTrue();
    }

    @Test
    void mobileNumberRejectsWrongFormats() {
        assertThat(FieldValidators.isValidMobileNumber("722730336")).isFalse(); // missing prefix
        assertThat(FieldValidators.isValidMobileNumber("0712345")).isFalse();   // too short
    }

    @Test
    void fiveDigitCodeValidation() {
        assertThat(FieldValidators.isValidFiveDigitCode("12345")).isTrue();
        assertThat(FieldValidators.isValidFiveDigitCode("1234")).isFalse();
        assertThat(FieldValidators.isValidFiveDigitCode("123456")).isFalse();
        assertThat(FieldValidators.isValidFiveDigitCode("abcde")).isFalse();
    }

    @Test
    void validEmailAddressesAreAccepted() {
        assertThat(FieldValidators.isValidEmail("jane.doe@example.com")).isTrue();
        assertThat(FieldValidators.isValidEmail("john_kamau123@mymobi.co.ke")).isTrue();
        assertThat(FieldValidators.isValidEmail("a+tag@sub.domain.org")).isTrue();
    }

    @Test
    void emailAddressesMissingAnAtSignOrDomainAreRejected() {
        assertThat(FieldValidators.isValidEmail("not-an-email")).isFalse();
        assertThat(FieldValidators.isValidEmail("missing@domain")).isFalse(); // no TLD
        assertThat(FieldValidators.isValidEmail("@example.com")).isFalse();   // no local part
        assertThat(FieldValidators.isValidEmail("name@")).isFalse();          // no domain
    }

    @Test
    void nullOrEmptyEmailIsRejected() {
        assertThat(FieldValidators.isValidEmail(null)).isFalse();
        assertThat(FieldValidators.isValidEmail("")).isFalse();
    }
}
