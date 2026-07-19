package com.mfstechnologies.mymobi.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlEscaperTest {

    @Test
    void escapesScriptTagsSoTheyCannotExecute() {
        String malicious = "<script>alert('xss')</script>";
        String escaped = HtmlEscaper.escape(malicious);

        assertThat(escaped).doesNotContain("<script>");
        assertThat(escaped).contains("&lt;script&gt;");
    }

    @Test
    void escapesAmpersandsQuotesAndAngleBrackets() {
        assertThat(HtmlEscaper.escape("Tom & Jerry")).isEqualTo("Tom &amp; Jerry");
        assertThat(HtmlEscaper.escape("<div>")).isEqualTo("&lt;div&gt;");
        assertThat(HtmlEscaper.escape("\"quoted\"")).isEqualTo("&quot;quoted&quot;");
        assertThat(HtmlEscaper.escape("O'Brien")).isEqualTo("O&#39;Brien");
    }

    @Test
    void nullInputBecomesEmptyStringRatherThanThrowing() {
        assertThat(HtmlEscaper.escape(null)).isEmpty();
    }

    @Test
    void ordinaryTextIsUnchanged() {
        assertThat(HtmlEscaper.escape("Jane Doe")).isEqualTo("Jane Doe");
    }
}