package com.davidparry.tooldesign.workflow.matching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.davidparry.tooldesign.workflow.api.ApiTypes.OrderItem;

/**
 * Picks the order line a customer means from their own words, the same way every time.
 *
 * <p>Each line scores 3 when the description names its category ("harness", "jacket"), 2 for each word of its
 * product name ("trailblazer", "chew", "bone"), and 2 when a size or color the description mentions is the line's.
 * A mentioned size or color the line doesn't have costs 10. The best score wins; a tie at the top is ambiguous and a
 * best score of 0 or less matches nothing. An order with one line matches it unless the description contradicts it.
 */
public final class ItemMatcher {

    /** An order line and its product's category. */
    public record Line(OrderItem item, String category) {
    }

    public sealed interface Result permits Matched, Ambiguous, NotFound {
    }

    public record Matched(Line line) implements Result {
    }

    public record Ambiguous(List<Line> candidates) implements Result {
    }

    public record NotFound(List<Line> lines) implements Result {
    }

    static final int CATEGORY = 3;

    static final int NAME_WORD = 2;

    static final int ATTRIBUTE = 2;

    static final int MISMATCH = -10;

    private static final Map<String, Set<String>> CATEGORY_WORDS = Map.of("HARNESS", Set.of("harness"), "APPAREL",
            Set.of("jacket", "coat", "raincoat"), "CRATE", Set.of("crate", "kennel", "cage"), "BED", Set.of("bed"),
            "COLLAR", Set.of("collar"), "TOY", Set.of("toy"), "LEASH", Set.of("leash", "lead"), "TREATS",
            Set.of("treat", "snack"));

    /** Words in product names that say nothing about which product it is. */
    private static final Set<String> GENERIC = Set.of("dog", "pet", "pack", "the", "and", "for", "with", "everyday");

    private static final java.util.regex.Pattern INCHES = java.util.regex.Pattern
            .compile("\\b(\\d{1,3})\\s*-?\\s*(?:inches|inch|in\\b|\")");

    private ItemMatcher() {
    }

    public static Result match(String description, List<Line> lines) {
        List<String> words = words(description);
        Set<String> sizes = sizesMentioned(description, words, lines);
        Set<String> colors = colorsMentioned(words, lines);
        List<Integer> scores = lines.stream().map(line -> score(line, words, sizes, colors)).toList();

        if (lines.size() == 1) {
            return scores.getFirst() > MISMATCH / 2 ? new Matched(lines.getFirst()) : new NotFound(lines);
        }
        int best = scores.stream().max(Comparator.naturalOrder()).orElse(0);
        if (best <= 0) {
            return new NotFound(lines);
        }
        List<Line> top = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (scores.get(i) == best) {
                top.add(lines.get(i));
            }
        }
        return top.size() == 1 ? new Matched(top.getFirst()) : new Ambiguous(top);
    }

    static int score(Line line, List<String> words, Set<String> sizes, Set<String> colors) {
        int score = 0;
        Set<String> categoryWords = CATEGORY_WORDS.getOrDefault(line.category(), Set.of());
        if (words.stream().anyMatch(categoryWords::contains)) {
            score += CATEGORY;
        }
        Set<String> nameWords = new LinkedHashSet<>(words(line.item().productName()));
        nameWords.removeAll(GENERIC);
        nameWords.removeAll(categoryWords);
        nameWords.removeIf(word -> word.length() < 3 || Character.isDigit(word.charAt(0)));
        score += NAME_WORD * (int) nameWords.stream().filter(words::contains).count();
        if (!sizes.isEmpty()) {
            String size = Sizes.canonical(line.item().size());
            score += size != null && sizes.contains(size) ? ATTRIBUTE : MISMATCH;
        }
        if (!colors.isEmpty()) {
            String color = line.item().color() == null ? null : line.item().color().toLowerCase(Locale.ROOT);
            score += color != null && colors.contains(color) ? ATTRIBUTE : MISMATCH;
        }
        return score;
    }

    static List<String> words(String text) {
        if (text == null) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT).replace("’", "'").replace("'s", "");
        return Arrays.stream(lower.split("[^a-z0-9/]+")).filter(w -> !w.isBlank()).map(ItemMatcher::stem).toList();
    }

    private static String stem(String word) {
        return word.length() > 3 && word.endsWith("s") && !word.endsWith("ss") ? word.substring(0, word.length() - 1)
                : word;
    }

    private static Set<String> sizesMentioned(String description, List<String> words, List<Line> lines) {
        Set<String> sizes = new LinkedHashSet<>();
        String lower = description == null ? "" : description.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\bextra[ -]?large\\b.*") || lower.matches(".*\\bx-large\\b.*")) {
            sizes.add("XL");
            lower = lower.replaceAll("\\bextra[ -]?large\\b|\\bx-large\\b", " ");
        }
        if (lower.matches(".*\\bextra[ -]?small\\b.*") || lower.matches(".*\\bx-small\\b.*")) {
            sizes.add("XS");
            lower = lower.replaceAll("\\bextra[ -]?small\\b|\\bx-small\\b", " ");
        }
        for (String word : lower.split("[^a-z0-9/]+")) {
            if (Set.of("small", "medium", "med", "large", "xl", "xs", "s/m").contains(word)) {
                sizes.add(Sizes.canonical(word));
            }
        }
        for (String token : (description == null ? "" : description).split("[^A-Za-z0-9/]+")) {
            if (Set.of("S", "M", "L", "XL", "XS").contains(token)) {
                sizes.add(token);
            }
        }
        java.util.regex.Matcher inches = INCHES.matcher(lower);
        while (inches.find()) {
            sizes.add(inches.group(1) + " IN");
        }
        Set<String> inchSizes = lines.stream().map(line -> Sizes.canonical(line.item().size()))
                .filter(size -> size != null && size.endsWith(" IN")).collect(Collectors.toSet());
        for (String word : words) {
            if (word.chars().allMatch(Character::isDigit) && inchSizes.contains(word + " IN")) {
                sizes.add(word + " IN");
            }
        }
        return sizes;
    }

    private static Set<String> colorsMentioned(List<String> words, List<Line> lines) {
        Set<String> known = lines.stream().map(line -> line.item().color()).filter(color -> color != null)
                .map(color -> color.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        known.addAll(Set.of("blue", "red", "black", "yellow", "navy", "green", "pink", "white", "gray", "grey"));
        return words.stream().filter(known::contains).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
