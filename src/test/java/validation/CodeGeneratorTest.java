package com.mfstechnologies.mymobi.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGeneratorTest {

    @RepeatedTest(50)
    void fiveDigitCodeIsAlwaysExactlyFiveDigits() {
        String code = CodeGenerator.generateFiveDigitCode();
        assertThat(code).matches("^\\d{5}$");
    }

    @RepeatedTest(50)
    void sixDigitCodeIsAlwaysExactlySixDigits() {
        String code = CodeGenerator.generateSixDigitCode();
        assertThat(code).matches("^\\d{6}$");
    }
}