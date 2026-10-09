package tz.co.msingi.cims.exams;

import tz.co.msingi.cims.common.document.HtmlTemplate;
import tz.co.msingi.cims.common.document.PdfRenderer;
import tz.co.msingi.cims.common.document.QrCodes;

import java.math.BigDecimal;
import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Renders a statement of results / provisional transcript to PDF from its issued snapshot. */
public final class TranscriptRenderer {

    private static final HtmlTemplate TEMPLATE = HtmlTemplate.classpath("templates/transcript.html");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final Pattern HEX_COLOUR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private TranscriptRenderer() {
    }

    public static byte[] pdf(TranscriptContent c) {
        return PdfRenderer.render(html(c), "statement of results " + c.documentNo());
    }

    static String html(TranscriptContent c) {
        Map<String, String> v = new HashMap<>();
        v.put("institutionName", c.institutionName());
        String shortName = c.institutionShortName() == null || c.institutionShortName().isBlank()
                ? Stream.of(c.institutionName().split("\\s+")).filter(w -> !w.isEmpty()).map(w -> w.substring(0, 1)).collect(Collectors.joining())
                : c.institutionShortName();
        v.put("institutionShortName", shortName.length() > 5 ? shortName.substring(0, 5) : shortName);
        v.put("nactvetNo", nz(c.institutionNactvetNo()));
        v.put("contactLine", nz(c.institutionContact()));
        v.put("primaryColor", c.primaryColor() != null && HEX_COLOUR.matcher(c.primaryColor()).matches() ? c.primaryColor() : "#1F4E8C");
        v.put("documentNo", c.documentNo());
        v.put("issuedOn", DATE.format(c.issuedOn()));
        v.put("studentName", c.studentName());
        v.put("registrationNo", c.registrationNo());
        v.put("programmeName", c.programmeName());
        v.put("awardName", nz(c.awardName()));
        v.put("sex", "F".equals(c.sex()) ? "Female" : "M".equals(c.sex()) ? "Male" : nz(c.sex()));
        v.put("dateOfBirth", c.dateOfBirth() == null ? "" : DATE.format(c.dateOfBirth()));
        v.put("entryYear", nz(c.entryYear()));
        v.put("nactvetVerificationNo", nz(c.nactvetVerificationNo()));
        v.put("creditsEarned", plain(c.creditsEarned()));
        v.put("cgpa", c.cgpa() == null ? "—" : c.cgpa().toPlainString());
        v.put("classification", c.classification() == null ? "—" : c.classification());
        v.put("gradingKey", nz(c.regulation()));
        v.put("verificationCode", c.verificationCode());
        v.put("verificationUrlBase", urlBase(c.verificationUrl()));
        Map<String, String> m = new HashMap<>();
        m.put("qrImage", "<img alt=\"QR code\" style=\"width: 70pt; height: 70pt;\" src=\"data:image/png;base64,"
                + Base64.getEncoder().encodeToString(QrCodes.png(c.verificationUrl(), 300)) + "\" />");
        StringBuilder sb = new StringBuilder();
        for (TranscriptContent.Semester s : c.semesters()) {
            sb.append("<h2>").append(e(s.name() + " · " + s.academicYear() + " · Year " + s.yearOfStudy()))
                    .append(s.afterResits() ? " <span class=\"muted small\">(after supplementary / special examinations)</span>" : "").append("</h2>");
            sb.append("<table class=\"marks\"><tr><th style=\"width: 14%;\">Code</th><th>Module</th><th class=\"r\" style=\"width: 9%;\">Credits</th>")
                    .append("<th class=\"c\" style=\"width: 9%;\">Grade</th><th class=\"r\" style=\"width: 9%;\">Points</th><th style=\"width: 16%;\">Remark</th></tr>");
            for (TranscriptContent.Module mod : s.modules()) {
                boolean fail = !"PASS".equals(mod.remark());
                sb.append("<tr><td>").append(e(mod.code())).append("</td><td>").append(e(mod.title())).append("</td><td class=\"r\">")
                        .append(e(plain(mod.credits()))).append("</td><td class=\"c").append(fail ? " fail" : "").append("\"><b>")
                        .append(e(mod.grade() == null ? "—" : mod.grade())).append("</b></td><td class=\"r\">")
                        .append(e(mod.points() == null ? "—" : plain(mod.points()))).append("</td><td").append(fail ? " class=\"fail\"" : "").append(">")
                        .append(e(remark(mod.remark()) + (mod.attempt() == null ? "" : " (" + ("SPECIAL".equals(mod.attempt()) ? "special" : "supp.") + ")")))
                        .append("</td></tr>");
            }
            sb.append("<tr class=\"sum\"><td></td><td>Credits passed ").append(e(plain(s.creditsPassed()))).append(" of ").append(e(plain(s.credits())))
                    .append("</td><td></td><td class=\"c\">GPA</td><td class=\"r\">").append(e(s.gpa() == null ? "—" : s.gpa().toPlainString()))
                    .append("</td><td>").append(e(s.decision())).append("</td></tr></table>");
        }
        m.put("semesters", sb.toString());
        return TEMPLATE.render(v, m);
    }

    private static String remark(String r) {
        return switch (r) {
            case "PASS" -> "Pass";
            case "FAIL" -> "Fail";
            case "ABSENT" -> "Absent";
            default -> "Incomplete";
        };
    }

    private static String e(String s) {
        return HtmlTemplate.escape(s == null ? "" : s);
    }

    private static String plain(BigDecimal n) {
        return n == null ? "0" : n.stripTrailingZeros().toPlainString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
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
