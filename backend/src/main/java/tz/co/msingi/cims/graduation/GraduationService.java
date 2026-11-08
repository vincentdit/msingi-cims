package tz.co.msingi.cims.graduation;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.admission.letter.LetterCodes;
import tz.co.msingi.cims.platform.numbering.NumberSequenceService;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.graduation.GraduationDtos.CertificateRecord;
import tz.co.msingi.cims.graduation.GraduationDtos.CertificateVerification;
import tz.co.msingi.cims.graduation.GraduationDtos.GenerateResult;
import tz.co.msingi.cims.graduation.GraduationDtos.GraduandView;
import tz.co.msingi.cims.graduation.GraduationDtos.GraduationPermissions;
import tz.co.msingi.cims.graduation.GraduationDtos.GraduationRequest;
import tz.co.msingi.cims.graduation.GraduationDtos.GraduationSummary;
import tz.co.msingi.cims.graduation.GraduationDtos.GraduationView;
import tz.co.msingi.cims.graduation.GraduationDtos.IssueResult;
import tz.co.msingi.cims.graduation.GraduationDtos.NotReady;
import tz.co.msingi.cims.graduation.GraduationRepositories.ClearanceRepository;
import tz.co.msingi.cims.graduation.GraduationRepositories.GraduandRepository;
import tz.co.msingi.cims.graduation.GraduationRepositories.GraduationRepository;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantDomainRepository;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Graduations (FR-GRD-02/03, BP-15 steps 3-5): Registry generates the graduand list from finalists with the award and full clearance,
 * the Governing Board approves it (someone else than who submitted it), the NACTVET graduand file is produced and its submission
 * recorded, and award certificates are issued with a unique number and verification code, every print logged.
 */
@Service
@RequiredArgsConstructor
public class GraduationService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    static final Role[] REGISTRY = ClearanceService.REGISTRY;
    static final Role[] BOARD = {Role.INSTITUTION_ADMIN};
    private static final Set<Student.Status> CURRENT = EnumSet.of(Student.Status.ACTIVE, Student.Status.COMPLETED);

    private final GraduationRepository graduations;
    private final tz.co.msingi.cims.platform.branding.PrincipalService principal;
    private final GraduandRepository graduands;
    private final ClearanceRepository clearances;
    private final StudentRepository students;
    private final Awards awards;
    private final TenantRepository tenants;
    private final TenantDomainRepository domains;
    private final NumberSequenceService sequences;
    private final ExamAccess access;
    private final Messaging messaging;
    private final AuditService audit;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Value("${cims.documents.verification-url:https://{host}/verify/{code}}")
    private String verificationUrlTemplate;

    public record Download(String filename, byte[] content) {
    }

    // ------------------------------------------------------------------ graduations

    @Transactional(readOnly = true)
    public List<GraduationSummary> list() {
        access.require("see graduations", ClearanceService.VIEWERS);
        return graduations.findAllByOrderByCreatedAtDesc().stream().map(g -> {
            List<Graduand> gs = graduands.findByGraduationId(g.getId());
            return new GraduationSummary(g.getId(), g.getName(), g.getCeremonyDate(), g.getStatus().name(), gs.size(),
                    (int) gs.stream().filter(x -> x.getCertificateNo() != null).count(), g.getNactvetStatus() == null ? null : g.getNactvetStatus().name(),
                    g.getCreatedAt());
        }).toList();
    }

    @Transactional
    public GraduationView create(GraduationRequest req) {
        access.require("create graduations", REGISTRY);
        Graduation g = new Graduation();
        g.setName(req.name().trim());
        g.setCeremonyDate(req.ceremonyDate());
        g.setCreatedByName(CurrentUser.name());
        graduations.saveAndFlush(g);
        audit.record("GRADUATION_CREATED", "Graduation", g.getId(), Map.of("name", g.getName(), "ceremonyDate", String.valueOf(g.getCeremonyDate())));
        return view(g);
    }

    @Transactional
    public GraduationView update(Long id, GraduationRequest req) {
        access.require("change graduations", REGISTRY);
        Graduation g = require(id);
        if (g.getStatus() == Graduation.Status.APPROVED && !Objects.equals(g.getName(), req.name().trim())) {
            throw new BusinessRuleException("GRADUATION_APPROVED", "An approved graduation keeps its name; only the ceremony date can change");
        }
        g.setName(req.name().trim());
        g.setCeremonyDate(req.ceremonyDate());
        audit.record("GRADUATION_UPDATED", "Graduation", g.getId(), Map.of("name", g.getName(), "ceremonyDate", String.valueOf(g.getCeremonyDate())));
        return view(g);
    }

    @Transactional(readOnly = true)
    public GraduationView get(Long id) {
        access.require("see graduations", ClearanceService.VIEWERS);
        return view(require(id));
    }

    /**
     * Lists every finalist with the award and full clearance who is not on another graduation's list, and drops those who no longer
     * qualify. Finalists not yet ready are reported with the reason.
     */
    @Transactional
    public GenerateResult generate(Long id) {
        access.require("generate graduand lists", REGISTRY);
        Graduation g = require(id);
        requireDraft(g);
        Map<Long, Graduand> mine = graduands.findByGraduationId(g.getId()).stream().collect(Collectors.toMap(Graduand::getStudentId, Function.identity()));
        Map<Long, Clearance> clr = clearances.findAll().stream().collect(Collectors.toMap(Clearance::getStudentId, Function.identity()));
        int added = 0, removed = 0;
        List<NotReady> notReady = new ArrayList<>();
        Set<Long> keep = new java.util.HashSet<>();
        for (Student s : students.findAll()) {
            if (!CURRENT.contains(s.getStatus())) continue;
            Awards.Outcome o = awards.of(s);
            if (!o.finalist()) continue;
            Optional<Graduand> elsewhere = graduands.findByStudentId(s.getId()).filter(x -> !x.getGraduationId().equals(g.getId()));
            if (elsewhere.isPresent()) continue;
            Clearance c = clr.get(s.getId());
            String why = !o.qualified() ? o.reason() : c == null ? "Clearance not started" : c.getStatus() != Clearance.Status.CLEARED
                    ? "Clearance in progress (" + c.getItems().stream().filter(i -> i.getStatus() == ClearanceItem.Status.CLEARED).count() + " of "
                    + c.getItems().size() + " offices)" : null;
            if (why != null) {
                notReady.add(new NotReady(s.getId(), s.getRegistrationNo(), s.fullName(), s.getProgramme().getCode(), why));
                continue;
            }
            keep.add(s.getId());
            Graduand gr = mine.get(s.getId());
            if (gr == null) {
                gr = new Graduand();
                gr.setGraduationId(g.getId());
                gr.setStudentId(s.getId());
                added++;
            }
            gr.setProgrammeId(s.getProgramme().getId());
            gr.setAwardName(o.awardName());
            gr.setClassification(o.classification());
            gr.setCgpa(o.cgpa());
            gr.setCreditsEarned(o.creditsEarned());
            gr.setCompletedSemester(o.completedSemester());
            graduands.save(gr);
        }
        for (Graduand gr : mine.values()) {
            if (!keep.contains(gr.getStudentId())) {
                graduands.delete(gr);
                removed++;
            }
        }
        graduands.flush();
        g.setGeneratedByName(CurrentUser.name());
        g.setGeneratedAt(OffsetDateTime.now());
        int total = (int) graduands.countByGraduationId(g.getId());
        audit.record("GRADUAND_LIST_GENERATED", "Graduation", g.getId(), Map.of("added", added, "removed", removed, "total", total,
                "notReady", notReady.size()));
        return new GenerateResult(added, removed, total, notReady);
    }

    @Transactional
    public GraduationView submit(Long id) {
        access.require("submit graduand lists", REGISTRY);
        Graduation g = require(id);
        requireDraft(g);
        if (graduands.countByGraduationId(g.getId()) == 0) throw new BusinessRuleException("NO_GRADUANDS", "Generate the graduand list first");
        g.setStatus(Graduation.Status.SUBMITTED);
        g.setSubmittedById(access.me());
        g.setSubmittedByName(CurrentUser.name());
        g.setSubmittedAt(OffsetDateTime.now());
        g.setReturnedNote(null);
        g.setReturnedByName(null);
        audit.record("GRADUAND_LIST_SUBMITTED", "Graduation", g.getId(), Map.of("graduands", graduands.countByGraduationId(g.getId())));
        return view(g);
    }

    /** The Governing Board approves the list (BP-15 step 4): students become graduates and are told by SMS. */
    @Transactional
    public GraduationView approve(Long id, String minuteRef) {
        access.require("approve graduands for the Governing Board", BOARD);
        Graduation g = require(id);
        if (g.getStatus() != Graduation.Status.SUBMITTED) throw new BusinessRuleException("NOT_SUBMITTED", "The list has not been submitted to the Board");
        if (Objects.equals(g.getSubmittedById(), access.me())) {
            throw new BusinessRuleException("SEGREGATION_OF_DUTIES", "You submitted this list, so someone else records the Board's approval");
        }
        g.setStatus(Graduation.Status.APPROVED);
        g.setApprovedByName(CurrentUser.name());
        g.setApprovedAt(OffsetDateTime.now());
        g.setBoardMinuteRef(minuteRef.trim());
        List<Graduand> list = graduands.findByGraduationId(g.getId());
        Map<Long, Student> ss = students.findAllById(list.stream().map(Graduand::getStudentId).toList()).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        for (Graduand gr : list) {
            Student s = ss.get(gr.getStudentId());
            s.setStatus(Student.Status.GRADUATED);
            messaging.event("GRADUATION_APPROVED", s.getPhone(), Map.of("award", gr.getAwardName(), "graduation", g.getName(),
                    "date", g.getCeremonyDate() == null ? "to be announced" : g.getCeremonyDate().format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH))));
        }
        audit.record("GRADUANDS_APPROVED", "Graduation", g.getId(), Map.of("minute", g.getBoardMinuteRef(), "graduands", list.size()));
        return view(g);
    }

    @Transactional
    public GraduationView returnToRegistry(Long id, String note) {
        access.require("return graduand lists", BOARD);
        Graduation g = require(id);
        if (g.getStatus() != Graduation.Status.SUBMITTED) throw new BusinessRuleException("NOT_SUBMITTED", "The list has not been submitted to the Board");
        g.setStatus(Graduation.Status.DRAFT);
        g.setReturnedNote(note.trim());
        g.setReturnedByName(CurrentUser.name());
        audit.record("GRADUAND_LIST_RETURNED", "Graduation", g.getId(), Map.of("note", g.getReturnedNote()));
        return view(g);
    }

    // ------------------------------------------------------------------ NACTVET (FR-GRD-02)

    /** The graduand file for NACTVET: one row per graduand. */
    @Transactional
    public Download nactvetFile(Long id) {
        access.require("export the NACTVET graduand file", REGISTRY);
        Graduation g = require(id);
        if (g.getStatus() != Graduation.Status.APPROVED) throw new BusinessRuleException("NOT_APPROVED", "The Governing Board has not approved this list yet");
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Graduands");
            String[] head = {"Institution reg. no.", "Registration no.", "NACTVET verification no. (AVN)", "Surname", "First name", "Middle name", "Sex",
                    "Date of birth", "Programme code (NACTVET)", "Programme", "Award", "Classification", "GPA", "Credits", "Completed", "Board minute",
                    "Certificate no."};
            Row r0 = sh.createRow(0);
            for (int i = 0; i < head.length; i++) r0.createCell(i).setCellValue(head[i]);
            int r = 1;
            for (GraduandView v : graduandViews(g)) {
                Student s = students.findById(v.studentId()).orElseThrow();
                Row row = sh.createRow(r++);
                Object[] vals = {t.getNactvetRegNo(), s.getRegistrationNo(), s.getNactvetVerificationNo(), s.getSurname(), s.getFirstName(), s.getMiddleName(),
                        s.getSex(), s.getDateOfBirth() == null ? null : s.getDateOfBirth().toString(), s.getProgramme().getNactvetCode(), s.getProgramme().getName(),
                        v.awardName(), v.classification(), v.cgpa() == null ? null : v.cgpa().toPlainString(),
                        v.creditsEarned() == null ? null : v.creditsEarned().stripTrailingZeros().toPlainString(), v.completedSemester(), g.getBoardMinuteRef(),
                        v.certificateNo()};
                for (int i = 0; i < vals.length; i++) row.createCell(i).setCellValue(vals[i] == null ? "" : String.valueOf(vals[i]));
            }
            for (int i = 0; i < head.length; i++) sh.setColumnWidth(i, (i == 9 || i == 10 ? 40 : 18) * 256);
            wb.write(out);
            audit.record("NACTVET_GRADUAND_FILE_EXPORTED", "Graduation", g.getId(), Map.of("graduands", r - 1));
            return new Download("nactvet-graduands-" + g.getId() + ".xlsx", out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public GraduationView nactvet(Long id, Graduation.NactvetStatus status, String reference, String note) {
        access.require("record the NACTVET graduand submission", REGISTRY);
        Graduation g = require(id);
        if (g.getStatus() != Graduation.Status.APPROVED) throw new BusinessRuleException("NOT_APPROVED", "The Governing Board has not approved this list yet");
        if (status != Graduation.NactvetStatus.SUBMITTED && g.getNactvetStatus() == null) {
            throw new BusinessRuleException("NOT_SUBMITTED", "Record the submission to NACTVET first");
        }
        g.setNactvetStatus(status);
        if (reference != null && !reference.isBlank()) g.setNactvetReference(reference.trim());
        g.setNactvetNote(note == null || note.isBlank() ? null : note.trim());
        if (status == Graduation.NactvetStatus.SUBMITTED) g.setNactvetSubmittedAt(OffsetDateTime.now());
        audit.record("NACTVET_GRADUANDS_" + status.name(), "Graduation", g.getId(), Map.of("reference", String.valueOf(g.getNactvetReference())));
        return view(g);
    }

    // ------------------------------------------------------------------ certificates (FR-GRD-03)

    /** Gives every graduand of an approved list a certificate number and verification code (once). */
    @Transactional
    public IssueResult issueCertificates(Long id) {
        access.require("issue certificates", REGISTRY);
        Graduation g = require(id);
        if (g.getStatus() != Graduation.Status.APPROVED) throw new BusinessRuleException("NOT_APPROVED", "Certificates are issued once the Governing Board has approved the list");
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        int issued = 0, already = 0;
        List<Graduand> list = graduands.findByGraduationId(g.getId()).stream().sorted(Comparator.comparing(x -> students.findById(x.getStudentId())
                .map(Student::getRegistrationNo).orElse(""))).toList();
        for (Graduand gr : list) {
            if (gr.getCertificateNo() != null) {
                already++;
                continue;
            }
            int year = Year.now(ZONE).getValue();
            gr.setCertificateNo(NumberSequenceService.format(t.getCode() + "/CERT/" + year + "/", sequences.next("CERTIFICATE-" + year), 5));
            gr.setVerificationCode(LetterCodes.newCode());
            gr.setCertificateIssuedAt(OffsetDateTime.now());
            gr.setCertificateIssuedBy(CurrentUser.name());
            gr.setSignatoryId(principal.currentId());
            issued++;
        }
        audit.record("CERTIFICATES_ISSUED", "Graduation", g.getId(), Map.of("issued", issued, "alreadyIssued", already));
        return new IssueResult(issued, already);
    }

    /** One certificate, or every issued certificate of the graduation, as a PDF; each print is counted and logged (a reprint needs a reason). */
    @Transactional
    public Download print(Long graduationId, Long graduandId, String reason) {
        access.require("print certificates", REGISTRY);
        Graduation g = require(graduationId);
        List<Graduand> list = graduands.findByGraduationId(g.getId()).stream()
                .filter(x -> x.getCertificateNo() != null && !"REVOKED".equals(x.getCertificateStatus()) && (graduandId == null || x.getId().equals(graduandId)))
                .sorted(Comparator.comparing(Graduand::getCertificateNo)).toList();
        if (list.isEmpty()) throw new BusinessRuleException("NO_CERTIFICATES", "Issue the certificates first");
        boolean reprint = list.stream().anyMatch(x -> x.getPrintCount() > 0);
        if (reprint && (reason == null || reason.isBlank())) {
            throw new BusinessRuleException("REPRINT_REASON_REQUIRED", "Some of these certificates have been printed before. Give the reason for the reprint.");
        }
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        List<CertificateRenderer.Certificate> certs = new ArrayList<>();
        for (Graduand gr : list) {
            Student s = students.findById(gr.getStudentId()).orElseThrow();
            certs.add(new CertificateRenderer.Certificate(t, s.fullName(), s.getRegistrationNo(), gr.getAwardName(), s.getProgramme().getName(),
                    gr.getClassification(), gr.getCompletedSemester(), g.getCeremonyDate() != null ? g.getCeremonyDate() : LocalDate.now(ZONE),
                    gr.getCertificateNo(), gr.getVerificationCode(), verificationUrl(t, gr.getVerificationCode()), gr.getPrintCount() > 0,
                    principal.marksFor(gr.getSignatoryId())));
            audit.record(gr.getPrintCount() > 0 ? "CERTIFICATE_REPRINTED" : "CERTIFICATE_PRINTED", "Graduand", gr.getId(),
                    Map.of("certificateNo", gr.getCertificateNo(), "student", s.getRegistrationNo(), "reason", String.valueOf(reason)));
            gr.setPrintCount(gr.getPrintCount() + 1);
            gr.setLastPrintedAt(OffsetDateTime.now());
        }
        String name = list.size() == 1 ? "certificate-" + list.get(0).getCertificateNo().replaceAll("[^A-Za-z0-9]+", "-") : "certificates-graduation-" + g.getId();
        return new Download(name + ".pdf", CertificateRenderer.pdf(certs));
    }

    // ------------------------------------------------------------------ public verification (FR-GRD-04, BP-16)

    @Transactional(readOnly = true)
    public Optional<CertificateVerification> verify(String code) {
        Optional<String> c = LetterCodes.normalise(code);
        if (c.isEmpty()) return Optional.empty();
        Optional<CertificateVerification> current = graduands.findByVerificationCode(c.get()).map(gr -> verification(gr,
                "REVOKED".equals(gr.getCertificateStatus()) ? "REVOKED" : "VALID", gr.getCertificateNo(), gr.getCertificateIssuedAt()));
        if (current.isPresent()) return current;
        // an earlier certificate: replaced by a newer one (SUPERSEDED) or revoked
        return jdbc.query("select graduand_id, certificate_no, issued_at, status from certificate_history where verification_code = ?", (rs, i) -> {
            Graduand gr = graduands.findById(rs.getLong(1)).orElseThrow();
            return verification(gr, "REVOKED".equals(rs.getString(4)) ? "REVOKED" : "SUPERSEDED", rs.getString(2), rs.getObject(3, OffsetDateTime.class));
        }, c.get()).stream().findFirst();
    }

    private CertificateVerification verification(Graduand gr, String status, String certificateNo, OffsetDateTime issuedAt) {
        Student s = students.findById(gr.getStudentId()).orElseThrow();
        Graduation g = graduations.findById(gr.getGraduationId()).orElseThrow();
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        return new CertificateVerification("AWARD_CERTIFICATE", status, t.getName(), t.getNactvetRegNo(), certificateNo, 1,
                issuedAt, s.fullName(), s.getRegistrationNo(), s.getProgramme().getName(), gr.getAwardName(), gr.getClassification(),
                g.getName(), g.getCeremonyDate());
    }

    // ------------------------------------------------------------------ revocation and replacement (FR-GRD-04)

    /** Withdraws a certificate (the award withdrawn, or issued in error): its code answers "revoked" from now on. Principal-level. */
    @Transactional
    public GraduandView revokeCertificate(Long graduationId, Long graduandId, String reason) {
        access.require("revoke an award certificate", BOARD);
        Graduand gr = issuedGraduand(graduationId, graduandId);
        if ("REVOKED".equals(gr.getCertificateStatus())) throw new BusinessRuleException("ALREADY_REVOKED", "This certificate is already revoked");
        gr.setCertificateStatus("REVOKED");
        gr.setCertificateRevokedAt(OffsetDateTime.now());
        gr.setCertificateRevokedBy(CurrentUser.name());
        gr.setCertificateRevokeReason(reason.trim());
        audit.record("CERTIFICATE_REVOKED", "Graduand", gr.getId(), Map.of("certificateNo", gr.getCertificateNo(), "reason", reason.trim()));
        return graduandView(gr);
    }

    /**
     * A replacement for a lost or damaged certificate: a new number (the old one with /R1, /R2...) and a new verification code; the old
     * code keeps answering "replaced by a newer certificate", so a lost certificate cannot pass as current. Printed as an original.
     */
    @Transactional
    public GraduandView replaceCertificate(Long graduationId, Long graduandId, String reason) {
        access.require("replace an award certificate", REGISTRY);
        Graduand gr = issuedGraduand(graduationId, graduandId);
        if ("REVOKED".equals(gr.getCertificateStatus())) throw new BusinessRuleException("CERTIFICATE_REVOKED", "A revoked certificate cannot be replaced");
        jdbc.update("""
                insert into certificate_history (tenant_id, graduand_id, certificate_no, verification_code, issued_at, status, reason, closed_by)
                values (?, ?, ?, ?, ?, 'REPLACED', ?, ?)""", TenantContext.requireTenantId(), gr.getId(), gr.getCertificateNo(), gr.getVerificationCode(),
                gr.getCertificateIssuedAt(), reason.trim(), CurrentUser.name());
        String old = gr.getCertificateNo();
        int n = gr.getReplacementCount() + 1;
        gr.setReplacementCount(n);
        gr.setCertificateNo(old.replaceFirst("/R\\d+$", "") + "/R" + n);
        gr.setVerificationCode(LetterCodes.newCode());
        gr.setCertificateIssuedAt(OffsetDateTime.now());
        gr.setCertificateIssuedBy(CurrentUser.name());
        gr.setSignatoryId(principal.currentId());
        gr.setPrintCount(0);
        gr.setLastPrintedAt(null);
        audit.record("CERTIFICATE_REPLACED", "Graduand", gr.getId(), Map.of("old", old, "certificateNo", gr.getCertificateNo(), "reason", reason.trim()));
        return graduandView(gr);
    }

    /** The graduand's certificates, current first, with how often each code was checked on the verification page. */
    @Transactional(readOnly = true)
    public List<CertificateRecord> certificateRecords(Long graduationId, Long graduandId) {
        access.require("view certificate records", REGISTRY);
        Graduand gr = issuedGraduand(graduationId, graduandId);
        List<CertificateRecord> out = new ArrayList<>();
        long[] cur = checks(gr.getVerificationCode());
        out.add(new CertificateRecord(gr.getCertificateNo(), gr.getVerificationCode(), gr.getCertificateStatus(), gr.getCertificateIssuedAt(),
                gr.getCertificateRevokedAt(), gr.getCertificateRevokedBy(), gr.getCertificateRevokeReason(), cur[0], cur[0] == 0 ? null : lastCheck(gr.getVerificationCode())));
        out.addAll(jdbc.query("""
                select certificate_no, verification_code, status, issued_at, closed_at, closed_by, reason from certificate_history
                where graduand_id = ? order by closed_at desc""", (rs, i) -> {
            long[] k = checks(rs.getString(2));
            return new CertificateRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class),
                    rs.getObject(5, OffsetDateTime.class), rs.getString(6), rs.getString(7), k[0], k[0] == 0 ? null : lastCheck(rs.getString(2)));
        }, gr.getId()));
        return out;
    }

    private long[] checks(String code) {
        Long n = jdbc.queryForObject("select count(*) from document_verifications where code = ?", Long.class, code);
        return new long[]{n == null ? 0 : n};
    }

    private OffsetDateTime lastCheck(String code) {
        return jdbc.queryForObject("select max(verified_at) from document_verifications where code = ?", OffsetDateTime.class, code);
    }

    private Graduand issuedGraduand(Long graduationId, Long graduandId) {
        Graduand gr = graduands.findById(graduandId).filter(x -> x.getGraduationId().equals(graduationId))
                .orElseThrow(() -> new NotFoundException("Graduand", graduandId));
        if (gr.getCertificateNo() == null) throw new BusinessRuleException("NO_CERTIFICATE", "No certificate has been issued to this graduand yet");
        return gr;
    }

    private GraduandView graduandView(Graduand gr) {
        Graduation g = require(gr.getGraduationId());
        return graduandViews(g).stream().filter(v -> v.id().equals(gr.getId())).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ helpers

    private Graduation require(Long id) {
        return graduations.findById(id).orElseThrow(() -> new NotFoundException("Graduation", id));
    }

    private static void requireDraft(Graduation g) {
        if (g.getStatus() != Graduation.Status.DRAFT) {
            throw new BusinessRuleException("LIST_SUBMITTED", "The graduand list has gone to the Governing Board and can no longer change");
        }
    }

    private GraduationView view(Graduation g) {
        boolean registry = access.has(REGISTRY);
        boolean draft = g.getStatus() == Graduation.Status.DRAFT;
        boolean approved = g.getStatus() == Graduation.Status.APPROVED;
        List<GraduandView> gv = graduandViews(g);
        GraduationPermissions can = new GraduationPermissions(registry, registry && draft, registry && draft && !gv.isEmpty(),
                access.has(BOARD) && g.getStatus() == Graduation.Status.SUBMITTED && !Objects.equals(g.getSubmittedById(), access.me()),
                registry && approved, registry && approved);
        return new GraduationView(g.getId(), g.getName(), g.getCeremonyDate(), g.getStatus().name(), g.getCreatedByName(), g.getGeneratedByName(),
                g.getGeneratedAt(), g.getSubmittedByName(), g.getSubmittedAt(), g.getApprovedByName(), g.getApprovedAt(), g.getBoardMinuteRef(),
                g.getReturnedNote(), g.getReturnedByName(), g.getNactvetStatus() == null ? null : g.getNactvetStatus().name(), g.getNactvetReference(),
                g.getNactvetNote(), g.getNactvetSubmittedAt(), gv, List.of(), can);
    }

    private List<GraduandView> graduandViews(Graduation g) {
        List<Graduand> list = graduands.findByGraduationId(g.getId());
        Map<Long, Student> ss = new HashMap<>();
        students.findAllById(list.stream().map(Graduand::getStudentId).toList()).forEach(s -> ss.put(s.getId(), s));
        Map<String, Long> verified = new HashMap<>();
        jdbc.query("""
                select v.code, count(*) from document_verifications v join graduands gr on gr.verification_code = v.code
                where gr.graduation_id = ? group by v.code""", rs -> { verified.put(rs.getString(1), rs.getLong(2)); }, g.getId());
        return list.stream().map(gr -> {
            Student s = ss.get(gr.getStudentId());
            return new GraduandView(gr.getId(), s.getId(), s.getRegistrationNo(), s.fullName(), s.getSex(), s.getProgramme().getCode(), s.getProgramme().getName(),
                    gr.getAwardName(), gr.getClassification(), gr.getCgpa(), gr.getCreditsEarned(), gr.getCompletedSemester(), gr.getCertificateNo(),
                    gr.getVerificationCode(), gr.getCertificateIssuedAt(), gr.getPrintCount(), gr.getLastPrintedAt(), gr.getCertificateStatus(),
                    gr.getReplacementCount(), gr.getVerificationCode() == null ? 0 : verified.getOrDefault(gr.getVerificationCode(), 0L));
        }).sorted(Comparator.comparing(GraduandView::programmeCode).thenComparing(GraduandView::registrationNo)).toList();
    }

    private String verificationUrl(Tenant tenant, String code) {
        String host = domains.findFirstByTenantIdOrderByIdAsc(tenant.getId()).map(x -> x.getHost()).orElse("localhost");
        return verificationUrlTemplate.replace("{host}", host).replace("{code}", code);
    }

}
