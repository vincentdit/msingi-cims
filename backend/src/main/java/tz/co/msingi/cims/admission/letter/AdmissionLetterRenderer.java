package tz.co.msingi.cims.admission.letter;

import tz.co.msingi.cims.common.document.HtmlTemplate;
import tz.co.msingi.cims.common.document.PdfRenderer;
import tz.co.msingi.cims.common.document.QrCodes;

import tz.co.msingi.cims.common.document.LetterWording;
import tz.co.msingi.cims.platform.letters.LetterTemplateService;
import java.net.URI;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Renders an admission letter with its joining instructions to PDF from the issued snapshot. */
public final class AdmissionLetterRenderer {


    private static final HtmlTemplate TEMPLATE = HtmlTemplate.classpath("templates/admission-letter.html");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);
    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final Pattern HEX_COLOUR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final String DEFAULT_COLOUR = "#1F4E8C";

    private AdmissionLetterRenderer() {
    }

    public static byte[] pdf(LetterContent c) {
        return PdfRenderer.render(html(c), "admission letter " + c.letterNo());
    }

    static String html(LetterContent c) {
        JoiningInstructions j = c.joining();
        Map<String, String> v = new HashMap<>();
        v.put("institutionName", c.institutionName());
        v.put("institutionShortName", initials(c));
        v.put("nactvetNo", nz(c.institutionNactvetNo()));
        v.put("contactLine", Stream.of(c.institutionPhone(), c.institutionEmail()).filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" · ")));
        // only a validated colour reaches the stylesheet
        v.put("primaryColor", c.primaryColor() != null && HEX_COLOUR.matcher(c.primaryColor()).matches() ? c.primaryColor() : DEFAULT_COLOUR);
        v.put("letterNo", c.letterNo());
        v.put("editionNote", c.edition() > 1 ? " (edition " + c.edition() + ", replaces earlier editions)" : "");
        v.put("letterDate", DATE.format(c.letterDate()));
        v.put("applicantName", c.applicantName());
        v.put("applicationRef", c.applicationRef());
        v.put("nactvetVerificationNo", nz(c.nactvetVerificationNo()));
        v.put("acceptedDate", c.acceptedAt() == null ? "" : DATE.format(c.acceptedAt().atZoneSameInstant(ZONE)));
        v.put("programmeName", c.programmeName());
        v.put("awardName", c.awardName());
        v.put("programmeCode", nz(c.programmeNactvetCode()));
        v.put("duration", duration(c.durationSemesters()));
        v.put("academicYear", c.academicYear());
        v.put("intake", c.intake());
        v.put("reportingDate", DAY_DATE.format(j.reportingDate()));
        v.put("reportingTime", j.reportingTime() == null || j.reportingTime().isBlank() ? "As announced" : j.reportingTime());
        v.put("reportingVenue", j.reportingVenue());
        v.put("reportingLine", DAY_DATE.format(j.reportingDate())
                + (j.reportingTime() == null || j.reportingTime().isBlank() ? "" : " at " + j.reportingTime()) + ", " + j.reportingVenue());
        v.put("signatoryName", j.signatoryName());
        v.put("signatoryTitle", j.signatoryTitle());
        v.put("verificationCode", c.verificationCode());
        v.put("verificationUrlBase", urlBase(c.verificationUrl()));

        Map<String, String> m = new HashMap<>();
        m.put("addressLines", lines(c.applicantAddress()));
        m.put("qrImage", "<img alt=\"QR code\" style=\"width: 76pt; height: 76pt;\" src=\"data:image/png;base64,"
                + Base64.getEncoder().encodeToString(QrCodes.png(c.verificationUrl(), 330)) + "\" />");
        m.put("registrationRow", j.registrationDeadline() == null ? ""
                : "<tr><th>Register by</th><td>" + HtmlTemplate.escape(DAY_DATE.format(j.registrationDeadline())) + "</td></tr>");
        int section = 2;
        List<String> blocks = new ArrayList<>();
        if (!j.documentsToBring().isEmpty()) blocks.add(list(section++ + ". Documents to bring", j.documentsToBring()));
        else blocks.add("");
        if (!j.itemsToBring().isEmpty()) blocks.add(list(section++ + ". Items to bring", j.itemsToBring()));
        else blocks.add("");
        blocks.add(j.feesNote() == null || j.feesNote().isBlank() ? ""
                : "<h3>" + section++ + ". Fees</h3>" + paragraphs(j.feesNote()));
        blocks.add(j.notes().isEmpty() ? "" : list(section + ". Other information", j.notes()));
        m.put("documentsBlock", blocks.get(0));
        m.put("itemsBlock", blocks.get(1));
        m.put("feesBlock", blocks.get(2));
        m.put("notesBlock", blocks.get(3));
        // the wording frozen in the letter; letters issued before the wording could be edited use the built-in text
        java.util.Map<String, String> w = c.wording() == null ? java.util.Map.of() : c.wording();
        m.put("introBlock", LetterWording.html(w.getOrDefault(LetterTemplateService.ADMISSION_INTRO,
                LetterTemplateService.defaultBody(LetterTemplateService.ADMISSION_INTRO, true)), v));
        m.put("termsBlock", LetterWording.html(w.getOrDefault(LetterTemplateService.ADMISSION_TERMS,
                LetterTemplateService.defaultBody(LetterTemplateService.ADMISSION_TERMS, true)), v));
        String notice = LetterWording.html(w.getOrDefault(LetterTemplateService.ADMISSION_PAYMENT,
                LetterTemplateService.defaultBody(LetterTemplateService.ADMISSION_PAYMENT, true)), v);
        m.put("paymentNotice", notice.isEmpty() ? "" : "<div class=\"notice small\">" + notice + "</div>");
        return TEMPLATE.render(v, m);
    }

    private static String list(String title, List<String> items) {
        return "<h3>" + HtmlTemplate.escape(title) + "</h3><ul>"
                + items.stream().map(i -> "<li>" + HtmlTemplate.escape(i) + "</li>").collect(Collectors.joining()) + "</ul>";
    }

    private static String paragraphs(String text) {
        return Stream.of(text.split("\\r?\\n")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> "<p>" + HtmlTemplate.escape(s) + "</p>").collect(Collectors.joining());
    }

    private static String lines(String text) {
        if (text == null || text.isBlank()) return "";
        return Stream.of(text.split("\\r?\\n")).map(String::trim).filter(s -> !s.isEmpty())
                .map(HtmlTemplate::escape).collect(Collectors.joining("<br />"));
    }

    private static String initials(LetterContent c) {
        String s = c.institutionShortName();
        if (s == null || s.isBlank()) {
            s = Stream.of(c.institutionName().split("\\s+")).filter(w -> !w.isEmpty()).map(w -> w.substring(0, 1)).collect(Collectors.joining());
        }
        return s.length() > 5 ? s.substring(0, 5) : s;
    }

    private static String duration(int semesters) {
        if (semesters % 2 == 0) {
            int years = semesters / 2;
            return years + (years == 1 ? " year" : " years") + " (" + semesters + " semesters)";
        }
        return semesters + " semesters";
    }

    private static String urlBase(String url) {
        try {
            URI u = URI.create(url);
            return u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + "/verify";
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
