package tz.co.msingi.cims.registry.idcard;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.admission.letter.LetterCodes;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.numbering.NumberSequenceService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.DataScope;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantDomainRepository;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;
import tz.co.msingi.cims.registry.idcard.IdCardDtos.BatchPreview;
import tz.co.msingi.cims.registry.idcard.IdCardDtos.CardView;
import tz.co.msingi.cims.registry.idcard.IdCardDtos.IdCardSummary;
import tz.co.msingi.cims.registry.idcard.IdCardDtos.IdCardVerification;
import tz.co.msingi.cims.registry.idcard.IdCardRepositories.IdCardRepository;
import tz.co.msingi.cims.registry.idcard.IdCardRepositories.StudentPhotoRepository;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Student photos and ID cards (FR-REG-06). A card carries the photo, name, registration number, programme, validity and a QR
 * code that opens the public verification page, which shows whether the card is still valid and the student's status.
 */
@Service
@RequiredArgsConstructor
public class IdCardService {

    public static final int MAX_PHOTO_BYTES = 2 * 1024 * 1024;
    public static final int MIN_PHOTO_PX = 200;
    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final int MAX_BATCH = 500;

    private final StudentRepository students;
    private final StudentPhotoRepository photos;
    private final IdCardRepository cards;
    private final AcademicYearRepository years;
    private final TenantRepository tenants;
    private final TenantDomainRepository domains;
    private final NumberSequenceService sequences;
    private final DataScope scope;
    private final AuditService audit;

    @Value("${cims.documents.verification-url:https://{host}/verify/{code}}")
    private String verificationUrlTemplate;

    public record Download(String filename, byte[] content) {
    }

    // ------------------------------------------------------------------ photos

    @Transactional
    public IdCardSummary uploadPhoto(Long studentId, byte[] bytes) {
        Student s = student(studentId);
        if (bytes == null || bytes.length == 0) throw new BusinessRuleException("PHOTO_EMPTY", "Choose a photo to upload.");
        if (bytes.length > MAX_PHOTO_BYTES) {
            throw new BusinessRuleException("PHOTO_TOO_LARGE", "The photo is larger than 2 MB. Save it smaller and try again.");
        }
        String type = sniff(bytes);
        if (type == null) throw new BusinessRuleException("PHOTO_TYPE", "Upload a JPEG or PNG photo.");
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            img = null;
        }
        if (img == null) throw new BusinessRuleException("PHOTO_UNREADABLE", "The photo could not be read. Try saving it again as JPEG.");
        if (img.getWidth() < MIN_PHOTO_PX || img.getHeight() < MIN_PHOTO_PX) {
            throw new BusinessRuleException("PHOTO_TOO_SMALL", "The photo is " + img.getWidth() + "×" + img.getHeight()
                    + " pixels; use one at least " + MIN_PHOTO_PX + " pixels each way so the card prints clearly.");
        }
        if (img.getWidth() > img.getHeight() * 1.1) {
            throw new BusinessRuleException("PHOTO_LANDSCAPE", "Use an upright passport-style photo (taller than it is wide).");
        }
        StudentPhoto p = photos.findByStudentId(studentId).orElseGet(StudentPhoto::new);
        boolean replaced = p.getId() != null;
        p.setStudentId(studentId);
        p.setContentType(type);
        p.setData(bytes);
        p.setSizeBytes(bytes.length);
        p.setUploadedByName(CurrentUser.name());
        photos.saveAndFlush(p);
        audit.record(replaced ? "STUDENT_PHOTO_REPLACED" : "STUDENT_PHOTO_UPLOADED", "Student", s.getId(),
                Map.of("registrationNo", String.valueOf(s.getRegistrationNo()), "bytes", bytes.length));
        return summary(studentId);
    }

    @Transactional(readOnly = true)
    public StudentPhoto photo(Long studentId) {
        student(studentId);
        return photos.findByStudentId(studentId).orElseThrow(() -> new NotFoundException("No photo has been uploaded for this student"));
    }

    // ------------------------------------------------------------------ cards

    @Transactional(readOnly = true)
    public IdCardSummary summary(Long studentId) {
        Student s = student(studentId);
        Optional<StudentPhoto> photo = photos.findByStudentId(studentId);
        List<CardView> history = cards.findByStudentIdOrderByIssuedAtDesc(studentId).stream().map(IdCardService::view).toList();
        CardView current = history.stream().filter(c -> c.status().equals("VALID")).findFirst().orElse(null);
        String blocker = s.getStatus() != Student.Status.ACTIVE
                ? "Cards are issued to active students; this student is " + s.getStatus().name().toLowerCase()
                : photo.isEmpty() ? "Upload the student's photo first" : null;
        return new IdCardSummary(photo.isPresent(), photo.map(p -> String.valueOf(p.getVersion())).orElse(null), blocker == null, blocker,
                current, history);
    }

    /** Issues a card, or a replacement (with a reason) when the student already has a valid one. */
    @Transactional
    public IdCardSummary issue(Long studentId, String reason) {
        Student s = student(studentId);
        Optional<IdCard> existing = cards.findByStudentIdAndStatus(studentId, IdCard.Status.VALID);
        if (existing.isPresent() && (reason == null || reason.isBlank())) {
            throw new BusinessRuleException("REASON_REQUIRED", "The student already has a valid card; give the reason for the replacement (lost, damaged…)");
        }
        IdCard card = issueFor(s, existing.orElse(null), reason);
        audit.record(existing.isPresent() ? "ID_CARD_REISSUED" : "ID_CARD_ISSUED", "Student", s.getId(),
                details(card, existing.isPresent() ? reason.trim() : null));
        return summary(studentId);
    }

    @Transactional
    public IdCardSummary revoke(Long studentId, Long cardId, String reason) {
        Student s = student(studentId);
        IdCard c = cards.findById(cardId).filter(x -> x.getStudent().getId().equals(studentId))
                .orElseThrow(() -> new NotFoundException("ID card", cardId));
        if (c.getStatus() != IdCard.Status.VALID) throw new BusinessRuleException("NOT_VALID", "This card is already " + c.getStatus().name().toLowerCase());
        end(c, IdCard.Status.REVOKED, reason.trim());
        audit.record("ID_CARD_REVOKED", "Student", s.getId(), details(c, reason.trim()));
        return summary(studentId);
    }

    /** Revokes the student's valid card when their studies end (called when a status change is applied). */
    @Transactional
    public void revokeAll(Student s, String reason) {
        cards.findByStudentIdAndStatus(s.getId(), IdCard.Status.VALID).ifPresent(c -> {
            end(c, IdCard.Status.REVOKED, reason);
            audit.record("ID_CARD_REVOKED", "Student", s.getId(), details(c, reason));
        });
    }

    /** The student's valid card as a printable CR80 PDF (front and back). */
    @Transactional
    public Download pdf(Long studentId) {
        Student s = student(studentId);
        IdCard c = cards.findByStudentIdAndStatus(studentId, IdCard.Status.VALID)
                .orElseThrow(() -> new BusinessRuleException("NO_VALID_CARD", "This student has no valid ID card to print"));
        audit.record("ID_CARD_PRINTED", "Student", s.getId(), details(c, null));
        return new Download("id-card-" + safe(c.getCardNo()) + ".pdf", IdCardRenderer.pdf(List.of(printable(c)), tenant()));
    }

    @Transactional(readOnly = true)
    public BatchPreview batchPreview(Long programmeId, Short yearOfStudy) {
        Batch b = batch(programmeId, yearOfStudy);
        return new BatchPreview(b.ready.size(), b.noPhoto.size(), b.carded,
                b.noPhoto.stream().limit(50).map(x -> x.getRegistrationNo() + " " + x.fullName()).toList());
    }

    /** Issues cards to every active student of the programme (and year) who has a photo and no valid card, and prints them together. */
    @Transactional
    public Download issueBatch(Long programmeId, Short yearOfStudy) {
        scope.requireProgramme(programmeId);
        Batch b = batch(programmeId, yearOfStudy);
        if (b.ready.isEmpty()) {
            throw new BusinessRuleException("NOTHING_TO_ISSUE", b.noPhoto.isEmpty()
                    ? "Every active student in this group already has a valid card"
                    : "No student in this group is ready: " + b.noPhoto.size() + " still need a photo");
        }
        if (b.ready.size() > MAX_BATCH) {
            throw new BusinessRuleException("BATCH_TOO_LARGE", "Issue at most " + MAX_BATCH + " cards at a time; choose a year of study");
        }
        List<IdCardRenderer.Card> printable = new ArrayList<>();
        for (Student s : b.ready) printable.add(printable(issueFor(s, null, null)));
        audit.record("ID_CARDS_ISSUED", "Programme", programmeId, Map.of("count", printable.size(),
                "yearOfStudy", yearOfStudy == null ? "all" : yearOfStudy.toString()));
        String code = b.ready.get(0).getProgramme().getCode();
        return new Download("id-cards-" + safe(code) + (yearOfStudy == null ? "" : "-year-" + yearOfStudy) + ".pdf",
                IdCardRenderer.pdf(printable, tenant()));
    }

    @Transactional(readOnly = true)
    public Optional<IdCardVerification> verify(String code) {
        return LetterCodes.normalise(code).flatMap(cards::findByVerificationCode).map(c -> {
            Student s = c.getStudent();
            Tenant t = tenants.findById(c.getTenantId()).orElseThrow();
            String status = c.getStatus() == IdCard.Status.REPLACED ? "SUPERSEDED"
                    : c.getStatus() == IdCard.Status.VALID && c.getValidUntil().isBefore(LocalDate.now(ZONE)) ? "EXPIRED"
                    : c.getStatus().name();
            return new IdCardVerification("STUDENT_ID_CARD", status, t.getName(), t.getNactvetRegNo(), c.getCardNo(), c.getIssuedAt(),
                    s.fullName(), s.getRegistrationNo(), s.getProgramme().getName(), c.getValidUntil(), s.getStatus().name());
        });
    }

    // ------------------------------------------------------------------ helpers

    private record Batch(List<Student> ready, List<Student> noPhoto, int carded) {
    }

    private Batch batch(Long programmeId, Short yearOfStudy) {
        scope.requireProgramme(programmeId);
        List<Student> active = students.findByProgrammeIdAndStatusInOrderBySurnameAscFirstNameAsc(programmeId, List.of(Student.Status.ACTIVE))
                .stream().filter(s -> yearOfStudy == null || s.getYearOfStudy() == yearOfStudy).toList();
        List<Long> ids = active.stream().map(Student::getId).toList();
        Set<Long> carded = ids.isEmpty() ? Set.of() : cards.studentsWithValidCard(ids);
        Set<Long> withPhoto = ids.isEmpty() ? Set.of() : photos.studentsWithPhoto(ids);
        List<Student> ready = new ArrayList<>(), noPhoto = new ArrayList<>();
        for (Student s : active) {
            if (carded.contains(s.getId())) continue;
            (withPhoto.contains(s.getId()) ? ready : noPhoto).add(s);
        }
        return new Batch(ready, noPhoto, carded.size());
    }

    private IdCard issueFor(Student s, IdCard replacing, String reason) {
        if (s.getStatus() != Student.Status.ACTIVE) {
            throw new BusinessRuleException("NOT_ACTIVE", "Cards are issued to active students; " + s.getRegistrationNo() + " is "
                    + s.getStatus().name().toLowerCase());
        }
        if (!photos.existsByStudentId(s.getId())) {
            throw new BusinessRuleException("PHOTO_REQUIRED", "Upload the student's photo before issuing a card");
        }
        if (replacing != null) {
            end(replacing, IdCard.Status.REPLACED, "Replaced: " + reason.trim());
            cards.flush();
        }
        int year = LocalDate.now(ZONE).getYear();
        IdCard c = new IdCard();
        c.setStudent(s);
        c.setCardNo(NumberSequenceService.format("ID" + year + "-", sequences.next("ID_CARD_" + year), 5));
        c.setVerificationCode(LetterCodes.newCode());
        c.setValidUntil(validUntil(s));
        c.setIssuedByName(CurrentUser.name());
        c.setIssuedAt(OffsetDateTime.now());
        return cards.saveAndFlush(c);
    }

    /**
     * The end of the student's expected final academic year: entry year end plus the programme's remaining years. If that has
     * passed (a student taking longer), the end of the current academic year, or a year from today when none is set.
     */
    LocalDate validUntil(Student s) {
        LocalDate today = LocalDate.now(ZONE);
        AcademicYear entry = s.getEntryAcademicYear();
        int years = Math.max(1, (s.getProgramme().getDurationSemesters() + 1) / 2);
        LocalDate expected = entry == null || entry.getEndDate() == null ? null : entry.getEndDate().plusYears(years - 1L);
        if (expected != null && expected.isAfter(today)) return expected;
        return years().stream().filter(AcademicYear::isCurrent).map(AcademicYear::getEndDate)
                .filter(d -> d != null && d.isAfter(today)).findFirst().orElse(today.plusYears(1));
    }

    private List<AcademicYear> years() {
        return years.findAllByOrderByStartDateDesc();
    }

    private IdCardRenderer.Card printable(IdCard c) {
        Student s = c.getStudent();
        StudentPhoto p = photos.findByStudentId(s.getId()).orElse(null);
        return new IdCardRenderer.Card(s.fullName(), s.getRegistrationNo(), s.getProgramme().getName(), s.getProgramme().getCode(),
                c.getCardNo(), c.getValidUntil(), c.getVerificationCode(), verificationUrl(c.getVerificationCode()),
                p == null ? null : p.getData(), p == null ? null : p.getContentType(), s.getPhone());
    }

    private void end(IdCard c, IdCard.Status status, String reason) {
        c.setStatus(status);
        c.setEndedReason(reason.length() > 300 ? reason.substring(0, 300) : reason);
        c.setEndedAt(OffsetDateTime.now());
    }

    private static Map<String, Object> details(IdCard c, String reason) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("cardNo", c.getCardNo());
        m.put("registrationNo", String.valueOf(c.getStudent().getRegistrationNo()));
        m.put("validUntil", c.getValidUntil().toString());
        if (reason != null) m.put("reason", reason);
        return m;
    }

    static CardView view(IdCard c) {
        return new CardView(c.getId(), c.getCardNo(), c.getVerificationCode(), c.getValidUntil(), c.getStatus().name(), c.getIssuedByName(),
                c.getIssuedAt(), c.getEndedReason(), c.getEndedAt());
    }

    private Student student(Long id) {
        Student s = students.findById(id).orElseThrow(() -> new NotFoundException("Student", id));
        scope.requireProgramme(s.getProgramme().getId());
        return s;
    }

    private Tenant tenant() {
        return tenants.findById(TenantContext.requireTenantId()).orElseThrow();
    }

    private String verificationUrl(String code) {
        Tenant t = tenant();
        String host = domains.findFirstByTenantIdOrderByIdAsc(t.getId()).map(d -> d.getHost()).orElse("localhost");
        return verificationUrlTemplate.replace("{host}", host).replace("{code}", code);
    }

    private static String safe(String s) {
        return s == null ? "card" : s.replaceAll("[^A-Za-z0-9]+", "-");
    }

    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && Arrays.equals(Arrays.copyOf(b, 8), new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})) return "image/png";
        return null;
    }
}
