package tz.co.msingi.cims.common.document;

import java.util.Base64;

/**
 * What makes a printed document the institution's own: its logo at the top and, on the documents the Principal signs, the
 * Principal's name, title, scanned signature and stamp at the bottom. Renderers ask this for ready-made markup so every
 * document draws them the same way. Any part may be missing: without a logo the initials badge is drawn; without a scanned
 * signature a blank line is left for signing by hand.
 */
public record OfficialMarks(Image logo, String signatoryName, String signatoryTitle, Image signature, Image stamp, Long signatoryId) {

    public static final String DEFAULT_TITLE = "Principal";

    /** An image with its pixel size (so it can be fitted to a box without distortion). */
    public record Image(byte[] data, String type, int width, int height) {

        String dataUri() {
            return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(data);
        }

        /** An {@code <img>} fitted inside a box of the given size in points, keeping the image's shape. */
        String img(String alt, double boxWidth, double boxHeight, String extraStyle) {
            double w = boxWidth, h = boxHeight;
            if (width > 0 && height > 0) {
                double scale = Math.min(boxWidth / width, boxHeight / height);
                w = width * scale;
                h = height * scale;
            }
            return "<img alt=\"" + alt + "\" src=\"" + dataUri() + "\" style=\"width: " + pt(w) + "pt; height: " + pt(h) + "pt;"
                    + (extraStyle == null ? "" : " " + extraStyle) + "\" />";
        }
    }

    public static OfficialMarks none() {
        return new OfficialMarks(null, null, null, null, null, null);
    }

    /** Only a typed name and title (documents issued before the Principal was recorded keep the name they were issued with). */
    public static OfficialMarks typed(Image logo, String name, String title) {
        return new OfficialMarks(logo, name, title, null, null, null);
    }

    public boolean hasLogo() {
        return logo != null && logo.data() != null && logo.data().length > 0;
    }

    public boolean hasSignatory() {
        return signatoryName != null && !signatoryName.isBlank();
    }

    public String title() {
        return signatoryTitle == null || signatoryTitle.isBlank() ? DEFAULT_TITLE : signatoryTitle;
    }

    /**
     * The mark at the top left of a document: the logo fitted in a {@code size}-point square, or the coloured initials badge
     * (styled by the template's {@code .badge} class) when there is no logo.
     */
    public String badge(String shortName, double size) {
        if (hasLogo()) return logo.img("Logo", size, size, "display: block;");
        return "<div class=\"badge\">" + HtmlTemplate.escape(shortName) + "</div>";
    }

    /**
     * The signature block: the scanned signature with the stamp pressed over its right side, then the line with the
     * Principal's name, title and (when given) "For: institution". Without a scanned signature the space is left for signing
     * by hand. {@code scale} shrinks the whole block (1 on letters, smaller on cards).
     */
    public String signatureBlock(String forInstitution, double scale, double lineWidth) {
        boolean sig = signature != null && signature.data() != null && signature.data().length > 0;
        boolean st = stamp != null && stamp.data() != null && stamp.data().length > 0;
        double space = (sig || st ? 44 : 30) * scale;
        StringBuilder b = new StringBuilder();
        b.append("<div style=\"position: relative; height: ").append(pt(space)).append("pt; width: ").append(pt(lineWidth)).append("pt;\">");
        if (sig) {
            b.append(signature.img("Signature", 150 * scale, 42 * scale, "position: absolute; left: 0; bottom: 0;"));
        }
        if (st) {
            // the stamp overlaps the end of the signature and the line, as a hand stamp would
            b.append(stamp.img("Stamp", 72 * scale, 72 * scale,
                    "position: absolute; left: " + pt((sig ? 100 : 40) * scale) + "pt; top: " + pt(-6 * scale) + "pt;"));
        }
        b.append("</div>");
        b.append("<div style=\"border-top: 0.6pt solid #9ca3af; width: ").append(pt(lineWidth)).append("pt; padding-top: ").append(pt(3 * scale))
                .append("pt; font-size: ").append(scale >= 1 ? "inherit" : pt(9 * scale) + "pt").append(";\">");
        if (hasSignatory()) b.append("<strong>").append(HtmlTemplate.escape(signatoryName)).append("</strong><br />");
        b.append(HtmlTemplate.escape(title()));
        if (forInstitution != null && !forInstitution.isBlank()) b.append("<br />For: ").append(HtmlTemplate.escape(forInstitution));
        b.append("</div>");
        return b.toString();
    }

    private static String pt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
