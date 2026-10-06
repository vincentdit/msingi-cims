package tz.co.msingi.cims.common.document;

import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, safe HTML templates for documents. {@code {{name}}} inserts a value HTML-escaped;
 * {@code {{{name}}}} inserts markup the caller built from escaped values. An unknown name fails loudly,
 * so a template typo never prints a blank on an official document.
 */
public final class HtmlTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}}|\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");

    private final String source;

    private HtmlTemplate(String source) {
        this.source = source;
    }

    public static HtmlTemplate classpath(String path) {
        try (InputStream in = HtmlTemplate.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Template not found: " + path);
            return new HtmlTemplate(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static HtmlTemplate of(String source) {
        return new HtmlTemplate(source);
    }

    public String render(Map<String, String> values, Map<String, String> markup) {
        Matcher m = PLACEHOLDER.matcher(source);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement;
            if (m.group(1) != null) {
                if (!markup.containsKey(m.group(1))) throw new IllegalArgumentException("No markup for {{{" + m.group(1) + "}}}");
                replacement = nullToEmpty(markup.get(m.group(1)));
            } else {
                if (!values.containsKey(m.group(2))) throw new IllegalArgumentException("No value for {{" + m.group(2) + "}}");
                replacement = escape(values.get(m.group(2)));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Escapes text for XHTML (the PDF renderer parses strict XML). */
    public static String escape(String s) {
        return s == null ? "" : HtmlUtils.htmlEscapeDecimal(s, "UTF-8");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
