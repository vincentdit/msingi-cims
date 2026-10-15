package tz.co.msingi.cims.common.document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Letter wording the institution writes as plain text (FR-ADM-S05), turned into safe HTML for the PDF renderer:
 * paragraphs are separated by a blank line, lines starting with "- " make a bulleted list and "1. " a numbered one,
 * **text** is bold, and {name} inserts a value. Everything else is escaped, so no markup can be injected.
 */
public final class LetterWording {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)}");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*•]\\s+(.*)$");
    private static final Pattern NUMBER = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)$");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");

    private LetterWording() {
    }

    /** Placeholders used in the text that are not in the allowed list. */
    public static Set<String> unknownPlaceholders(String text, Set<String> allowed) {
        Set<String> unknown = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(text == null ? "" : text);
        while (m.find()) {
            if (!allowed.contains(m.group(1))) unknown.add(m.group(1));
        }
        return unknown;
    }

    public static String html(String text, Map<String, String> values) {
        if (text == null || text.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        for (String block : text.replace("\r\n", "\n").trim().split("\\n\\s*\\n")) {
            List<String> lines = block.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
            if (lines.isEmpty()) continue;
            if (lines.stream().allMatch(l -> BULLET.matcher(l).matches())) out.append(list("ul", lines, BULLET, values));
            else if (lines.stream().allMatch(l -> NUMBER.matcher(l).matches())) out.append(list("ol", lines, NUMBER, values));
            else out.append("<p>").append(inline(String.join(" ", lines), values)).append("</p>");
        }
        return out.toString();
    }

    private static String list(String tag, List<String> lines, Pattern item, Map<String, String> values) {
        StringBuilder b = new StringBuilder("<").append(tag).append(">");
        for (String l : lines) {
            Matcher m = item.matcher(l);
            m.matches();
            b.append("<li>").append(inline(m.group(1), values)).append("</li>");
        }
        return b.append("</").append(tag).append(">").toString();
    }

    /** Placeholders become markers, the text is escaped, bold is applied, then the escaped values go in. */
    private static String inline(String s, Map<String, String> values) {
        List<String> inserted = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(s.replace("\u0001", "").replace("\u0002", ""));
        StringBuilder marked = new StringBuilder();
        while (m.find()) {
            inserted.add(values.getOrDefault(m.group(1), ""));
            m.appendReplacement(marked, Matcher.quoteReplacement("\u0001" + (inserted.size() - 1) + "\u0002"));
        }
        m.appendTail(marked);
        String escaped = HtmlTemplate.escape(marked.toString());
        escaped = BOLD.matcher(escaped).replaceAll("<strong>$1</strong>");
        for (int i = 0; i < inserted.size(); i++) {
            escaped = escaped.replace("\u0001" + i + "\u0002", HtmlTemplate.escape(inserted.get(i) == null ? "" : inserted.get(i)));
        }
        return escaped;
    }
}
