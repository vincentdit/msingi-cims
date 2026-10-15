package tz.co.msingi.cims.admission.document;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicationRepository;
import tz.co.msingi.cims.admission.domain.Applicant;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.service.ApplicantProfileService;
import tz.co.msingi.cims.admission.service.ApplicationEditGuard;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Documents an admission window asks applicants to upload (birth certificate, certificates, a passport photo, a medical form…),
 * size-limited PDF, JPEG or PNG, checked by the Admissions Office (FR-ADM-07). A required document must be uploaded before the
 * application can be submitted; a document the office does not accept can be uploaded again.
 */
@Service
@RequiredArgsConstructor
public class ApplicationDocumentService {

    private final JdbcTemplate jdbc;
    private final ApplicationRepository applications;
    private final ApplicantProfileService profiles;
    private final ApplicationEditGuard editGuard;
    private final AuditService audit;
    private final Messaging messaging;

    public record TypeRequest(@NotBlank @Pattern(regexp = "[A-Z0-9_]{2,30}") String code, @NotBlank @Size(max = 150) String name,
                              @Size(max = 500) String description, boolean required, @Min(50) @Max(5120) int maxKb, int sortOrder) {
    }

    public record TypeView(Long id, String code, String name, String description, boolean required, int maxKb, int sortOrder, long uploads) {
    }

    /** One document type of the window with this application's upload, if any. */
    public record DocumentView(Long typeId, String code, String name, String description, boolean required, int maxKb,
                               Long documentId, String fileName, String contentType, Integer sizeBytes, String status, String reviewNote,
                               String reviewedByName, OffsetDateTime reviewedAt, OffsetDateTime uploadedAt, boolean canUpload) {
    }

    public record Review(boolean accept, @Size(max = 500) String note) {
    }

    public record FileContent(String fileName, String contentType, byte[] data) {
    }

    // ------------------------------------------------------------------ the window's list (Admissions Office)

    @Transactional(readOnly = true)
    public List<TypeView> types(Long windowId) {
        return jdbc.query("""
                select t.id, t.code, t.name, t.description, t.required, t.max_kb, t.sort_order,
                       (select count(*) from application_documents d where d.document_type_id = t.id)
                from window_document_types t where t.window_id = ? order by t.sort_order, t.id""",
                (rs, i) -> new TypeView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getInt(6),
                        rs.getInt(7), rs.getLong(8)), windowId);
    }

    @Transactional
    public TypeView addType(Long windowId, TypeRequest r) {
        Integer w = jdbc.queryForObject("select count(*) from admission_windows where id = ?", Integer.class, windowId);
        if (w == null || w == 0) throw new NotFoundException("Admission window", windowId);
        Integer dup = jdbc.queryForObject("select count(*) from window_document_types where window_id = ? and code = ?", Integer.class, windowId, r.code());
        if (dup != null && dup > 0) throw new BusinessRuleException("DUPLICATE_CODE", "This window already asks for a document with code " + r.code());
        Long id = jdbc.queryForObject("""
                insert into window_document_types (tenant_id, window_id, code, name, description, required, max_kb, sort_order)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                windowId, r.code(), r.name().trim(), blank(r.description()), r.required(), r.maxKb(), r.sortOrder());
        audit.record("DOCUMENT_TYPE_ADDED", "AdmissionWindow", windowId, Map.of("code", r.code(), "required", r.required()));
        return type(id);
    }

    @Transactional
    public TypeView updateType(Long id, TypeRequest r) {
        TypeView before = type(id);
        if (!before.code().equals(r.code()) && before.uploads() > 0) {
            throw new BusinessRuleException("IN_USE", "Applicants have uploaded this document; its code cannot change.");
        }
        jdbc.update("""
                update window_document_types set code = ?, name = ?, description = ?, required = ?, max_kb = ?, sort_order = ?,
                  updated_at = now(), version = version + 1 where id = ?""",
                r.code(), r.name().trim(), blank(r.description()), r.required(), r.maxKb(), r.sortOrder(), id);
        audit.record("DOCUMENT_TYPE_UPDATED", "AdmissionWindow", windowOf(id), Map.of("code", r.code(), "required", r.required()));
        return type(id);
    }

    @Transactional
    public void deleteType(Long id) {
        TypeView t = type(id);
        if (t.uploads() > 0) throw new BusinessRuleException("IN_USE", "Applicants have uploaded this document. Make it optional instead.");
        Long window = windowOf(id);
        jdbc.update("delete from window_document_types where id = ?", id);
        audit.record("DOCUMENT_TYPE_REMOVED", "AdmissionWindow", window, Map.of("code", t.code()));
    }

    // ------------------------------------------------------------------ the applicant

    @Transactional(readOnly = true)
    public List<DocumentView> mine(Long applicationId) {
        Application app = own(applicationId);
        return documents(app, true);
    }

    @Transactional
    public DocumentView upload(Long applicationId, Long typeId, String fileName, byte[] data) {
        Application app = own(applicationId);
        DocumentView current = documents(app, true).stream().filter(d -> d.typeId().equals(typeId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Document type", typeId));
        if (!current.canUpload()) {
            throw new BusinessRuleException("DOCUMENTS_LOCKED", "Documents can no longer be changed for this application.");
        }
        if (data == null || data.length == 0) throw new BusinessRuleException("EMPTY_FILE", "The file is empty.");
        if (data.length > current.maxKb() * 1024) {
            throw new BusinessRuleException("FILE_TOO_LARGE", "The file is larger than " + size(current.maxKb()) + ". Scan at a lower resolution or compress it.");
        }
        String type = sniff(data);
        if (type == null) throw new BusinessRuleException("FILE_TYPE", "Upload a PDF, JPEG or PNG file.");
        String name = cleanName(fileName, type);
        String hash = sha256(data);
        int n = jdbc.update("""
                update application_documents set file_name = ?, content_type = ?, data = ?, size_bytes = ?, sha256 = ?, status = 'UPLOADED',
                  review_note = null, reviewed_by_name = null, reviewed_at = null, uploaded_at = now(), version = version + 1
                where application_id = ? and document_type_id = ?""", name, type, data, data.length, hash, app.getId(), typeId);
        if (n == 0) {
            jdbc.update("""
                    insert into application_documents (tenant_id, application_id, document_type_id, file_name, content_type, data, size_bytes, sha256)
                    values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?)""", app.getId(), typeId, name, type, data, data.length, hash);
        }
        editGuard.markAmended(app);
        audit.record("DOCUMENT_UPLOADED", "Application", app.getId(), Map.of("document", current.code(), "file", name, "bytes", data.length));
        return documents(app, true).stream().filter(d -> d.typeId().equals(typeId)).findFirst().orElseThrow();
    }

    @Transactional
    public void remove(Long applicationId, Long typeId) {
        Application app = own(applicationId);
        if (!editGuard.changeable(app)) throw new BusinessRuleException("DOCUMENTS_LOCKED", "Documents can no longer be changed for this application.");
        int n = jdbc.update("delete from application_documents where application_id = ? and document_type_id = ? and status <> 'ACCEPTED'", app.getId(), typeId);
        if (n > 0) audit.record("DOCUMENT_REMOVED", "Application", app.getId(), Map.of("typeId", typeId));
    }

    @Transactional(readOnly = true)
    public FileContent myFile(Long applicationId, Long typeId) {
        Application app = own(applicationId);
        return file("select file_name, content_type, data from application_documents where application_id = ? and document_type_id = ?", app.getId(), typeId);
    }

    /** What is still missing (for the submit check and the applicant's to-do list). */
    @Transactional(readOnly = true)
    public List<String> missingRequired(Application app) {
        return jdbc.queryForList("""
                select t.name from window_document_types t where t.window_id = ? and t.required
                  and not exists (select 1 from application_documents d where d.document_type_id = t.id and d.application_id = ? and d.status <> 'REJECTED')
                order by t.sort_order, t.id""", String.class, app.getWindow().getId(), app.getId());
    }

    // ------------------------------------------------------------------ the office

    @Transactional(readOnly = true)
    public List<DocumentView> forApplication(Long applicationId) {
        Application app = applications.findById(applicationId).orElseThrow(() -> new NotFoundException("Application", applicationId));
        return documents(app, false);
    }

    @Transactional
    public FileContent file(Long documentId) {
        FileContent f = file("select file_name, content_type, data from application_documents where id = ?", documentId);
        audit.record("DOCUMENT_VIEWED", "Application", applicationOf(documentId), Map.of("documentId", documentId));
        return f;
    }

    @Transactional
    public DocumentView review(Long documentId, Review r) {
        if (!r.accept() && (r.note() == null || r.note().isBlank())) {
            throw new BusinessRuleException("NOTE_REQUIRED", "Say why the document is not accepted, so the applicant can upload the right one.");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.application_id, t.name, a.phone from application_documents d join window_document_types t on t.id = d.document_type_id
                join applications ap on ap.id = d.application_id join applicants a on a.id = ap.applicant_id where d.id = ?""", documentId);
        if (rows.isEmpty()) throw new NotFoundException("Document", documentId);
        Long appId = ((Number) rows.get(0).get("application_id")).longValue();
        jdbc.update("""
                update application_documents set status = ?, review_note = ?, reviewed_by_name = ?, reviewed_at = now(), version = version + 1
                where id = ?""", r.accept() ? "ACCEPTED" : "REJECTED", blank(r.note()), CurrentUser.name(), documentId);
        audit.record(r.accept() ? "DOCUMENT_ACCEPTED" : "DOCUMENT_REJECTED", "Application", appId,
                Map.of("document", rows.get(0).get("name"), "note", r.note() == null ? "" : r.note()));
        if (!r.accept()) {
            messaging.event("DOCUMENT_NOT_ACCEPTED", (String) rows.get(0).get("phone"),
                    Map.of("document", (String) rows.get(0).get("name"), "reason", r.note().trim()));
        }
        Application app = applications.findById(appId).orElseThrow();
        return documents(app, false).stream().filter(d -> documentId.equals(d.documentId())).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ helpers

    private List<DocumentView> documents(Application app, boolean applicant) {
        boolean changeable = editGuard.changeable(app) || app.getStatus() == Application.Status.SUBMITTED
                || app.getStatus() == Application.Status.UNDER_REVIEW;
        return jdbc.query("""
                select t.id, t.code, t.name, t.description, t.required, t.max_kb, d.id, d.file_name, d.content_type, d.size_bytes, d.status,
                       d.review_note, d.reviewed_by_name, d.reviewed_at, d.uploaded_at
                from window_document_types t left join application_documents d on d.document_type_id = t.id and d.application_id = ?
                where t.window_id = ? order by t.sort_order, t.id""",
                (rs, i) -> {
                    String status = rs.getString(11);
                    // the applicant may replace a document while the application can change, or when the office did not accept it
                    boolean can = applicant && !"ACCEPTED".equals(status) && (editGuard.changeable(app) || ("REJECTED".equals(status) && changeable));
                    return new DocumentView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getInt(6),
                            (Long) rs.getObject(7), rs.getString(8), rs.getString(9), (Integer) rs.getObject(10), status, rs.getString(12),
                            rs.getString(13), rs.getObject(14, OffsetDateTime.class), rs.getObject(15, OffsetDateTime.class), can);
                }, app.getId(), app.getWindow().getId());
    }

    private Application own(Long id) {
        Applicant a = profiles.currentApplicant();
        return applications.findById(id).filter(x -> x.getApplicant().getId().equals(a.getId()))
                .orElseThrow(() -> new NotFoundException("Application", id));
    }

    private TypeView type(Long id) {
        List<TypeView> r = jdbc.query("""
                select t.id, t.code, t.name, t.description, t.required, t.max_kb, t.sort_order,
                       (select count(*) from application_documents d where d.document_type_id = t.id)
                from window_document_types t where t.id = ?""",
                (rs, i) -> new TypeView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getInt(6),
                        rs.getInt(7), rs.getLong(8)), id);
        if (r.isEmpty()) throw new NotFoundException("Document type", id);
        return r.get(0);
    }

    private Long windowOf(Long typeId) {
        return jdbc.queryForObject("select window_id from window_document_types where id = ?", Long.class, typeId);
    }

    private Long applicationOf(Long documentId) {
        return jdbc.queryForObject("select application_id from application_documents where id = ?", Long.class, documentId);
    }

    private FileContent file(String sql, Object... args) {
        List<FileContent> r = jdbc.query(sql, (rs, i) -> new FileContent(rs.getString(1), rs.getString(2), rs.getBytes(3)), args);
        if (r.isEmpty()) throw new NotFoundException("Document not found");
        return r.get(0);
    }

    /** The real type from the first bytes, whatever the name or the browser says. */
    static String sniff(byte[] d) {
        if (d.length > 4 && d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F') return "application/pdf";
        if (d.length > 3 && (d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8 && (d[2] & 0xff) == 0xFF) return "image/jpeg";
        if (d.length > 8 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        return null;
    }

    private static String cleanName(String name, String type) {
        String ext = switch (type) { case "application/pdf" -> ".pdf"; case "image/png" -> ".png"; default -> ".jpg"; };
        String base = name == null ? "document" : name.replaceAll(".*[\\\\/]", "").replaceAll("\\.[A-Za-z0-9]{1,5}$", "")
                .replaceAll("[^A-Za-z0-9 ._-]", "").trim();
        if (base.isEmpty()) base = "document";
        if (base.length() > 150) base = base.substring(0, 150);
        return base + ext;
    }

    private static String sha256(byte[] d) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String size(int kb) {
        return kb >= 1024 ? (kb % 1024 == 0 ? kb / 1024 + " MB" : String.format("%.1f MB", kb / 1024.0)) : kb + " KB";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
