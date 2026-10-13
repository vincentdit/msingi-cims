package tz.co.msingi.cims.registry.idcard;

import tz.co.msingi.cims.common.document.HtmlTemplate;
import tz.co.msingi.cims.common.document.PdfRenderer;
import tz.co.msingi.cims.common.document.QrCodes;
import tz.co.msingi.cims.platform.tenancy.Tenant;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Renders student ID cards at ID-1 / CR80 size (85.6 × 54 mm): a front page and a back page for each card. */
public final class IdCardRenderer {

    private static final HtmlTemplate TEMPLATE = HtmlTemplate.classpath("templates/id-card.html");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Pattern HEX_COLOUR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    public record Card(String name, String registrationNo, String programme, String programmeCode, String cardNo, LocalDate validUntil,
                       String verificationCode, String verificationUrl, byte[] photo, String photoType, String phone) {
    }

    private IdCardRenderer() {
    }

    public static byte[] pdf(List<Card> cards, Tenant t) {
        return PdfRenderer.render(html(cards, t), "student ID cards");
    }

    static String html(List<Card> cards, Tenant t) {
        Map<String, Object> branding = t.getBranding() == null ? Map.of() : t.getBranding();
        String colour = String.valueOf(branding.getOrDefault("primaryColor", ""));
        String shortName = t.getShortName() == null || t.getShortName().isBlank()
                ? Stream.of(t.getName().split("\\s+")).filter(w -> !w.isEmpty()).map(w -> w.substring(0, 1)).collect(Collectors.joining())
                : t.getShortName();
        if (shortName.length() > 5) shortName = shortName.substring(0, 5);
        String contact = Stream.of(t.getContactPhone(), t.getContactEmail()).filter(x -> x != null && !x.isBlank()).collect(Collectors.joining(" · "));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            boolean last = i == cards.size() - 1;
            String photo = c.photo() == null
                    ? "<div class=\"nophoto\">PHOTO</div>"
                    : "<img class=\"photo\" alt=\"Photo\" src=\"data:" + e(c.photoType()) + ";base64," + Base64.getEncoder().encodeToString(c.photo()) + "\" />";
            sb.append("<div class=\"card\">")
                    .append("<table class=\"band\"><tr><td style=\"width: 26pt;\"><div class=\"badge\">").append(e(shortName)).append("</div></td>")
                    .append("<td><div class=\"inst\">").append(e(t.getName())).append("</div><div class=\"kind\">STUDENT IDENTITY CARD</div></td></tr></table>")
                    .append("<table class=\"body\"><tr><td style=\"width: 62pt;\">").append(photo).append("</td><td>")
                    .append("<div class=\"name\">").append(e(c.name())).append("</div>")
                    .append("<div class=\"label\">Registration no.</div><div class=\"value\">").append(e(c.registrationNo())).append("</div>")
                    .append("<div class=\"label\">Programme</div><div class=\"value small\">").append(e(c.programme())).append("</div>")
                    .append("<table class=\"pair\"><tr><td><div class=\"label\">Card no.</div><div class=\"value\">").append(e(c.cardNo()))
                    .append("</div></td><td><div class=\"label\">Valid until</div><div class=\"value\">").append(e(DATE.format(c.validUntil())))
                    .append("</div></td></tr></table>")
                    .append("</td></tr></table>")
                    .append("<div class=\"foot\"><span class=\"right\">").append(e(c.programmeCode())).append("</span>")
                    .append(t.getNactvetRegNo() == null ? "Registered by NACTVET" : "NACTVET " + e(t.getNactvetRegNo())).append("</div></div>");
            String qr = "<img class=\"qr\" alt=\"QR code\" src=\"data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(QrCodes.png(c.verificationUrl(), 260)) + "\" />";
            sb.append("<div class=\"card back").append(last ? " last" : "").append("\">")
                    .append("<table class=\"backt\"><tr><td style=\"width: 70pt;\">").append(qr).append("</td><td class=\"terms\">")
                    .append("<div class=\"strong\">This card is the property of ").append(e(t.getName())).append(".</div>")
                    .append("<div>It identifies the holder as a registered student and must be shown on request. It is not transferable.</div>")
                    .append("<div class=\"gap\">If found, please return it to the institution").append(contact.isEmpty() ? "" : " (" + e(contact) + ")")
                    .append(".</div>")
                    .append("<div class=\"gap\">Scan the QR code or visit ").append(e(urlBase(c.verificationUrl())))
                    .append(" and enter</div><div class=\"code\">").append(e(c.verificationCode())).append("</div>")
                    .append("</td></tr></table>")
                    .append("<table class=\"sign\"><tr><td><div class=\"line\">Holder's signature</div></td><td><div class=\"line\">Registrar</div></td></tr></table>")
                    .append("</div>");
        }
        return TEMPLATE.render(Map.of("primaryColor", HEX_COLOUR.matcher(colour).matches() ? colour : "#1F4E8C"), Map.of("cards", sb.toString()));
    }

    private static String urlBase(String url) {
        try {
            URI u = URI.create(url);
            return u.getHost() == null ? url : u.getHost() + "/verify";
        } catch (IllegalArgumentException ex) {
            return url;
        }
    }

    private static String e(String s) {
        return HtmlTemplate.escape(s == null ? "" : s);
    }
}
