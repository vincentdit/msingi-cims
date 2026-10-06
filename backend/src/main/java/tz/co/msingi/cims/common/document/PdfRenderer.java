package tz.co.msingi.cims.common.document;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Renders well-formed XHTML (with CSS for print) to PDF using openhtmltopdf. */
public final class PdfRenderer {

    private PdfRenderer() {
    }

    public static byte[] render(String xhtml, String title) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            b.withHtmlContent(xhtml, null);
            b.toStream(out);
            b.withProducer("CIMS - Msingi Technologies Ltd");
            b.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not render " + title, e);
        }
    }
}
