package com.davidparry.tooldesign.benchmark.verify;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the checkable values out of free text: amounts, dates, SKUs, and quote and action references. The trace side
 * reads JSON tool results; the answer side reads what the model wrote.
 */
final class Extract {

    static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 11, 6);

    private static final Pattern DOLLARS = Pattern.compile("\\$\\s?(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d{1,2})?");

    private static final Pattern DECIMAL = Pattern.compile("(?<![\\d.])(\\d+\\.\\d{2})(?![\\d])");

    private static final Pattern ISO_DATE = Pattern.compile("\\b(20\\d{2})-(\\d{2})-(\\d{2})");

    private static final Pattern MONTH_DATE = Pattern.compile("\\b(January|February|March|April|May|June|July|"
            + "August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\.?\\s+"
            + "(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(20\\d{2}))?\\b");

    private static final Pattern WEEKDAY = Pattern.compile("\\b(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|"
            + "Sunday)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern WEEKDAY_DATE = Pattern.compile("\\b(Monday|Tuesday|Wednesday|Thursday|Friday|"
            + "Saturday|Sunday|Mon|Tues|Tue|Wed|Thurs|Thur|Thu|Fri|Sat|Sun)\\.?,?\\s+(?:the\\s+)?(January|February|"
            + "March|April|May|June|July|August|September|October|"
            + "November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?"
            + "(?:,?\\s+(20\\d{2}))?\\b");

    private static final Pattern SKU = Pattern.compile("\\b[A-Z]{3}-[A-Z0-9]{1,5}(?:-[A-Z]{3})?\\b");

    private static final Pattern QUOTE = Pattern.compile("\\b(?:EQ|RQ)-\\d+\\b");

    private static final Pattern ACTION = Pattern.compile("\\b(?:EX|RT)-\\d+\\b");

    private Extract() {
    }

    /** Dollar amounts written as $15, $11.99, or $1,200.00. */
    static Set<BigDecimal> dollars(String text) {
        Set<BigDecimal> amounts = new LinkedHashSet<>();
        if (text == null) {
            return amounts;
        }
        Matcher m = DOLLARS.matcher(text);
        while (m.find()) {
            amounts.add(money(m.group(1).replace(",", "") + (m.group(2) == null ? "" : m.group(2))));
        }
        return amounts;
    }

    /** Two-decimal numbers, which is how every amount appears in a tool result. */
    static Set<BigDecimal> decimals(String text) {
        Set<BigDecimal> amounts = new LinkedHashSet<>();
        if (text == null) {
            return amounts;
        }
        Matcher m = DECIMAL.matcher(text);
        while (m.find()) {
            amounts.add(money(m.group(1)));
        }
        return amounts;
    }

    /** ISO dates, including the date part of a timestamp, and dates written as "November 12" or "Nov 12, 2026". */
    static Set<LocalDate> dates(String text) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        if (text == null) {
            return dates;
        }
        Matcher iso = ISO_DATE.matcher(text);
        while (iso.find()) {
            date(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)),
                    dates);
        }
        Matcher written = MONTH_DATE.matcher(text);
        while (written.find()) {
            int year = written.group(3) == null ? BUSINESS_DATE.getYear() : Integer.parseInt(written.group(3));
            date(year, month(written.group(1)).getValue(), Integer.parseInt(written.group(2)), dates);
        }
        return dates;
    }

    /**
     * The next date for each weekday the text names, counted from the business date, so a customer's "this Saturday"
     * grounds the model's "Saturday, November 7".
     */
    static Set<LocalDate> weekdays(String text) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        if (text == null) {
            return dates;
        }
        Matcher m = WEEKDAY.matcher(text);
        while (m.find()) {
            DayOfWeek day = DayOfWeek.valueOf(m.group(1).toUpperCase(Locale.ROOT));
            dates.add(BUSINESS_DATE.with(TemporalAdjusters.nextOrSame(day)));
        }
        return dates;
    }

    /** A weekday written next to a date, as in "Monday, November 10" or "Tue, Nov 10". */
    record NamedDay(DayOfWeek said, LocalDate date) {

        boolean matches() {
            return this.date.getDayOfWeek() == this.said;
        }
    }

    static Set<NamedDay> namedDays(String text) {
        Set<NamedDay> days = new LinkedHashSet<>();
        if (text == null) {
            return days;
        }
        Matcher m = WEEKDAY_DATE.matcher(text);
        while (m.find()) {
            int year = m.group(4) == null ? BUSINESS_DATE.getYear() : Integer.parseInt(m.group(4));
            Set<LocalDate> date = new LinkedHashSet<>();
            date(year, month(m.group(2)).getValue(), Integer.parseInt(m.group(3)), date);
            date.forEach(d -> days.add(new NamedDay(weekday(m.group(1)), d)));
        }
        return days;
    }

    static Set<String> skus(String text) {
        return all(SKU, text);
    }

    static Set<String> quotes(String text) {
        return all(QUOTE, text);
    }

    static Set<String> actions(String text) {
        return all(ACTION, text);
    }

    /** Parses "11.99", "$11.99", or "15" to an amount at scale 2, or null when it isn't one. */
    static BigDecimal amount(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().replace("$", "").replace(",", "").replace("USD", "").strip();
        try {
            return money(text);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses a card date given as YYYY-MM-DD or "November 12", or null. */
    static LocalDate date(Object value) {
        if (value == null) {
            return null;
        }
        Set<LocalDate> found = dates(value.toString());
        return found.isEmpty() ? null : found.iterator().next();
    }

    private static BigDecimal money(String text) {
        return new BigDecimal(text).setScale(2, RoundingMode.HALF_UP);
    }

    private static void date(int year, int month, int day, Set<LocalDate> dates) {
        try {
            dates.add(LocalDate.of(year, month, day));
        }
        catch (DateTimeException e) {
            // not a real date, so not a claim about one
        }
    }

    private static Month month(String name) {
        String prefix = name.substring(0, 3).toUpperCase(Locale.ROOT);
        for (Month month : Month.values()) {
            if (month.name().startsWith(prefix)) {
                return month;
            }
        }
        throw new IllegalArgumentException(name);
    }

    private static DayOfWeek weekday(String name) {
        String prefix = name.substring(0, 3).toUpperCase(Locale.ROOT);
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day.name().startsWith(prefix)) {
                return day;
            }
        }
        throw new IllegalArgumentException(name);
    }

    private static Set<String> all(Pattern pattern, String text) {
        Set<String> found = new LinkedHashSet<>();
        if (text == null) {
            return found;
        }
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }
}
