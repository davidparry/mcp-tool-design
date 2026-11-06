package com.davidparry.tooldesign.workflow.matching;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One spelling for every way a customer or the catalog writes a size: "large" and "L", "42-inch" and "42 in". */
public final class Sizes {

    private static final Map<String, String> WORDS = Map.ofEntries(Map.entry("xs", "XS"),
            Map.entry("extrasmall", "XS"), Map.entry("xsmall", "XS"), Map.entry("s", "S"), Map.entry("small", "S"),
            Map.entry("m", "M"), Map.entry("med", "M"), Map.entry("medium", "M"), Map.entry("l", "L"),
            Map.entry("large", "L"), Map.entry("xl", "XL"), Map.entry("extralarge", "XL"), Map.entry("xlarge", "XL"),
            Map.entry("s/m", "S/M"), Map.entry("small/medium", "S/M"), Map.entry("sm", "S/M"));

    private static final Pattern INCHES = Pattern.compile("^(\\d{1,3})\\s*(-|\\s)?\\s*(in|inch|inches|\"|”)?\\.?$");

    private Sizes() {
    }

    /** The canonical size, or null when the text isn't a size this store sells. */
    public static String canonical(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.trim().toLowerCase(Locale.ROOT);
        Matcher inches = INCHES.matcher(lower);
        if (inches.matches()) {
            return inches.group(1) + " IN";
        }
        String squeezed = lower.replaceAll("[\\s-]+", "");
        String word = WORDS.get(squeezed);
        if (word != null) {
            return word;
        }
        if (squeezed.endsWith("size")) {
            return WORDS.get(squeezed.substring(0, squeezed.length() - 4));
        }
        return null;
    }

    public static boolean same(String a, String b) {
        String left = canonical(a);
        return left != null && left.equals(canonical(b));
    }
}
