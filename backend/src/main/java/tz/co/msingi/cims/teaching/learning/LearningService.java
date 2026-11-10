package tz.co.msingi.cims.teaching.learning;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.academic.curriculum.Module;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.common.web.UploadedFile;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleOfferingRepository;
import tz.co.msingi.cims.exams.ModuleOffering;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Learning materials (FR-TCH-06): the lecturers of a module offering post notes, links and assignments for the students registered
 * in it (an approved semester registration that includes the module); students download them and hand in assignments, and the
 * lecturers mark them with feedback. Heads of department and the academic offices can read everything for the modules they follow.
 */
@Service
@RequiredArgsConstructor
public class LearningService {

    public static final int MAX_MATERIAL_BYTES = 20 * 1024 * 1024;
    public static final int MAX_SUBMISSION_BYTES = 10 * 1024 * 1024;
    private static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");

    private final JdbcTemplate jdbc;
    private final ModuleOfferingRepository offerings;
    private final ExamAccess access;
    private final AuditService audit;
    private final Messaging messaging;

    public record MaterialView(Long id, Long offeringId, String kind, String title, String body, String linkUrl, String fileName, String contentType,
                               Integer sizeBytes, OffsetDateTime dueAt, BigDecimal maxScore, boolean acceptLate, boolean published,
                               String createdByName, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                               Integer submissions, Integer marked, Integer classSize, MySubmission mine) {
    }

    public record MySubmission(Long id, String fileName, int sizeBytes, OffsetDateTime submittedAt, boolean late, int timesSubmitted, String note,
                               BigDecimal score, String feedback, OffsetDateTime markedAt) {
    }

    public record SubmissionView(Long id, Long studentId, String registrationNo, String studentName, String fileName, int sizeBytes,
                                 OffsetDateTime submittedAt, boolean late, int timesSubmitted, String note, BigDecimal score, String feedback,
                                 String markedByName, OffsetDateTime markedAt) {
    }

    public record ClassMember(Long studentId, String registrationNo, String studentName, SubmissionView submission) {
    }

    public record AssignmentSheet(MaterialView assignment, List<ClassMember> students) {
    }

    public record MaterialRequest(String kind, String title, String body, String linkUrl, OffsetDateTime dueAt, BigDecimal maxScore,
                                  Boolean acceptLate, Boolean published, Boolean tellClass) {
    }

    public record MyModule(Long offeringId, String code, String title, String semester, int notes, int assignments, int openAssignments,
                           int handedIn) {
    }

    public record FileContent(String fileName, String contentType, byte[] data) {
    }

    // ================================================================== lecturers and those who follow the module

    @Transactional(readOnly = true)
    public List<MaterialView> forOffering(Long offeringId) {
        requireRead(offeringId);
        int size = classSize(offeringId);
        return jdbc.query(BASE + " where m.offering_id = ? order by m.created_at desc", (rs, i) -> view(rs, size, null), offeringId);
    }

    @Transactional
    public MaterialView post(Long offeringId, MaterialRequest r, MultipartFile file) {
        requireTeach(offeringId);
        String kind = r.kind() == null ? "NOTE" : r.kind().toUpperCase(Locale.ROOT);
        if (!List.of("NOTE", "ASSIGNMENT").contains(kind)) throw new BusinessRuleException("MATERIAL_KIND", "Choose a note or an assignment");
        String title = required(r.title(), 200, "a title");
        String link = link(r.linkUrl());
        UploadedFile f = UploadedFile.read(file, MAX_MATERIAL_BYTES, UploadedFile.Allowed.DOCUMENTS);
        if ("NOTE".equals(kind) && f == null && link == null && blank(r.body()) == null) {
            throw new BusinessRuleException("MATERIAL_EMPTY", "Add a file, a link or some text");
        }
        if ("ASSIGNMENT".equals(kind)) {
            if (r.dueAt() == null) throw new BusinessRuleException("DUE_REQUIRED", "Give the date and time the assignment is due");
            if (r.dueAt().isBefore(OffsetDateTime.now())) throw new BusinessRuleException("DUE_PAST", "The due date has already passed");
        }
        Long id = jdbc.queryForObject("""
                insert into course_materials (tenant_id, offering_id, kind, title, body, link_url, file_name, content_type, size_bytes, data, due_at,
                                              max_score, accept_late, published, created_by, created_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                offeringId, kind, title, limit(blank(r.body()), 4000), link, f == null ? null : f.fileName(), f == null ? null : f.contentType(),
                f == null ? null : f.size(), f == null ? null : f.data(), "ASSIGNMENT".equals(kind) ? ts(r.dueAt()) : null,
                "ASSIGNMENT".equals(kind) ? r.maxScore() : null, !Boolean.FALSE.equals(r.acceptLate()), !Boolean.FALSE.equals(r.published()),
                CurrentUser.requireId(), CurrentUser.name());
        audit.record("COURSE_MATERIAL_POSTED", "CourseMaterial", id, Map.of("offeringId", offeringId, "kind", kind, "file", f != null));
        if ("ASSIGNMENT".equals(kind) && Boolean.TRUE.equals(r.tellClass()) && !Boolean.FALSE.equals(r.published())) tellClass(offeringId, title, r.dueAt());
        return one(id);
    }

    @Transactional
    public MaterialView update(Long id, MaterialRequest r) {
        Map<String, Object> m = row(id);
        requireTeach((Long) m.get("offering_id"));
        String title = required(r.title(), 200, "a title");
        boolean assignment = "ASSIGNMENT".equals(m.get("kind"));
        if (assignment && r.dueAt() == null) throw new BusinessRuleException("DUE_REQUIRED", "Give the date and time the assignment is due");
        jdbc.update("""
                update course_materials set title = ?, body = ?, link_url = ?, due_at = ?, max_score = ?, accept_late = ?, published = ?, updated_at = now()
                where id = ?""", title, limit(blank(r.body()), 4000), link(r.linkUrl()), assignment ? ts(r.dueAt()) : null, assignment ? r.maxScore() : null,
                !Boolean.FALSE.equals(r.acceptLate()), !Boolean.FALSE.equals(r.published()), id);
        audit.record("COURSE_MATERIAL_CHANGED", "CourseMaterial", id, Map.of("published", !Boolean.FALSE.equals(r.published())));
        return one(id);
    }

    @Transactional
    public void delete(Long id) {
        Map<String, Object> m = row(id);
        requireTeach((Long) m.get("offering_id"));
        Integer handedIn = jdbc.queryForObject("select count(*) from assignment_submissions where material_id = ?", Integer.class, id);
        if (handedIn != null && handedIn > 0) {
            throw new BusinessRuleException("HAS_SUBMISSIONS", "Students have handed in work for this assignment: hide it instead of deleting it");
        }
        jdbc.update("delete from course_materials where id = ?", id);
        audit.record("COURSE_MATERIAL_DELETED", "CourseMaterial", id, Map.of("title", m.get("title")));
    }

    @Transactional(readOnly = true)
    public FileContent file(Long id) {
        Map<String, Object> m = row(id);
        Long offeringId = (Long) m.get("offering_id");
        if (isStudent()) {
            Long me = myStudentId();
            if (!registered(offeringId, me) || !Boolean.TRUE.equals(m.get("published"))) throw new NotFoundException("Material not found");
        } else {
            requireRead(offeringId);
        }
        if (m.get("file_name") == null) throw new NotFoundException("This item has no file");
        byte[] data = jdbc.queryForObject("select data from course_materials where id = ?", byte[].class, id);
        return new FileContent((String) m.get("file_name"), (String) m.get("content_type"), data);
    }

    /** The class list of an assignment with what each student handed in. */
    @Transactional(readOnly = true)
    public AssignmentSheet submissions(Long materialId) {
        Map<String, Object> m = row(materialId);
        Long offeringId = (Long) m.get("offering_id");
        requireRead(offeringId);
        if (!"ASSIGNMENT".equals(m.get("kind"))) throw new BusinessRuleException("NOT_ASSIGNMENT", "Only assignments have submissions");
        Map<Long, SubmissionView> byStudent = new java.util.HashMap<>();
        jdbc.query(SUB_BASE + " where a.material_id = ?", rs -> {
            SubmissionView v = sub(rs);
            byStudent.put(v.studentId(), v);
        }, materialId);
        List<ClassMember> students = new java.util.ArrayList<>(jdbc.query(CLASS + " order by s.surname, s.first_name", (rs, i) -> new ClassMember(
                rs.getLong(1), rs.getString(2), rs.getString(3), byStudent.remove(rs.getLong(1))), offeringId));
        // work from a student whose registration has since changed is still shown
        byStudent.values().forEach(v -> students.add(new ClassMember(v.studentId(), v.registrationNo(), v.studentName(), v)));
        return new AssignmentSheet(one(materialId), students);
    }

    @Transactional(readOnly = true)
    public FileContent submissionFile(Long submissionId) {
        Map<String, Object> s = jdbc.queryForList("""
                select a.material_id, a.student_id, a.file_name, a.content_type, m.offering_id from assignment_submissions a
                join course_materials m on m.id = a.material_id where a.id = ?""", submissionId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Submission not found"));
        if (isStudent()) {
            if (!myStudentId().equals(s.get("student_id"))) throw new NotFoundException("Submission not found");
        } else {
            requireRead((Long) s.get("offering_id"));
        }
        byte[] data = jdbc.queryForObject("select data from assignment_submissions where id = ?", byte[].class, submissionId);
        return new FileContent((String) s.get("file_name"), (String) s.get("content_type"), data);
    }

    @Transactional
    public SubmissionView mark(Long submissionId, BigDecimal score, String feedback) {
        Map<String, Object> s = jdbc.queryForList("""
                select m.offering_id, m.max_score from assignment_submissions a join course_materials m on m.id = a.material_id where a.id = ?""",
                submissionId).stream().findFirst().orElseThrow(() -> new NotFoundException("Submission not found"));
        requireTeach((Long) s.get("offering_id"));
        if (score != null && score.signum() < 0) throw new BusinessRuleException("SCORE_INVALID", "The score cannot be negative");
        BigDecimal max = (BigDecimal) s.get("max_score");
        if (score != null && max != null && score.compareTo(max) > 0) throw new BusinessRuleException("SCORE_INVALID", "The score is out of " + max.stripTrailingZeros().toPlainString());
        jdbc.update("update assignment_submissions set score = ?, feedback = ?, marked_by_name = ?, marked_at = now() where id = ?",
                score, limit(blank(feedback), 2000), CurrentUser.name(), submissionId);
        audit.record("ASSIGNMENT_MARKED", "AssignmentSubmission", submissionId, score == null ? Map.of() : Map.of("score", score));
        return jdbc.query(SUB_BASE + " where a.id = ?", (rs, i) -> sub(rs), submissionId).get(0);
    }

    // ================================================================== students

    /** The modules of the student's latest approved semester registration, with what has been posted in each. */
    @Transactional(readOnly = true)
    public List<MyModule> myModules() {
        Long me = myStudentId();
        return jdbc.query("""
                select o.id, m.code, m.title, se.name,
                  (select count(*) from course_materials c where c.offering_id = o.id and c.published and c.kind = 'NOTE'),
                  (select count(*) from course_materials c where c.offering_id = o.id and c.published and c.kind = 'ASSIGNMENT'),
                  (select count(*) from course_materials c where c.offering_id = o.id and c.published and c.kind = 'ASSIGNMENT' and c.due_at > now()
                     and not exists (select 1 from assignment_submissions a where a.material_id = c.id and a.student_id = ?)),
                  (select count(*) from assignment_submissions a join course_materials c on c.id = a.material_id where c.offering_id = o.id and a.student_id = ?)
                from semester_registrations sr join module_registrations mr on mr.semester_registration_id = sr.id
                join module_offerings o on o.module_id = mr.module_id and o.semester_id = sr.semester_id
                join modules m on m.id = o.module_id join semesters se on se.id = sr.semester_id
                where sr.student_id = ? and sr.status = 'APPROVED'
                  and sr.semester_id = (select sr2.semester_id from semester_registrations sr2 join semesters s2 on s2.id = sr2.semester_id
                                        where sr2.student_id = ? and sr2.status = 'APPROVED' order by s2.start_date desc limit 1)
                order by m.code""", (rs, i) -> new MyModule(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6),
                rs.getInt(7), rs.getInt(8)), me, me, me, me);
    }

    @Transactional(readOnly = true)
    public List<MaterialView> myMaterials(Long offeringId) {
        Long me = myStudentId();
        if (!registered(offeringId, me)) throw new NotFoundException("You are not registered for this module");
        return jdbc.query(BASE + " where m.offering_id = ? and m.published order by m.created_at desc", (rs, i) -> view(rs, null, mine(rs.getLong("id"), me)),
                offeringId);
    }

    @Transactional
    public MaterialView handIn(Long materialId, MultipartFile file, String note) {
        Long me = myStudentId();
        Map<String, Object> m = row(materialId);
        if (!"ASSIGNMENT".equals(m.get("kind")) || !Boolean.TRUE.equals(m.get("published")) || !registered((Long) m.get("offering_id"), me)) {
            throw new NotFoundException("Assignment not found");
        }
        UploadedFile f = UploadedFile.read(file, MAX_SUBMISSION_BYTES, UploadedFile.Allowed.DOCUMENTS);
        if (f == null) throw new BusinessRuleException("FILE_REQUIRED", "Choose the file to hand in");
        OffsetDateTime due = ((Timestamp) m.get("due_at")).toInstant().atOffset(ZoneOffset.UTC);
        boolean late = OffsetDateTime.now().isAfter(due);
        if (late && !Boolean.TRUE.equals(m.get("accept_late"))) throw new BusinessRuleException("ASSIGNMENT_CLOSED", "The assignment was due on " + when(due) + "; it no longer takes work");
        Map<String, Object> prior = jdbc.queryForList("select id, marked_at from assignment_submissions where material_id = ? and student_id = ?", materialId, me)
                .stream().findFirst().orElse(null);
        if (prior != null && prior.get("marked_at") != null) throw new BusinessRuleException("ALREADY_MARKED", "Your work has been marked; it cannot be replaced");
        jdbc.update("""
                insert into assignment_submissions (tenant_id, material_id, student_id, file_name, content_type, size_bytes, data, sha256, note, late)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (material_id, student_id) do update set file_name = excluded.file_name, content_type = excluded.content_type,
                  size_bytes = excluded.size_bytes, data = excluded.data, sha256 = excluded.sha256, note = excluded.note, late = excluded.late,
                  submitted_at = now(), times_submitted = assignment_submissions.times_submitted + 1""",
                materialId, me, f.fileName(), f.contentType(), f.size(), f.data(), f.sha256(), limit(blank(note), 1000), late);
        audit.record("ASSIGNMENT_HANDED_IN", "CourseMaterial", materialId, Map.of("late", late, "sha256", f.sha256()));
        return jdbc.query(BASE + " where m.id = ?", (rs, i) -> view(rs, null, mine(materialId, me)), materialId).get(0);
    }

    // ================================================================== helpers

    private static final String BASE = """
            select m.id, m.offering_id, m.kind, m.title, m.body, m.link_url, m.file_name, m.content_type, m.size_bytes, m.due_at, m.max_score,
                   m.accept_late, m.published, m.created_by_name, m.created_at, m.updated_at,
                   (select count(*) from assignment_submissions a where a.material_id = m.id) as submissions,
                   (select count(*) from assignment_submissions a where a.material_id = m.id and a.marked_at is not null) as marked
            from course_materials m""";

    private static final String SUB_BASE = """
            select a.id, a.student_id, s.registration_no, s.first_name || ' ' || s.surname as name, a.file_name, a.size_bytes, a.submitted_at, a.late,
                   a.times_submitted, a.note, a.score, a.feedback, a.marked_by_name, a.marked_at
            from assignment_submissions a join students s on s.id = a.student_id""";

    /** The students registered for an offering: approved semester registrations that include the module. */
    private static final String CLASS = """
            select s.id, s.registration_no, s.first_name || ' ' || s.surname from module_offerings o
            join semester_registrations sr on sr.semester_id = o.semester_id and sr.status = 'APPROVED'
            join module_registrations mr on mr.semester_registration_id = sr.id and mr.module_id = o.module_id
            join students s on s.id = sr.student_id where o.id = ?""";

    private MaterialView one(Long id) {
        return jdbc.query(BASE + " where m.id = ?", (rs, i) -> view(rs, classSize(rs.getLong("offering_id")), null), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Material not found"));
    }

    private MaterialView view(java.sql.ResultSet rs, Integer classSize, MySubmission mine) throws java.sql.SQLException {
        boolean assignment = "ASSIGNMENT".equals(rs.getString("kind"));
        boolean staff = classSize != null;
        return new MaterialView(rs.getLong("id"), rs.getLong("offering_id"), rs.getString("kind"), rs.getString("title"), rs.getString("body"),
                rs.getString("link_url"), rs.getString("file_name"), rs.getString("content_type"), (Integer) rs.getObject("size_bytes"), odt(rs.getTimestamp("due_at")),
                rs.getBigDecimal("max_score"), rs.getBoolean("accept_late"), rs.getBoolean("published"), rs.getString("created_by_name"),
                odt(rs.getTimestamp("created_at")), odt(rs.getTimestamp("updated_at")),
                staff && assignment ? rs.getInt("submissions") : null, staff && assignment ? rs.getInt("marked") : null, staff ? classSize : null, mine);
    }

    private MySubmission mine(Long materialId, Long studentId) {
        return jdbc.query("""
                select id, file_name, size_bytes, submitted_at, late, times_submitted, note, score, feedback, marked_at
                from assignment_submissions where material_id = ? and student_id = ?""", (rs, i) -> new MySubmission(rs.getLong(1), rs.getString(2), rs.getInt(3),
                odt(rs.getTimestamp(4)), rs.getBoolean(5), rs.getInt(6), rs.getString(7), rs.getBigDecimal(8), rs.getString(9), odt(rs.getTimestamp(10))),
                materialId, studentId).stream().findFirst().orElse(null);
    }

    private static SubmissionView sub(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SubmissionView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), odt(rs.getTimestamp(7)),
                rs.getBoolean(8), rs.getInt(9), rs.getString(10), rs.getBigDecimal(11), rs.getString(12), rs.getString(13), odt(rs.getTimestamp(14)));
    }

    private Map<String, Object> row(Long id) {
        return jdbc.queryForList("select id, offering_id, kind, title, published, file_name, content_type, due_at, accept_late, max_score from course_materials where id = ?", id)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Material not found"));
    }

    private int classSize(Long offeringId) {
        Integer n = jdbc.queryForObject("select count(*) from (" + CLASS + ") c", Integer.class, offeringId);
        return n == null ? 0 : n;
    }

    private boolean teaches(Long offeringId) {
        Integer n = jdbc.queryForObject("select count(*) from offering_staff where offering_id = ? and user_id = ?", Integer.class, offeringId, CurrentUser.requireId());
        return n != null && n > 0;
    }

    private boolean followsModule(Module m) {
        return access.follows(m);
    }

    private ModuleOffering offering(Long id) {
        return offerings.findById(id).orElseThrow(() -> new NotFoundException("Module offering not found"));
    }

    /** Lecturers of the offering may post and mark. Returns whether the signed-in person teaches it. */
    private boolean requireRead(Long offeringId) {
        ModuleOffering o = offering(offeringId);
        if (teaches(offeringId)) return true;
        if (followsModule(o.getModule())) return false;
        throw new org.springframework.security.access.AccessDeniedException("Not a lecturer of this module");
    }

    private void requireTeach(Long offeringId) {
        offering(offeringId);
        if (!teaches(offeringId)) throw new org.springframework.security.access.AccessDeniedException("Only the module's lecturers can do this");
    }

    private boolean registered(Long offeringId, Long studentId) {
        Integer n = jdbc.queryForObject("select count(*) from (" + CLASS + ") c where c.id = ?", Integer.class, offeringId, studentId);
        return n != null && n > 0;
    }

    private boolean isStudent() {
        return access.has(tz.co.msingi.cims.platform.security.Role.STUDENT);
    }

    private Long myStudentId() {
        return jdbc.query("select id from students where user_id = ?", (rs, i) -> rs.getLong(1), CurrentUser.requireId()).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
    }

    private void tellClass(Long offeringId, String title, OffsetDateTime due) {
        String module = jdbc.queryForObject("select m.code from module_offerings o join modules m on m.id = o.module_id where o.id = ?", String.class, offeringId);
        jdbc.query("select s.phone from (" + CLASS + ") c join students s on s.id = c.id", rs -> {
            messaging.event("ASSIGNMENT_POSTED", rs.getString(1), Map.of("module", module, "title", title, "due", when(due)));
        }, offeringId);
    }

    private static String when(OffsetDateTime t) {
        return t.atZoneSameInstant(TZ).format(DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH));
    }

    private static Timestamp ts(OffsetDateTime t) {
        return t == null ? null : Timestamp.from(t.toInstant());
    }

    private static OffsetDateTime odt(Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static String link(String url) {
        String u = blank(url);
        if (u == null) return null;
        if (!u.matches("(?i)https?://[^\\s<>\"]{3,490}")) throw new BusinessRuleException("LINK_INVALID", "Give a web address starting with https:// or http://");
        return u;
    }

    private static String required(String s, int max, String what) {
        String v = blank(s);
        if (v == null) throw new BusinessRuleException("FIELD_REQUIRED", "Give " + what);
        return limit(v, max);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String limit(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
