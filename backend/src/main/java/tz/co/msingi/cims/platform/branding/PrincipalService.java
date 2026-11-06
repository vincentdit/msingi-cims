package tz.co.msingi.cims.platform.branding;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.document.OfficialMarks;
import tz.co.msingi.cims.common.document.OfficialMarks.Image;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The Principal, who signs the institution's letters and documents: name, title, scanned signature and the institution's stamp.
 * Every change is kept as a new record, so a document carries the signature it was issued with even after the Principal changes.
 * The signature and stamp are only ever shown to the administrator and printed on documents, never served publicly.
 */
@Service
@RequiredArgsConstructor
public class PrincipalService {

    public static final int MAX_BYTES = 3 * 1024 * 1024;
    /** Images are reduced to this width: plenty for print at the size they appear, and keeps every PDF small. */
    static final int STORE_WIDTH = 900;

    public enum Mark { SIGNATURE, STAMP }

    public record Save(@NotBlank @Size(max = 120) String name, @Size(max = 120) String title) {
    }

    public record PrincipalView(Long id, String name, String title, boolean signature, boolean stamp, String signatureUrl, String stampUrl,
                                String changeNote, String updatedByName, OffsetDateTime updatedAt, int changes) {
    }

    public record HistoryRow(Long id, String name, String title, boolean signature, boolean stamp, String changeNote, String byName,
                             OffsetDateTime at) {
    }

    private record Row(Long id, String name, String title, byte[] signature, String signatureType, Integer signatureWidth, Integer signatureHeight,
                       byte[] stamp, String stampType, Integer stampWidth, Integer stampHeight, String changeNote, String byName, OffsetDateTime at) {
    }

    private final JdbcTemplate jdbc;
    private final InstitutionPhotoService photos;
    private final AuditService audit;

    // ------------------------------------------------------------------ administrator

    @Transactional(readOnly = true)
    public Optional<PrincipalView> view() {
        return current().map(this::toView);
    }

    @Transactional(readOnly = true)
    public List<HistoryRow> history() {
        return jdbc.query("""
                select id, name, title, signature is not null as sig, stamp is not null as st, change_note, created_by_name, created_at
                from institution_signatories order by id desc limit 50""",
                (rs, i) -> new HistoryRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5), rs.getString(6),
                        rs.getString(7), rs.getObject(8, OffsetDateTime.class)));
    }

    /** Records the Principal's name and title; the signature and stamp carry over unless the person changes. */
    @Transactional
    public PrincipalView save(Save s) {
        String name = s.name().strip();
        String title = s.title() == null || s.title().isBlank() ? OfficialMarks.DEFAULT_TITLE : s.title().strip();
        Row cur = current().orElse(null);
        if (cur != null && cur.name().equals(name) && cur.title().equals(title)) return toView(cur);
        boolean newPerson = cur != null && !normal(cur.name()).equals(normal(name));
        String note = cur == null ? "Principal recorded"
                : newPerson ? "New Principal: " + name + " (signature and stamp to be uploaded)" : "Name or title corrected";
        Row next = new Row(null, name, title,
                newPerson ? null : cur == null ? null : cur.signature(), newPerson || cur == null ? null : cur.signatureType(),
                newPerson || cur == null ? null : cur.signatureWidth(), newPerson || cur == null ? null : cur.signatureHeight(),
                // the stamp belongs to the institution, so it stays when the Principal changes
                cur == null ? null : cur.stamp(), cur == null ? null : cur.stampType(), cur == null ? null : cur.stampWidth(),
                cur == null ? null : cur.stampHeight(), note, null, null);
        return toView(insert(next, "PRINCIPAL_SAVED", Map.of("name", name, "title", title, "newPerson", newPerson)));
    }

    /** A scanned signature or stamp. Unless {@code keepBackground}, the white paper around the ink is made see-through. */
    @Transactional
    public PrincipalView upload(Mark mark, byte[] bytes, boolean keepBackground) {
        Row cur = current().orElseThrow(() -> new BusinessRuleException("PRINCIPAL_FIRST", "Enter the Principal's name first, then upload the "
                + (mark == Mark.SIGNATURE ? "signature." : "stamp.")));
        Image img = prepare(bytes, keepBackground);
        Row next = mark == Mark.SIGNATURE
                ? new Row(null, cur.name(), cur.title(), img.data(), img.type(), img.width(), img.height(), cur.stamp(), cur.stampType(), cur.stampWidth(),
                cur.stampHeight(), cur.signature() == null ? "Signature uploaded" : "Signature replaced", null, null)
                : new Row(null, cur.name(), cur.title(), cur.signature(), cur.signatureType(), cur.signatureWidth(), cur.signatureHeight(), img.data(),
                img.type(), img.width(), img.height(), cur.stamp() == null ? "Stamp uploaded" : "Stamp replaced", null, null);
        return toView(insert(next, "PRINCIPAL_" + mark.name() + "_UPLOADED", Map.of("bytes", img.data().length)));
    }

    @Transactional
    public PrincipalView remove(Mark mark) {
        Row cur = current().orElseThrow(() -> new NotFoundException("No Principal has been recorded"));
        if (mark == Mark.SIGNATURE ? cur.signature() == null : cur.stamp() == null) return toView(cur);
        Row next = mark == Mark.SIGNATURE
                ? new Row(null, cur.name(), cur.title(), null, null, null, null, cur.stamp(), cur.stampType(), cur.stampWidth(), cur.stampHeight(),
                "Signature removed", null, null)
                : new Row(null, cur.name(), cur.title(), cur.signature(), cur.signatureType(), cur.signatureWidth(), cur.signatureHeight(),
                null, null, null, null, "Stamp removed", null, null);
        return toView(insert(next, "PRINCIPAL_" + mark.name() + "_REMOVED", Map.of()));
    }

    /** The current signature or stamp image, for the administrator's page. */
    @Transactional(readOnly = true)
    public Image image(Mark mark) {
        Row r = current().orElseThrow(() -> new NotFoundException("No Principal has been recorded"));
        Image i = mark == Mark.SIGNATURE ? image(r.signature(), r.signatureType(), r.signatureWidth(), r.signatureHeight())
                : image(r.stamp(), r.stampType(), r.stampWidth(), r.stampHeight());
        if (i == null) throw new NotFoundException("No " + mark.name().toLowerCase(Locale.ROOT) + " has been uploaded");
        return i;
    }

    // ------------------------------------------------------------------ documents

    /** The id of the current Principal record, stored on a document when it is issued; null when none is recorded. */
    @Transactional(readOnly = true)
    public Long currentId() {
        return current().map(Row::id).orElse(null);
    }

    /** The logo and the current Principal, for documents printed as they are now (ID cards, receipts, previews). */
    @Transactional(readOnly = true)
    public OfficialMarks marks() {
        return marks(current().orElse(null));
    }

    /**
     * The marks for a document issued with Principal record {@code signatoryId}. A document issued before any Principal was
     * recorded (null) is signed by the current one if it never carried a name, so the caller passes its own fallback.
     */
    @Transactional(readOnly = true)
    public OfficialMarks marksFor(Long signatoryId) {
        if (signatoryId == null) return marks();
        List<Row> l = jdbc.query("select * from institution_signatories where id = ?", PrincipalService::row, signatoryId);
        return marks(l.isEmpty() ? null : l.get(0));
    }

    /** Only the logo: for documents issued before the Principal was recorded, which keep the name typed on them. */
    @Transactional(readOnly = true)
    public OfficialMarks logoOnly(String name, String title) {
        return OfficialMarks.typed(logo(), name, title);
    }

    @Transactional(readOnly = true)
    public Image logo() {
        if (photos.view(InstitutionPhoto.Kind.LOGO).isEmpty()) return null;
        InstitutionPhoto p = photos.image(InstitutionPhoto.Kind.LOGO);
        return new Image(p.getData(), p.getContentType(), p.getWidth() == null ? 0 : p.getWidth(), p.getHeight() == null ? 0 : p.getHeight());
    }

    // ------------------------------------------------------------------ internals

    private OfficialMarks marks(Row r) {
        Image logo = logo();
        if (r == null) return OfficialMarks.typed(logo, null, null);
        return new OfficialMarks(logo, r.name(), r.title(), image(r.signature(), r.signatureType(), r.signatureWidth(), r.signatureHeight()),
                image(r.stamp(), r.stampType(), r.stampWidth(), r.stampHeight()), r.id());
    }

    private Optional<Row> current() {
        return jdbc.query("select * from institution_signatories order by id desc limit 1", PrincipalService::row).stream().findFirst();
    }

    private Row insert(Row r, String action, Map<String, Object> details) {
        Long id = jdbc.queryForObject("""
                insert into institution_signatories (tenant_id, name, title, signature, signature_type, signature_width, signature_height,
                    stamp, stamp_type, stamp_width, stamp_height, change_note, created_by_name)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                TenantContext.requireTenantId(), r.name(), r.title(), r.signature(), r.signatureType(), r.signatureWidth(), r.signatureHeight(),
                r.stamp(), r.stampType(), r.stampWidth(), r.stampHeight(), r.changeNote(), CurrentUser.name());
        audit.record(action, "InstitutionSignatory", id, details);
        return current().orElseThrow();
    }

    private PrincipalView toView(Row r) {
        Integer n = jdbc.queryForObject("select count(*) from institution_signatories", Integer.class);
        String base = "/api/admin/institution/principal/";
        return new PrincipalView(r.id(), r.name(), r.title(), r.signature() != null, r.stamp() != null,
                r.signature() == null ? null : base + "signature?v=" + r.id(), r.stamp() == null ? null : base + "stamp?v=" + r.id(),
                r.changeNote(), r.byName(), r.at(), n == null ? 0 : n);
    }

    private static Row row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Row(rs.getLong("id"), rs.getString("name"), rs.getString("title"), rs.getBytes("signature"), rs.getString("signature_type"),
                (Integer) rs.getObject("signature_width"), (Integer) rs.getObject("signature_height"), rs.getBytes("stamp"), rs.getString("stamp_type"),
                (Integer) rs.getObject("stamp_width"), (Integer) rs.getObject("stamp_height"), rs.getString("change_note"),
                rs.getString("created_by_name"), rs.getObject("created_at", OffsetDateTime.class));
    }

    private static Image image(byte[] data, String type, Integer w, Integer h) {
        return data == null ? null : new Image(data, type, w == null ? 0 : w, h == null ? 0 : h);
    }

    private static String normal(String s) {
        return s.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Checks the upload and makes it ready for print: reduced to {@link #STORE_WIDTH} pixels wide, cropped to the ink, and (unless
     * asked not to) with the paper made see-through so the stamp and signature sit naturally on the page. Always stored as PNG.
     */
    static Image prepare(byte[] bytes, boolean keepBackground) {
        if (bytes == null || bytes.length == 0) throw new BusinessRuleException("IMAGE_EMPTY", "Choose an image to upload.");
        if (bytes.length > MAX_BYTES) throw new BusinessRuleException("IMAGE_TOO_LARGE", "The image is larger than 3 MB. Save it smaller and try again.");
        String type = InstitutionPhotoService.sniff(bytes);
        if (type == null || type.equals("image/webp")) throw new BusinessRuleException("IMAGE_TYPE", "Upload a PNG or JPEG image.");
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            src = null;
        }
        if (src == null) throw new BusinessRuleException("IMAGE_UNREADABLE", "The image could not be read. Try saving it again as PNG or JPEG.");
        if (src.getWidth() < 150) {
            throw new BusinessRuleException("IMAGE_TOO_SMALL", "The image is " + src.getWidth() + " pixels wide; scan it so it is at least 150 pixels wide.");
        }
        BufferedImage img = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        if (!keepBackground) clearPaper(img);
        img = crop(img, keepBackground);
        if (img.getWidth() > STORE_WIDTH) {
            int h = Math.max(1, Math.round(img.getHeight() * (STORE_WIDTH / (float) img.getWidth())));
            BufferedImage small = new BufferedImage(STORE_WIDTH, h, BufferedImage.TYPE_INT_ARGB);
            var s = small.createGraphics();
            s.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            s.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            s.drawImage(img, 0, 0, STORE_WIDTH, h, null);
            s.dispose();
            img = small;
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return new Image(out.toByteArray(), "image/png", img.getWidth(), img.getHeight());
        } catch (IOException e) {
            throw new IllegalStateException("Could not store the image", e);
        }
    }

    /** Light paper becomes transparent, with a soft edge so the ink's outline stays smooth. */
    static void clearPaper(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = argb >>> 24, r = (argb >> 16) & 0xFF, gr = (argb >> 8) & 0xFF, b = argb & 0xFF;
                int light = Math.min(r, Math.min(gr, b));
                int alpha = light >= 225 ? 0 : light <= 170 ? 255 : (225 - light) * 255 / 55;
                alpha = Math.min(alpha, a);
                img.setRGB(x, y, (alpha << 24) | (argb & 0xFFFFFF));
            }
        }
    }

    /** Trims empty margins (transparent, or plain paper when the background is kept), leaving a little room around the ink. */
    static BufferedImage crop(BufferedImage img, boolean keepBackground) {
        int minX = img.getWidth(), minY = img.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                boolean ink = keepBackground
                        ? (argb >>> 24) > 40 && Math.min((argb >> 16) & 0xFF, Math.min((argb >> 8) & 0xFF, argb & 0xFF)) < 225
                        : (argb >>> 24) > 40;
                if (ink) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) throw new BusinessRuleException("IMAGE_BLANK", "No ink could be found in the image. Scan the signature or stamp in dark ink on white paper.");
        int pad = Math.max(4, (maxX - minX) / 40);
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(img.getWidth() - 1, maxX + pad);
        maxY = Math.min(img.getHeight() - 1, maxY + pad);
        return img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }
}
