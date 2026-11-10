package tz.co.msingi.cims.common.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.common.exception.BusinessRuleException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * A file someone uploads to be kept in the database: lecture notes, assignments, qualification certificates. The type is decided by
 * the file's first bytes, never by its name alone; office documents are told apart by their extension once the bytes show they are
 * a ZIP (docx, xlsx, pptx) or an older OLE file (doc, xls, ppt). Anything else is refused.
 */
public record UploadedFile(String fileName, String contentType, byte[] data, String sha256) {

    public enum Allowed { DOCUMENTS, PDF_OR_IMAGE }

    private static final Map<String, String> OOXML = Map.of(
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "zip", "application/zip");
    private static final Map<String, String> OLE = Map.of(
            "doc", "application/msword", "xls", "application/vnd.ms-excel", "ppt", "application/vnd.ms-powerpoint");

    public int size() {
        return data.length;
    }

    /** Reads and checks the file; null when no file was sent. */
    public static UploadedFile read(MultipartFile f, int maxBytes, Allowed allowed) {
        if (f == null || f.isEmpty()) return null;
        byte[] data;
        try {
            data = f.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String name = f.getOriginalFilename() == null ? "file" : f.getOriginalFilename();
        if (data.length > maxBytes) {
            throw new BusinessRuleException("FILE_TOO_LARGE", name + " is larger than " + (maxBytes / (1024 * 1024)) + " MB.");
        }
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String type = sniff(data, ext, allowed);
        if (type == null) {
            throw new BusinessRuleException("FILE_TYPE", allowed == Allowed.DOCUMENTS
                    ? "Upload a PDF, Word, Excel or PowerPoint file, a picture (JPEG or PNG), a text file or a ZIP (" + name + ")."
                    : "Upload a PDF, JPEG or PNG file (" + name + ").");
        }
        return new UploadedFile(clean(name), type, data, sha256(data));
    }

    static String sniff(byte[] d, String ext, Allowed allowed) {
        if (d.length > 4 && d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F') return "application/pdf";
        if (d.length > 3 && (d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8 && (d[2] & 0xff) == 0xFF) return "image/jpeg";
        if (d.length > 8 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        if (allowed == Allowed.PDF_OR_IMAGE) return null;
        if (d.length > 4 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4) return OOXML.get(ext);
        if (d.length > 8 && (d[0] & 0xff) == 0xD0 && (d[1] & 0xff) == 0xCF && (d[2] & 0xff) == 0x11 && (d[3] & 0xff) == 0xE0) return OLE.get(ext);
        if ("txt".equals(ext) && plainText(d)) return "text/plain";
        return null;
    }

    private static boolean plainText(byte[] d) {
        int n = Math.min(d.length, 4096);
        for (int i = 0; i < n; i++) if (d[i] == 0) return false;   // a binary file renamed .txt
        return true;
    }

    private static String clean(String name) {
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|*?:]", "_").trim();
        if (n.isEmpty()) n = "file";
        return n.length() > 150 ? n.substring(n.length() - 150) : n;
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sends a stored file as a download; never cached by the browser or a proxy. */
    public static ResponseEntity<byte[]> download(String fileName, String contentType, byte[] data) {
        return ResponseEntity.ok()
                .header("Content-Disposition", ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(contentType)).body(data);
    }
}
