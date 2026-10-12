package tz.co.msingi.cims.graduation;

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

/** Renders award certificates (FR-GRD-03) with their number, verification code and QR code; one A4 landscape page each. */
public final class CertificateRenderer {

    private static final HtmlTemplate TEMPLATE = HtmlTemplate.classpath("templates/certificate.html");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final Pattern HEX_COLOUR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    public record Certificate(Tenant tenant, String name, String registrationNo, String award, String programme, String classification,
                              String completed, LocalDate awardedOn, String certificateNo, String verificationCode, String verificationUrl,
                              boolean reprint) {
    }

    private CertificateRenderer() {
    }

    public static byte[] pdf(List<Certificate> certs) {
        return PdfRenderer.render(html(certs), "award certificates");
    }

    static String html(List<Certificate> certs) {
        Tenant t = certs.get(0).tenant();
        Map<String, Object> branding = t.getBranding() == null ? Map.of() : t.getBranding();
        String colour = String.valueOf(branding.getOrDefault("primaryColor", ""));
        String shortName = t.getShortName() == null || t.getShortName().isBlank()
                ? Stream.of(t.getName().split("\\s+")).filter(w -> !w.isEmpty()).map(w -> w.substring(0, 1)).collect(Collectors.joining())
                : t.getShortName();
        if (shortName.length() > 5) shortName = shortName.substring(0, 5);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < certs.size(); i++) {
            Certificate c = certs.get(i);
            String qr = "<img alt=\"QR code\" style=\"width: 62pt; height: 62pt;\" src=\"data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(QrCodes.png(c.verificationUrl(), 300)) + "\" />";
            sb.append("<div class=\"page").append(i == certs.size() - 1 ? " last" : "").append("\">")
                    .append("<div class=\"center\"><div class=\"badge\">").append(e(shortName)).append("</div>")
                    .append("<div class=\"inst\">").append(e(t.getName())).append("</div>")
                    .append("<div class=\"reg\">Registered by the National Council for Technical and Vocational Education and Training")
                    .append(t.getNactvetRegNo() == null ? "" : " · " + e(t.getNactvetRegNo())).append("</div>")
                    .append("<div class=\"certify\">This is to certify that</div>")
                    .append("<div class=\"name\">").append(e(c.name())).append("</div>")
                    .append("<div class=\"regno\">Registration no. ").append(e(c.registrationNo())).append("</div>")
                    .append("<div class=\"having\">having completed the approved programme of study and satisfied the examiners, was awarded the</div>")
                    .append("<div class=\"award\">").append(e(c.award())).append("</div>")
                    .append("<div class=\"programme\">").append(e(c.programme())).append("</div>");
            if (c.classification() != null && !c.classification().isBlank()) {
                sb.append("<div class=\"classification\">").append(e(c.classification())).append("</div>");
            }
            sb.append("<div class=\"date\">Awarded on ").append(e(DATE.format(c.awardedOn()))).append(" · completed ").append(e(c.completed())).append("</div>")
                    .append("</div>")
                    .append("<table class=\"foot\"><tr>")
                    .append("<td style=\"width: 34%;\"><table style=\"border-collapse: collapse;\"><tr><td style=\"padding-right: 6pt;\">").append(qr)
                    .append("</td><td class=\"verify\"><div>Verify this certificate</div><div class=\"code\">").append(e(c.verificationCode()))
                    .append("</div><div>at ").append(e(urlBase(c.verificationUrl()))).append("</div><div class=\"no\">Certificate no. ")
                    .append(e(c.certificateNo())).append("</div>").append(c.reprint() ? "<div class=\"copy\">REPRINT</div>" : "")
                    .append("</td></tr></table></td>")
                    .append("<td style=\"width: 33%;\"><div class=\"line\">Registrar</div></td>")
                    .append("<td style=\"width: 33%;\"><div class=\"line\">Principal</div></td>")
                    .append("</tr></table></div>");
        }
        return TEMPLATE.render(Map.of("primaryColor", HEX_COLOUR.matcher(colour).matches() ? colour : "#1F4E8C"), Map.of("pages", sb.toString()));
    }

    private static String e(String s) {
        return HtmlTemplate.escape(s == null ? "" : s);
    }

    private static String urlBase(String url) {
        try {
            URI u = URI.create(url);
            return u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + "/verify";
        } catch (IllegalArgumentException ex) {
            return url;
        }
    }
}
