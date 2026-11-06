package tz.co.msingi.cims.admission.letter;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import tz.co.msingi.cims.common.document.OfficialMarks;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionLetterRendererTest {

    static final String URL = "https://admission.demo.ac.tz/verify/7KQ2-9XM4-TB3H-P0RA";

    static LetterContent sample(String name) {
        JoiningInstructions j = new JoiningInstructions(LocalDate.of(2027, 9, 27), "08:00", "Main Hall, Block A",
                LocalDate.of(2027, 10, 8), List.of("Original CSEE certificate", "Birth certificate", "Two passport-size photographs"),
                List.of("Laboratory coat", "Scientific calculator"),
                "Tuition TZS 1,200,000 per year in two instalments.\nPay only with a GePG control number.",
                List.of("Hostel places are limited & allocated on a first-come basis."), "Dr. Amina Salum", "Principal");
        return new LetterContent("Msingi Demo Technical College", "MDTC", "REG/DEMO/001", "+255 700 000 000", "admissions@demo.ac.tz",
                "#1F4E8C", "DEMO/ADM/2027/000001", 1, LocalDate.of(2026, 10, 6), name, "P.O. Box 123\nDodoma",
                "DEMO/APP/2027/000001", "NV/2026/496655", "Ordinary Diploma in Information Technology",
                "Ordinary Diploma (NTA Level 6)", "DEMO-ODIT", 6, "2027/2028", "September intake",
                OffsetDateTime.parse("2026-10-06T10:15:00+03:00"), j, "7KQ2-9XM4-TB3H-P0RA", URL);
    }

    @Test
    void rendersLetterAndJoiningInstructionsWithScannableQrCode() throws Exception {
        byte[] pdf = AdmissionLetterRenderer.pdf(sample("Asha <b>O'Neil</b> & Mussa"), OfficialMarks.typed(null, "Dr. Amina Salum", "Principal"));
        Path out = Path.of(System.getProperty("java.io.tmpdir"), "cims-letter-sample.pdf");
        Files.write(out, pdf);
        try (PDDocument doc = PDDocument.load(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Asha <b>O'Neil</b> & Mussa")           // escaped, printed literally
                    .contains("DEMO/ADM/2027/000001", "NV/2026/496655", "Ordinary Diploma in Information Technology",
                            "Monday, 27 September 2027", "Main Hall, Block A", "Laboratory coat", "Dr. Amina Salum",
                            "7KQ2-9XM4-TB3H-P0RA", "Joining instructions", "Page 2 of 2");
            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 200);
            String decoded = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(page)))).getText();
            assertThat(decoded).isEqualTo(URL);
        }
    }

    @Test
    void signedLetterCarriesLogoSignatureAndStampWithThePrincipalsName() throws Exception {
        OfficialMarks marks = new OfficialMarks(png(400, 400, 0x1F4E8C), "Dr. Neema Mushi", "Principal", png(600, 180, 0x111827),
                png(300, 300, 0x1D4ED8), 7L);
        String html = AdmissionLetterRenderer.html(sample("Asha"), marks);
        assertThat(html).contains("alt=\"Logo\"", "alt=\"Signature\"", "alt=\"Stamp\"", "Dr. Neema Mushi").doesNotContain("class=\"badge\"");
        byte[] pdf = AdmissionLetterRenderer.pdf(sample("Asha"), marks);
        Files.write(Path.of(System.getProperty("java.io.tmpdir"), "cims-letter-signed.pdf"), pdf);
        try (PDDocument doc = PDDocument.load(pdf)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Dr. Neema Mushi", "Principal", "For: Msingi Demo Technical College");
            int images = 0;
            for (var name : doc.getPage(0).getResources().getXObjectNames()) {
                if (doc.getPage(0).getResources().isImageXObject(name)) images++;
            }
            assertThat(images).isGreaterThanOrEqualTo(4);   // logo, signature, stamp and the QR code
        }
        // without a logo the initials badge is drawn, and without a signature the line is left for signing by hand
        String plain = AdmissionLetterRenderer.html(sample("Asha"), OfficialMarks.typed(null, "Dr. Neema Mushi", null));
        assertThat(plain).contains("class=\"badge\"", "Dr. Neema Mushi", "Principal").doesNotContain("alt=\"Signature\"");
    }

    static OfficialMarks.Image png(int w, int h, int rgb) throws java.io.IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setColor(new java.awt.Color(rgb));
        g.fillOval(w / 8, h / 8, w * 3 / 4, h * 3 / 4);
        g.dispose();
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return new OfficialMarks.Image(out.toByteArray(), "image/png", w, h);
    }

    @Test
    void unsafeColourNeverReachesTheStylesheet() {
        LetterContent c = sample("A");
        LetterContent bad = new LetterContent(c.institutionName(), c.institutionShortName(), c.institutionNactvetNo(), c.institutionPhone(),
                c.institutionEmail(), "red;} body{display:none", c.letterNo(), c.edition(), c.letterDate(), c.applicantName(),
                c.applicantAddress(), c.applicationRef(), c.nactvetVerificationNo(), c.programmeName(), c.awardName(),
                c.programmeNactvetCode(), c.durationSemesters(), c.academicYear(), c.intake(), c.acceptedAt(), c.joining(),
                c.verificationCode(), c.verificationUrl());
        assertThat(AdmissionLetterRenderer.html(bad, OfficialMarks.none())).doesNotContain("display:none").contains("#1F4E8C");
    }

    @Test
    void codesAreRandomFormattedAndForgiving() {
        String code = LetterCodes.newCode();
        assertThat(code).matches("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}");
        assertThat(LetterCodes.newCode()).isNotEqualTo(code);
        assertThat(LetterCodes.normalise(" 7kq2 9xm4-tb3h-pora ")).contains("7KQ2-9XM4-TB3H-P0RA");
        assertThat(LetterCodes.normalise("7KQ2-9XM4")).isEmpty();
        assertThat(LetterCodes.normalise("7KQ2-9XM4-TB3H-P0R!")).isEmpty();
    }
}
