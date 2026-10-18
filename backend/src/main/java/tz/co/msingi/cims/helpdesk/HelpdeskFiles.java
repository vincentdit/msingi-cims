package tz.co.msingi.cims.helpdesk;

import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.common.exception.BusinessRuleException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Files attached to helpdesk and Msingi support messages: at most {@value #MAX_FILES} PDF, JPEG or PNG files of up to 5 MB each,
 * checked by their first bytes whatever their name says.
 */
public final class HelpdeskFiles {

    public static final int MAX_FILES = 3;
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    public record Upload(String fileName, String contentType, byte[] data, String sha256) {
    }

    public record AttachmentView(Long id, String fileName, String contentType, int sizeBytes) {
    }

    public record FileContent(String fileName, String contentType, byte[] data) {
    }

    private HelpdeskFiles() {
    }

    /** Checks and reads the uploaded files (none is fine). */
    public static List<Upload> read(List<MultipartFile> files) {
        List<Upload> out = new ArrayList<>();
        if (files == null) return out;
        List<MultipartFile> real = files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (real.size() > MAX_FILES) throw new BusinessRuleException("TOO_MANY_FILES", "Attach at most " + MAX_FILES + " files to one message.");
        for (MultipartFile f : real) {
            byte[] data;
            try {
                data = f.getBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (data.length > MAX_BYTES) throw new BusinessRuleException("FILE_TOO_LARGE", f.getOriginalFilename() + " is larger than 5 MB.");
            String type = sniff(data);
            if (type == null) throw new BusinessRuleException("FILE_TYPE", "Attach PDF, JPEG or PNG files only (" + f.getOriginalFilename() + ").");
            out.add(new Upload(cleanName(f.getOriginalFilename(), type), type, data, sha256(data)));
        }
        return out;
    }

    static String sniff(byte[] d) {
        if (d.length > 4 && d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F') return "application/pdf";
        if (d.length > 3 && (d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8 && (d[2] & 0xff) == 0xFF) return "image/jpeg";
        if (d.length > 8 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        return null;
    }

    static String cleanName(String name, String type) {
        String ext = switch (type) { case "application/pdf" -> ".pdf"; case "image/png" -> ".png"; default -> ".jpg"; };
        String base = name == null ? "attachment" : name.replaceAll(".*[\\\\/]", "").replaceAll("\\.[A-Za-z0-9]{1,5}$", "")
                .replaceAll("[^A-Za-z0-9 ._-]", "").trim();
        if (base.isEmpty()) base = "attachment";
        if (base.length() > 150) base = base.substring(0, 150);
        return base + ext;
    }

    static String sha256(byte[] d) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String s) {
        return sha256(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
