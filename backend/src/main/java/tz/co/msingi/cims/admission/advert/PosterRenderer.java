package tz.co.msingi.cims.admission.advert;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import tz.co.msingi.cims.admission.advert.ProgrammeAdvertService.Advert;
import tz.co.msingi.cims.common.document.PdfRenderer;
import tz.co.msingi.cims.common.document.QrCodes;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import static tz.co.msingi.cims.common.document.HtmlTemplate.escape;

/**
 * Advert posters for a programme, drawn from what CIMS holds (so a poster never shows an old fee): the institution's logo and
 * colour, the programme, the year's fee and instalments, the entry requirements, the selling points, the closing date, the
 * contacts and social pages, and a QR code to the programme's page. Square (1080 × 1080, Facebook, Instagram, WhatsApp) or
 * portrait (1080 × 1350, Facebook and Instagram feeds), in English or Kiswahili, as PDF or PNG.
 */
public final class PosterRenderer {

    private PosterRenderer() {
    }

    public enum Format {
        SQUARE(810, 810), PORTRAIT(810, 1012.5);
        final double w, h;

        Format(double w, double h) {
            this.w = w;
            this.h = h;
        }
    }

    /** The institution as the poster shows it. */
    public record Institution(String name, String shortName, String colour, String accent, byte[] logo, String logoType,
                              List<String> phones, String email, String website, List<String[]> social) {
    }

    private static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");

    public static byte[] pdf(Advert a, Institution inst, String link, Format f, boolean sw) {
        return PdfRenderer.render(html(a, inst, link, f, sw), "poster " + a.code());
    }

    /** The poster as a PNG image, 1080 pixels wide. */
    public static byte[] png(Advert a, Institution inst, String link, Format f, boolean sw) {
        byte[] pdf = pdf(a, inst, link, f, sw);
        try (PDDocument doc = PDDocument.load(pdf); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, 96f, ImageType.RGB);
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not draw the poster", e);
        }
    }

    /** The programme's name in big letters, smaller for long names so the poster keeps its room. */
    static int progSize(String name, boolean tall) {
        int n = name == null ? 0 : name.length();
        if (tall) return n > 60 ? 40 : n > 40 ? 44 : 52;
        return n > 60 ? 34 : 40;
    }

    static String html(Advert a, Institution inst, String link, Format f, boolean sw) {
        boolean tall = f == Format.PORTRAIT;
        String c = inst.colour(), acc = inst.accent();
        String years = a.durationSemesters() % 2 == 0 ? String.valueOf(a.durationSemesters() / 2)
                : BigDecimal.valueOf(a.durationSemesters()).divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_UP).toPlainString();
        String duration = sw ? "Miaka " + years : years + (years.equals("1") ? " year" : " years");
        String level = (a.awardFamily() + " Level " + a.awardLevel());
        DateTimeFormatter day = DateTimeFormatter.ofPattern("d MMM yyyy", sw ? Locale.forLanguageTag("sw") : Locale.UK);
        String status = a.open() ? (sw ? "MAOMBI YAMEFUNGULIWA" : "APPLICATIONS OPEN")
                : (sw ? "MAOMBI YANAFUNGULIWA " : "APPLICATIONS OPEN ON ") + day.format(a.opensAt().atZoneSameInstant(TZ)).toUpperCase(Locale.ROOT);
        String closing = (sw ? "Mwisho wa maombi: " : "Applications close: ") + day.format(a.closesAt().atZoneSameInstant(TZ));
        String requirements = sw ? a.requirementsSw() : a.requirementsEn();
        if (requirements == null || requirements.isBlank()) requirements = a.requirementSummary() == null ? "" : a.requirementSummary();
        int reqMax = tall ? 330 : 230;
        if (requirements.length() > reqMax) requirements = requirements.substring(0, reqMax - 1).replaceAll("\\s+\\S*$", "") + "…";

        String logo = inst.logo() == null || inst.logo().length == 0
                ? "<div class=\"badge\">" + escape(initials(inst)) + "</div>"
                : "<img class=\"logo\" src=\"data:" + inst.logoType() + ";base64," + Base64.getEncoder().encodeToString(inst.logo()) + "\" />";
        String qr = "<img class=\"qr\" src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(QrCodes.png(link, 360)) + "\" />";

        StringBuilder fee = new StringBuilder();
        if (a.fee() != null && a.fee().total() != null && a.fee().total().signum() > 0) {
            fee.append("<div class=\"fee\"><span class=\"feeLabel\">").append(sw ? "ADA: " : "FEE: ").append("</span>TZS ")
                    .append(escape(tzs(a.fee().total()))).append("<span class=\"per\">").append(sw ? " kwa mwaka" : " a year").append("</span>");
            if (a.fee().instalments() > 1) {
                fee.append("<div class=\"instal\">").append(sw ? "Unalipa kwa awamu " + a.fee().instalments() + " (" + SW_COUNT[Math.min(a.fee().instalments(), 4)] + ")"
                        : "Pay in " + a.fee().instalments() + " instalments").append("</div>");
            }
            fee.append("</div>");
        }
        StringBuilder chips = new StringBuilder();
        List<String> hl = a.highlights().stream().limit(tall && a.name().length() <= 40 ? 4 : 3).toList();
        for (String h : hl) chips.append("<span class=\"chip\">").append(escape(h)).append("</span> ");

        List<String> contactBits = new ArrayList<>();
        if (!inst.phones().isEmpty()) contactBits.add(escape(String.join(" · ", inst.phones())));
        if (inst.email() != null) contactBits.add(escape(inst.email()));
        StringBuilder social = new StringBuilder();
        for (String[] s : inst.social()) {
            social.append("<span class=\"net\">").append(escape(s[0])).append("</span> ").append(escape(s[1])).append("&#160;&#160; ");
        }

        String css = """
                @page { size: %spt %spt; margin: 0; }
                body { margin: 0; font-family: Helvetica, Arial, sans-serif; color: #111827; }
                .page { width: %spt; height: %spt; position: relative; background: #ffffff; }
                .top { background: %s; color: #ffffff; padding: 26pt 34pt 22pt 34pt; }
                .top table { width: 100%%; border-collapse: collapse; }
                .logo { width: %spt; height: auto; }
                .badge { width: 70pt; height: 70pt; line-height: 70pt; border-radius: 35pt; background: #ffffff; color: %s; font-weight: bold; font-size: 22pt; text-align: center; }
                .inst { font-size: %spt; font-weight: bold; line-height: 1.12; text-transform: uppercase; letter-spacing: 0.5pt; }
                .top table.statusT { width: auto; margin-top: 8pt; border-collapse: collapse; }
                .status { white-space: nowrap; background: %s; color: #111827; font-weight: bold; font-size: 11.5pt; padding: 4pt 10pt; }
                .body { padding: %spt 34pt 0 34pt; }
                .prog { font-size: %spt; font-weight: bold; color: %s; line-height: 1.05; text-transform: uppercase; }
                .award { font-size: 15pt; color: #374151; margin-top: 6pt; }
                .fee { margin-top: %spt; font-size: %spt; font-weight: bold; border-left: 8pt solid %s; padding: 6pt 0 6pt 14pt; }
                .feeLabel { color: %s; }
                .per { font-size: 16pt; font-weight: normal; }
                .instal { font-size: 16pt; font-weight: normal; text-transform: none; letter-spacing: 0; margin-top: 3pt; }
                .req { margin-top: %spt; background: #F3F4F6; padding: 12pt 16pt; border-radius: 6pt; }
                .reqT { font-size: 13pt; font-weight: bold; color: %s; letter-spacing: 1pt; }
                .reqB { font-size: %spt; line-height: 1.3; margin-top: 4pt; }
                .stats { width: 100%%; margin-top: %spt; border-collapse: separate; border-spacing: 0; }
                .stats td { width: 33%%; vertical-align: top; padding-right: 10pt; }
                .stat { border: 1.5pt solid #E5E7EB; border-radius: 6pt; padding: 8pt 10pt; }
                .statL { font-size: 10.5pt; color: #6B7280; text-transform: uppercase; letter-spacing: 0.6pt; }
                .statV { font-size: %spt; font-weight: bold; color: #111827; margin-top: 2pt; }
                .chips { margin-top: %spt; line-height: 2.3; }
                .chip { display: inline-block; white-space: nowrap; background: %s; color: #ffffff; font-weight: bold; font-size: %spt; padding: 5pt 12pt; border-radius: 14pt; text-transform: uppercase; margin-right: 6pt; line-height: 1.2; }
                .foot { position: absolute; left: 0; right: 0; bottom: 0; background: #111827; color: #ffffff; padding: 18pt 34pt 18pt 34pt; }
                .foot table { width: 100%%; border-collapse: collapse; }
                .apply { font-size: 12pt; color: #D1D5DB; text-transform: uppercase; letter-spacing: 1pt; }
                .link { font-size: %spt; font-weight: bold; color: %s; margin-top: 2pt; }
                .contacts { font-size: 12.5pt; margin-top: 7pt; color: #F9FAFB; line-height: 1.35; }
                .net { color: %s; font-weight: bold; }
                .closing { font-size: 12.5pt; margin-top: 6pt; color: #FDE68A; font-weight: bold; }
                .qr { width: %spt; height: %spt; background: #ffffff; padding: 5pt; }
                .scan { font-size: 10pt; color: #D1D5DB; text-align: center; margin-top: 3pt; }
                """.formatted(f.w, f.h, f.w, f.h, c, tall ? 86 : 72, c, tall ? 24 : 21, acc, tall ? 44 : 30,
                progSize(a.name(), tall), c, tall ? 34 : 22, tall ? 32 : 27, acc, c, tall ? 34 : 22, c, tall ? 19 : 15.5, tall ? 38 : 24, tall ? 20 : 16, tall ? 38 : 24, c, tall ? 15 : 13,
                tall ? 22 : 20, acc, acc, tall ? 116 : 104, tall ? 116 : 104);

        String body = "<div class=\"page\">"
                + "<div class=\"top\"><table><tr><td style=\"width: " + (tall ? 100 : 86) + "pt;\">" + logo + "</td><td>"
                + "<div class=\"inst\">" + escape(inst.name()) + "</div><table class=\"statusT\"><tr><td class=\"status\">" + escape(status) + "</td></tr></table></td></tr></table></div>"
                + "<div class=\"body\">"
                + "<div class=\"prog\">" + escape(a.name()) + "</div>"
                + "<div class=\"award\">" + escape(a.awardName()) + " · " + escape(level) + " · " + escape(duration) + "</div>"
                + fee
                + (requirements.isBlank() ? "" : "<div class=\"req\"><div class=\"reqT\">" + (sw ? "SIFA ZA KUJIUNGA" : "ENTRY REQUIREMENTS") + "</div>"
                + "<div class=\"reqB\">" + escape(requirements) + "</div></div>")
                + "<table class=\"stats\"><tr>"
                + stat(sw ? "Muda wa masomo" : "Duration", duration)
                + stat(sw ? "Nafasi" : "Places", String.valueOf(a.capacity()))
                + stat(sw ? "Ada ya maombi" : "Application fee", a.applicationFee() == null || a.applicationFee().signum() == 0 ? (sw ? "Bure" : "Free") : "TZS " + tzs(a.applicationFee()))
                + "</tr></table>"
                + (chips.length() == 0 ? "" : "<div class=\"chips\">" + chips + "</div>")
                + "</div>"
                + "<div class=\"foot\"><table><tr><td>"
                + "<div class=\"apply\">" + (sw ? "Tuma maombi mtandaoni" : "Apply online") + "</div>"
                + "<div class=\"link\">" + escape(link.replaceFirst("^https?://", "")) + "</div>"
                + "<div class=\"closing\">" + escape(closing) + "</div>"
                + (contactBits.isEmpty() ? "" : "<div class=\"contacts\">" + String.join(" · ", contactBits) + "</div>")
                + (social.length() == 0 ? "" : "<div class=\"contacts\">" + social + "</div>")
                + "</td><td style=\"width: " + (tall ? 128 : 116) + "pt; text-align: right; vertical-align: bottom;\">" + qr
                + "<div class=\"scan\">" + (sw ? "Skani kutuma maombi" : "Scan to apply") + "</div></td></tr></table></div>"
                + "</div>";
        return "<!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"UTF-8\" /><title>" + escape(a.name())
                + "</title><style>" + css + "</style></head><body>" + body + "</body></html>";
    }

    private static String stat(String label, String value) {
        return "<td><div class=\"stat\"><div class=\"statL\">" + escape(label) + "</div><div class=\"statV\">" + escape(value) + "</div></div></td>";
    }

    private static final String[] SW_COUNT = {"", "moja", "mbili", "tatu", "nne"};

    private static String initials(Institution i) {
        if (i.shortName() != null && !i.shortName().isBlank()) return i.shortName().length() > 5 ? i.shortName().substring(0, 5) : i.shortName();
        StringBuilder b = new StringBuilder();
        for (String w : i.name().split("\\s+")) if (!w.isEmpty() && Character.isUpperCase(w.charAt(0)) && b.length() < 4) b.append(w.charAt(0));
        return b.toString();
    }

    static String tzs(BigDecimal v) {
        BigDecimal a = v.setScale(0, RoundingMode.HALF_UP);
        return String.format(Locale.ENGLISH, "%,d", a.longValue());
    }
}
