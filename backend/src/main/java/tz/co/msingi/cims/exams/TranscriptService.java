package tz.co.msingi.cims.exams;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.letter.LetterCodes;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamDtos.DocumentView;
import tz.co.msingi.cims.exams.ExamDtos.TranscriptVerification;
import tz.co.msingi.cims.exams.ExamRepositories.AcademicDocumentRepository;
import tz.co.msingi.cims.exams.ExamRepositories.SemesterResultRepository;
import tz.co.msingi.cims.finance.accounts.AccountDtos.Clearance;
import tz.co.msingi.cims.finance.accounts.AccountService;
import tz.co.msingi.cims.finance.fees.ClearanceGate;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.numbering.NumberSequenceService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantDomainRepository;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Statements of results / provisional transcripts (FR-EXM-13): every published semester of a student (after resits where those are
 * published), with GPA, CGPA and classification so far. Issued as a frozen snapshot with a verification code (BP-16); asking again
 * while nothing has changed returns the same document, and a changed record supersedes the earlier one.
 */
@Service
@RequiredArgsConstructor
public class TranscriptService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    static final Role[] ISSUERS = {Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER, Role.REGISTRAR};

    private final AcademicDocumentRepository documents;
    private final SemesterResultRepository semesterResults;
    private final ResultSheetService sheets;
    private final StudentRepository students;
    private final AccountService accounts;
    private final TenantRepository tenants;
    private final TenantDomainRepository domains;
    private final NumberSequenceService sequences;
    private final ExamAccess access;
    private final AuditService audit;
    private final ObjectMapper json;

    @Value("${cims.documents.verification-url:https://{host}/verify/{code}}")
    private String verificationUrlTemplate;

    public record Download(String filename, byte[] content) {
    }

    // ------------------------------------------------------------------ staff

    @Transactional(readOnly = true)
    public List<DocumentView> list(Long studentId) {
        access.require("view transcripts", ISSUERS);
        return documents.findByStudentIdOrderByIssuedAtDesc(studentId).stream().map(TranscriptService::view).toList();
    }

    @Transactional
    public Download issueForStaff(Long studentId) {
        access.require("issue a transcript", ISSUERS);
        Student st = students.findById(studentId).orElseThrow(() -> new NotFoundException("Student", studentId));
        AcademicDocument d = issue(st);
        audit.record("TRANSCRIPT_DOWNLOADED", "AcademicDocument", d.getId(), Map.of("documentNo", d.getDocumentNo(), "student", st.getRegistrationNo()));
        return download(d);
    }

    @Transactional
    public Download pdf(Long documentId) {
        access.require("download a transcript", ISSUERS);
        AcademicDocument d = documents.findById(documentId).orElseThrow(() -> new NotFoundException("Document", documentId));
        audit.record("TRANSCRIPT_DOWNLOADED", "AcademicDocument", d.getId(), Map.of("documentNo", d.getDocumentNo()));
        return download(d);
    }

    @Transactional
    public DocumentView revoke(Long documentId, String reason) {
        access.require("revoke a transcript", Role.INSTITUTION_ADMIN, Role.EXAMINATIONS_OFFICER);
        AcademicDocument d = documents.findById(documentId).orElseThrow(() -> new NotFoundException("Document", documentId));
        if (d.getStatus() == AcademicDocument.Status.REVOKED) throw new BusinessRuleException("ALREADY_REVOKED", "This document is already revoked");
        d.setStatus(AcademicDocument.Status.REVOKED);
        audit.record("TRANSCRIPT_REVOKED", "AcademicDocument", d.getId(), Map.of("documentNo", d.getDocumentNo(), "reason", reason.trim()));
        return view(d);
    }

    // ------------------------------------------------------------------ student portal

    @Transactional
    public Download issueForStudent() {
        Student st = students.findByUserId(CurrentUser.requireId()).orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
        Clearance c = accounts.evaluate(st, ClearanceGate.Gate.RESULTS, st.getEntryAcademicYear());
        if (c.enabled() && !c.cleared()) throw new BusinessRuleException("RESULTS_WITHHELD", "Your results are withheld: " + c.reason());
        AcademicDocument d = issue(st);
        audit.record("TRANSCRIPT_DOWNLOADED", "AcademicDocument", d.getId(), Map.of("documentNo", d.getDocumentNo(), "by", "student"));
        return download(d);
    }

    // ------------------------------------------------------------------ issuing

    /** The current valid statement if the record has not changed since, otherwise a new one superseding it. */
    AcademicDocument issue(Student st) {
        TranscriptContent draft = build(st, null, null, null, null); // compared without number, code and date
        if (draft.semesters().isEmpty()) throw new BusinessRuleException("NO_RESULTS", "No results have been published for " + st.fullName() + " yet");
        String sha = sha256(draft);
        Optional<AcademicDocument> current = documents.findFirstByStudentIdAndKindAndStatus(st.getId(), AcademicDocument.Kind.TRANSCRIPT,
                AcademicDocument.Status.VALID);
        if (current.isPresent() && current.get().getContentSha256().equals(sha)) return current.get();
        current.ifPresent(d -> d.setStatus(AcademicDocument.Status.SUPERSEDED));
        documents.flush();
        Tenant tenant = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        String no = NumberSequenceService.format(tenant.getCode() + "/TR/" + Year.now(ZONE).getValue() + "/",
                sequences.next("TRANSCRIPT-" + Year.now(ZONE).getValue()), 6);
        String code = LetterCodes.newCode();
        AcademicDocument d = new AcademicDocument();
        d.setStudentId(st.getId());
        d.setDocumentNo(no);
        d.setVerificationCode(code);
        d.setContent(build(st, no, code, verificationUrl(tenant, code), LocalDate.now(ZONE)));
        d.setContentSha256(sha);
        d.setIssuedAt(OffsetDateTime.now());
        d.setIssuedByName(CurrentUser.id().isPresent() ? CurrentUser.name() : "System");
        documents.saveAndFlush(d);
        audit.record("TRANSCRIPT_ISSUED", "AcademicDocument", d.getId(), Map.of("documentNo", no, "student", st.getRegistrationNo(),
                "semesters", d.getContent().semesters().size()));
        return d;
    }

    /** The student's published results per semester (the RESIT sheet where published, else the MAIN sheet). */
    TranscriptContent build(Student st, String documentNo, String code, String url, LocalDate issuedOn) {
        Map<Long, SemesterResult> bySemester = new HashMap<>();
        for (SemesterResult r : semesterResults.findByStudentIdOrderByIdAsc(st.getId())) {
            if (r.getSheet().getStatus() != ResultSheet.Status.PUBLISHED) continue;
            Long sem = r.getSheet().getSemester().getId();
            SemesterResult have = bySemester.get(sem);
            if (have == null || r.getSheet().getRound() == ResultSheet.Round.RESIT) bySemester.put(sem, r);
        }
        List<SemesterResult> ordered = bySemester.values().stream()
                .sorted(Comparator.comparing((SemesterResult r) -> r.getSheet().getSemester().getStartDate())).toList();
        List<TranscriptContent.Semester> blocks = new ArrayList<>();
        BigDecimal earned = BigDecimal.ZERO;
        for (SemesterResult r : ordered) {
            ResultSheet s = r.getSheet();
            List<TranscriptContent.Module> mods = sheets.studentLines(s, st.getId()).stream()
                    .map(l -> new TranscriptContent.Module(l.registration().getModule().getCode(), l.registration().getModule().getTitle(),
                            l.registration().getCredits(), l.outcome().grade(), l.outcome().points(), l.outcome().remark().name(), l.attempt()))
                    .sorted(Comparator.comparing(TranscriptContent.Module::code)).toList();
            blocks.add(new TranscriptContent.Semester(s.getSemester().getName(), s.getSemester().getAcademicYear().getCode(), s.getYearOfStudy(),
                    s.getRound() == ResultSheet.Round.RESIT, mods, r.getCreditsRegistered(), r.getCreditsPassed(), scaled(r.getGpa(), s),
                    ResultSheetService.decisionText(r.getDecision())));
            earned = earned.add(r.getCreditsPassed());
        }
        SemesterResult last = ordered.isEmpty() ? null : ordered.get(ordered.size() - 1);
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        Map<String, Object> branding = t.getBranding() == null ? Map.of() : t.getBranding();
        String key = last == null ? null : "Grading (" + last.getSheet().getRegulationSet().getName() + "): "
                + last.getSheet().getRegulationSet().getGradeBands().stream()
                .map(b -> b.getGrade() + " " + b.getMinMark() + "–" + b.getMaxMark() + " (" + b.getPoints().stripTrailingZeros().toPlainString() + ")")
                .collect(Collectors.joining(" · ")) + ". GPA = sum of grade points × credits ÷ credits.";
        return new TranscriptContent(t.getName(), t.getShortName(), t.getNactvetRegNo(),
                Stream.of(t.getContactPhone(), t.getContactEmail()).filter(x -> x != null && !x.isBlank()).collect(Collectors.joining(" · ")),
                String.valueOf(branding.getOrDefault("primaryColor", "")), documentNo, issuedOn, st.fullName(), st.getRegistrationNo(),
                st.getSex(), st.getDateOfBirth(), st.getNactvetVerificationNo(), st.getProgramme().getName(), st.getProgramme().getAwardName(),
                st.getProgramme().getNactvetCode(), st.getEntryAcademicYear() == null ? null : st.getEntryAcademicYear().getCode(), blocks, earned,
                last == null ? null : scaled(last.getCgpa(), last.getSheet()), last == null ? null : last.getClassification(), key, code, url);
    }

    /** A GPA with the number of decimals of the regulations it was computed under (stored with two). */
    private static BigDecimal scaled(BigDecimal gpa, ResultSheet s) {
        return gpa == null ? null : gpa.setScale(s.getRegulationSet().getGpaDecimals(), java.math.RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ public verification (BP-16)

    @Transactional(readOnly = true)
    public Optional<TranscriptVerification> verify(String code) {
        return LetterCodes.normalise(code).flatMap(documents::findByVerificationCode).map(d -> {
            TranscriptContent c = d.getContent();
            String lastSemester = c.semesters().isEmpty() ? null : c.semesters().get(c.semesters().size() - 1).name() + " "
                    + c.semesters().get(c.semesters().size() - 1).academicYear();
            return new TranscriptVerification("STATEMENT_OF_RESULTS", d.getStatus().name(), c.institutionName(), c.institutionNactvetNo(),
                    d.getDocumentNo(), 1, d.getIssuedAt(), c.studentName(), c.registrationNo(), c.programmeName(), c.awardName(),
                    c.nactvetVerificationNo(), c.cgpa(), c.classification(), c.creditsEarned(), lastSemester);
        });
    }

    // ------------------------------------------------------------------ helpers

    private Download download(AcademicDocument d) {
        return new Download("statement-of-results-" + d.getDocumentNo().replaceAll("[^A-Za-z0-9]+", "-") + ".pdf", TranscriptRenderer.pdf(d.getContent()));
    }

    static DocumentView view(AcademicDocument d) {
        return new DocumentView(d.getId(), d.getKind().name(), d.getDocumentNo(), d.getVerificationCode(), d.getStatus().name(), d.getIssuedAt(),
                d.getIssuedByName(), d.getContent().cgpa(), d.getContent().semesters().size());
    }

    private String verificationUrl(Tenant tenant, String code) {
        String host = domains.findFirstByTenantIdOrderByIdAsc(tenant.getId()).map(x -> x.getHost()).orElse("localhost");
        return verificationUrlTemplate.replace("{host}", host).replace("{code}", code);
    }

    private String sha256(TranscriptContent c) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(c).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
