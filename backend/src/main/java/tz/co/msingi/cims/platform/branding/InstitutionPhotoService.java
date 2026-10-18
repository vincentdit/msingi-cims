package tz.co.msingi.cims.platform.branding;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.branding.InstitutionPhoto.Kind;
import tz.co.msingi.cims.platform.branding.InstitutionPhotoRepository.PhotoInfo;
import tz.co.msingi.cims.platform.security.CurrentUser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/** The institution's photos on its portal: uploaded, re-captioned and removed by the administrator, served to everyone. */
@Service
@RequiredArgsConstructor
public class InstitutionPhotoService {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MIN_WIDTH = 800;

    private final InstitutionPhotoRepository photos;
    private final AuditService audit;

    public record PhotoView(String kind, String url, String caption, String contentType, int sizeBytes, Integer width,
                            Integer height, String uploadedByName, String updatedAt) {
    }

    @Transactional(readOnly = true)
    public Optional<PhotoView> view(Kind kind) {
        return photos.info(kind).map(i -> toView(kind, i));
    }

    @Transactional(readOnly = true)
    public InstitutionPhoto image(Kind kind) {
        return photos.findByKind(kind).orElseThrow(() -> new NotFoundException("No photo has been uploaded"));
    }

    @Transactional
    public PhotoView upload(Kind kind, byte[] bytes, String caption) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessRuleException("PHOTO_EMPTY", "Choose a photo to upload.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new BusinessRuleException("PHOTO_TOO_LARGE", "The photo is larger than 5 MB. Save it smaller and try again.");
        }
        String type = sniff(bytes);
        if (type == null) {
            throw new BusinessRuleException("PHOTO_TYPE", "Upload a JPEG, PNG or WebP photo.");
        }
        // the logo is printed on posters and documents, which take JPEG and PNG (PNG keeps a transparent background)
        if (kind == Kind.LOGO && type.equals("image/webp")) {
            throw new BusinessRuleException("PHOTO_TYPE", "Upload the logo as PNG (best, with a transparent background) or JPEG.");
        }
        int minWidth = kind == Kind.LOGO ? 200 : MIN_WIDTH;
        Integer width = null, height = null;
        if (!type.equals("image/webp")) {
            BufferedImage img = read(bytes);
            if (img == null) {
                throw new BusinessRuleException("PHOTO_UNREADABLE", "The photo could not be read. Try saving it again as JPEG.");
            }
            width = img.getWidth();
            height = img.getHeight();
            if (width < minWidth) {
                throw new BusinessRuleException("PHOTO_TOO_SMALL",
                        "The image is " + width + " pixels wide; use one at least " + minWidth + " pixels wide so it stays sharp.");
            }
        }
        InstitutionPhoto p = photos.findByKind(kind).orElseGet(InstitutionPhoto::new);
        p.setKind(kind);
        p.setContentType(type);
        p.setData(bytes);
        p.setSizeBytes(bytes.length);
        p.setWidth(width);
        p.setHeight(height);
        p.setCaption(clean(caption));
        p.setUploadedByName(CurrentUser.name());
        photos.saveAndFlush(p);
        audit.record("UPLOAD", "InstitutionPhoto", p.getId(), Map.of("kind", kind.name(), "bytes", bytes.length));
        return view(kind).orElseThrow();
    }

    @Transactional
    public PhotoView caption(Kind kind, String caption) {
        InstitutionPhoto p = image(kind);
        p.setCaption(clean(caption));
        photos.saveAndFlush(p);
        audit.record("UPDATE", "InstitutionPhoto", p.getId(), Map.of("kind", kind.name()));
        return view(kind).orElseThrow();
    }

    @Transactional
    public void remove(Kind kind) {
        photos.findByKind(kind).ifPresent(p -> {
            photos.delete(p);
            audit.record("DELETE", "InstitutionPhoto", p.getId(), Map.of("kind", kind.name()));
        });
    }

    private static PhotoView toView(Kind kind, PhotoInfo i) {
        return new PhotoView(kind.name(), "/api/public/institution/photos/" + kind.name().toLowerCase() + "?v=" + i.version(),
                i.caption(), i.contentType(), i.sizeBytes(), i.width(), i.height(), i.uploadedByName(),
                i.updatedAt() == null ? null : i.updatedAt().toString());
    }

    private static String clean(String caption) {
        if (caption == null || caption.isBlank()) return null;
        String c = caption.strip();
        if (c.length() > 150) {
            throw new BusinessRuleException("CAPTION_TOO_LONG", "Keep the caption to 150 characters.");
        }
        return c;
    }

    /** The image type from its first bytes, so a renamed file of another kind is not accepted. */
    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && Arrays.equals(Arrays.copyOf(b, 8), new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})) return "image/png";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static BufferedImage read(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
    }
}
