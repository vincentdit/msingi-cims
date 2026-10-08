package tz.co.msingi.cims.notification;

/** How many SMS parts a text needs: GSM 7-bit (160, or 153 per part) or Unicode (70, or 67 per part). */
public final class SmsText {

    private static final String GSM_BASIC = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final String GSM_EXTENDED = "^{}\\[~]|€\f";

    public record Size(String encoding, int length, int parts) {
    }

    private SmsText() {
    }

    /**
     * Replaces typographic punctuation (dashes, curly quotes, ellipsis, non-breaking spaces) with plain GSM characters,
     * so that one such character does not turn the whole SMS into Unicode, which costs two to three times as much.
     */
    public static String plain(String text) {
        return text.replace('\u2014', '-').replace('\u2013', '-').replace('\u2212', '-')
                .replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201C', '"').replace('\u201D', '"')
                .replace("\u2026", "...").replace('\u00A0', ' ').replace('\u2022', '*').replace("\u20AC", "EUR");
    }

    public static Size size(String text) {
        int gsmLength = 0;
        boolean gsm = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (GSM_BASIC.indexOf(c) >= 0) gsmLength += 1;
            else if (GSM_EXTENDED.indexOf(c) >= 0) gsmLength += 2;
            else {
                gsm = false;
                break;
            }
        }
        if (gsm) return new Size("GSM7", gsmLength, gsmLength <= 160 ? 1 : (gsmLength + 152) / 153);
        int len = text.codePointCount(0, text.length()) + (int) text.codePoints().filter(cp -> cp > 0xFFFF).count();
        return new Size("UCS2", len, len <= 70 ? 1 : (len + 66) / 67);
    }
}
