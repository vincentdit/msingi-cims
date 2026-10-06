package tz.co.msingi.cims.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registration number patterns (FR-REG-02), e.g. {@code {INST}/{FAMILY}{LEVEL}/{YY}/{SEQ:4}} → DEMO/NTA6/27/0001.
 * <p>
 * Tokens: {@code {INST}} institution code, {@code {PROG}} programme code, {@code {FAMILY}} NTA or NVA,
 * {@code {LEVEL}} award level, {@code {YEAR}} / {@code {YY}} entry year, {@code {INTAKE}} intake code initial
 * (S for September, M for March), and {@code {SEQ}} or {@code {SEQ:n}} the running number (n digits, 3–6, default 4).
 * The running number restarts for every distinct prefix, so "per year" or "per programme per year" numbering
 * follows from the tokens chosen. Literals may use letters, digits and / - . _
 */
public final class RegistrationNumberPattern {

    public static final String DEFAULT = "{INST}/{FAMILY}{LEVEL}/{YY}/{SEQ:4}";
    public static final int MAX_LENGTH = 30;

    private static final Pattern TOKEN = Pattern.compile("\\{([A-Z]+)(?::(\\d))?}");
    private static final Pattern LITERAL = Pattern.compile("[A-Za-z0-9/\\-._]*");
    private static final List<String> KNOWN = List.of("INST", "PROG", "FAMILY", "LEVEL", "YEAR", "YY", "INTAKE", "SEQ");

    private final String pattern;
    private final int seqWidth;

    private RegistrationNumberPattern(String pattern, int seqWidth) {
        this.pattern = pattern;
        this.seqWidth = seqWidth;
    }

    /** Parses and validates a pattern; throws IllegalArgumentException with a message fit for the user. */
    public static RegistrationNumberPattern parse(String pattern) {
        if (pattern == null || pattern.isBlank()) throw new IllegalArgumentException("The pattern is empty");
        String p = pattern.trim();
        Matcher m = TOKEN.matcher(p);
        int last = 0, seqCount = 0, width = 4;
        List<String> problems = new ArrayList<>();
        while (m.find()) {
            checkLiteral(p.substring(last, m.start()), problems);
            String name = m.group(1);
            if (!KNOWN.contains(name)) problems.add("Unknown token {" + name + "}");
            if (name.equals("SEQ")) {
                seqCount++;
                if (m.group(2) != null) width = Integer.parseInt(m.group(2));
                if (width < 3 || width > 6) problems.add("{SEQ:n} needs 3 to 6 digits");
            } else if (m.group(2) != null) {
                problems.add("Only {SEQ} takes a width");
            }
            last = m.end();
        }
        checkLiteral(p.substring(last), problems);
        if (seqCount != 1) problems.add("The pattern must contain {SEQ} exactly once");
        RegistrationNumberPattern rp = new RegistrationNumberPattern(p, width);
        if (problems.isEmpty()) {
            String longest = rp.render(new Values("XXXXXXXXXX", "XXXXXXXXXX", "NTA", 8, 2027, "SEPTEMBER"), (long) Math.pow(10, width) - 1);
            if (longest.length() > MAX_LENGTH) {
                problems.add("Numbers could be up to " + longest.length() + " characters; the maximum is " + MAX_LENGTH);
            }
        }
        if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
        return rp;
    }

    private static void checkLiteral(String literal, List<String> problems) {
        if (literal.contains("{") || literal.contains("}")) problems.add("Unbalanced braces");
        else if (!LITERAL.matcher(literal).matches()) problems.add("Only letters, digits and / - . _ may appear between tokens");
    }

    public String pattern() {
        return pattern;
    }

    /** The number without the running number: every distinct prefix has its own sequence. */
    public String prefix(Values v) {
        return substitute(v, "{SEQ}");
    }

    public String render(Values v, long seq) {
        return substitute(v, String.format("%0" + seqWidth + "d", seq));
    }

    private String substitute(Values v, String seqText) {
        Map<String, String> values = Map.of(
                "INST", v.institutionCode().toUpperCase(Locale.ROOT),
                "PROG", v.programmeCode().toUpperCase(Locale.ROOT),
                "FAMILY", v.awardFamily(),
                "LEVEL", String.valueOf(v.awardLevel()),
                "YEAR", String.valueOf(v.entryYear()),
                "YY", String.format("%02d", v.entryYear() % 100),
                "INTAKE", v.intakeCode() == null || v.intakeCode().isEmpty() ? "X" : v.intakeCode().substring(0, 1).toUpperCase(Locale.ROOT),
                "SEQ", seqText);
        Matcher m = TOKEN.matcher(pattern);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(values.get(m.group(1))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** What a number is made of. */
    public record Values(String institutionCode, String programmeCode, String awardFamily, int awardLevel, int entryYear, String intakeCode) {
    }
}
