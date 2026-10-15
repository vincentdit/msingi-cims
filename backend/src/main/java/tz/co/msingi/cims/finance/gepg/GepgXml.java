package tz.co.msingi.cims.finance.gepg;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * GePG messages: {@code <Gepg><content>…</content><gepgSignature>base64</gepgSignature></Gepg>}, where the signature is
 * SHA1withRSA over the exact text of the content element. Built as text (so the signed bytes are exactly what is sent) and
 * read with a parser that refuses DTDs and external entities.
 */
public final class GepgXml {

    private GepgXml() {
    }

    public static String el(String name, Object value) {
        return "<" + name + ">" + escape(value == null ? "" : String.valueOf(value)) + "</" + name + ">";
    }

    public static String envelope(String content, String signature) {
        return "<Gepg>" + content + "<gepgSignature>" + signature + "</gepgSignature></Gepg>";
    }

    /** The content element exactly as received, from its opening to its closing tag, or null. */
    public static String inner(String xml, String tag) {
        if (xml == null) return null;
        int a = xml.indexOf("<" + tag + ">");
        int b = xml.indexOf("</" + tag + ">");
        if (a < 0 || b < a) return null;
        return xml.substring(a, b + tag.length() + 3);
    }

    public static String signature(String xml) {
        String s = inner(xml, "gepgSignature");
        return s == null ? null : s.substring("<gepgSignature>".length(), s.length() - "</gepgSignature>".length()).trim();
    }

    /** The name of the first element inside {@code <Gepg>}, i.e. what kind of message this is. */
    public static String kind(String xml) {
        Element root = parse(xml);
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && !"gepgSignature".equals(e.getTagName())) return e.getTagName();
        }
        return null;
    }

    public static Element parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a readable GePG message: " + e.getMessage(), e);
        }
    }

    public static String text(Element parent, String tag) {
        NodeList l = parent.getElementsByTagName(tag);
        if (l.getLength() == 0) return null;
        String t = l.item(0).getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }

    public static List<Element> all(Element parent, String tag) {
        NodeList l = parent.getElementsByTagName(tag);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < l.getLength(); i++) out.add((Element) l.item(i));
        return out;
    }

    public static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
