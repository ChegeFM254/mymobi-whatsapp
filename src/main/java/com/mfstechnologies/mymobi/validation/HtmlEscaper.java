package com.mfstechnologies.mymobi.validation;

public final class HtmlEscaper {

    private HtmlEscaper() {
    }

    public static String escape(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}