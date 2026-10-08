package tz.co.msingi.cims.notification;

import tz.co.msingi.cims.common.exception.BusinessRuleException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fills {placeholders} in message texts, formatting amounts and dates for the message's language. */
public final class MessageRender {

    public static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final String[] SW_MONTHS = {"Januari", "Februari", "Machi", "Aprili", "Mei", "Juni", "Julai", "Agosti", "Septemba",
            "Oktoba", "Novemba", "Desemba"};

    private MessageRender() {
    }

    public static Set<String> placeholders(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Throws unless every placeholder is one of {@code allowed} and every {@code required} one is present. */
    public static void check(String label, String text, Collection<String> allowed, Collection<String> required) {
        if (text == null || text.isBlank()) throw new BusinessRuleException("INVALID_TEMPLATE", label + " is empty");
        Set<String> used = placeholders(text);
        for (String p : used) {
            if (!allowed.contains(p)) {
                throw new BusinessRuleException("UNKNOWN_PLACEHOLDER", label + ": {" + p + "} is not available here. Available: "
                        + String.join(", ", allowed.stream().map(a -> "{" + a + "}").toList()));
            }
        }
        for (String r : required) {
            if (!used.contains(r)) throw new BusinessRuleException("MISSING_PLACEHOLDER", label + " must include {" + r + "}");
        }
    }

    public static String render(String text, Map<String, ?> values, String language) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = values.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(format(v, language)));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll(" {2,}", " ").trim();
    }

    static String format(Object v, String language) {
        if (v == null) return "";
        if (v instanceof BigDecimal a) {
            BigDecimal x = a.setScale(2, RoundingMode.HALF_UP);
            return "TZS " + (x.stripTrailingZeros().scale() <= 0 ? String.format(Locale.ENGLISH, "%,d", x.longValue())
                    : String.format(Locale.ENGLISH, "%,.2f", x));
        }
        if (v instanceof OffsetDateTime t) v = t.atZoneSameInstant(ZONE).toLocalDate();
        if (v instanceof LocalDate d) {
            return "SW".equals(language) ? d.getDayOfMonth() + " " + SW_MONTHS[d.getMonthValue() - 1] + " " + d.getYear()
                    : DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).format(d);
        }
        return String.valueOf(v);
    }
}
