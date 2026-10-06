package tz.co.msingi.cims.admission.verification;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.AdmissionWindowRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicantQualificationRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicationRepository;
import tz.co.msingi.cims.admission.domain.AdmissionWindow;
import tz.co.msingi.cims.admission.domain.Applicant;
import tz.co.msingi.cims.admission.domain.ApplicantQualification;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.domain.Application.Status;
import tz.co.msingi.cims.admission.letter.AdmissionLetter;
import tz.co.msingi.cims.admission.letter.AdmissionLetterRepository;
import tz.co.msingi.cims.admission.offer.OfferService;
import tz.co.msingi.cims.admission.verification.VerificationDtos.ApplicantRow;
import tz.co.msingi.cims.admission.verification.VerificationDtos.OutcomeResult;
import tz.co.msingi.cims.admission.verification.VerificationDtos.Overview;
import tz.co.msingi.cims.admission.verification.VerificationDtos.ReadyRow;
import tz.co.msingi.cims.admission.verification.VerificationDtos.SubmissionView;
import tz.co.msingi.cims.admission.verification.VerificationRepositories.NactvetSubmissionItemRepository;
import tz.co.msingi.cims.admission.verification.VerificationRepositories.NactvetSubmissionRepository;
import tz.co.msingi.cims.common.Csv;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.integration.nactvet.NactvetGateway;
import tz.co.msingi.cims.integration.nactvet.NactvetGateway.Candidate;
import tz.co.msingi.cims.integration.nactvet.NactvetGateway.Outcome;
import tz.co.msingi.cims.notification.SmsSender;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * NACTVET admission verification (BP-04 steps 1–3, FR-ADM-12): pre-validate the selected applicants,
 * submit them by API or file, record NACTVET's outcome per applicant, and resolve queries for resubmission.
 * Verified applicants receive an offer (see {@link OfferService}).
 */
@Service
@RequiredArgsConstructor
public class VerificationService {

    /** Statuses shown on the verification screen, in lifecycle order. */
    static final List<Status> PIPELINE = List.of(Status.SELECTED, Status.PENDING_VERIFICATION, Status.QUERIED,
            Status.NACTVET_REJECTED, Status.OFFERED, Status.ACCEPTED, Status.DECLINED, Status.OFFER_LAPSED);

    private static final String OUTCOME_HEADER = "reference,index_number,outcome,reason,verification_no,multiple_admission";

    private final NactvetSubmissionRepository submissions;
    private final NactvetSubmissionItemRepository items;
    private final ApplicationRepository applications;
    private final ApplicantQualificationRepository qualifications;
    private final AdmissionWindowRepository windows;
    private final TenantRepository tenants;
    private final NactvetGateway nactvet;
    private final OfferService offers;
    private final AdmissionLetterRepository letters;
    private final SmsSender sms;
    private final AuditService audit;

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Overview overview(Long windowId) {
        AdmissionWindow window = requireWindow(windowId);
        List<Application> apps = applications.findByWindowIdAndStatusInOrderByReferenceNoAsc(windowId, PIPELINE);
        Map<Long, String> index = indexNumbers(apps);
        Tenant tenant = currentTenant();

        Map<String, Long> counts = new LinkedHashMap<>();
        PIPELINE.forEach(s -> counts.put(s.name(), 0L));
        apps.forEach(a -> counts.merge(a.getStatus().name(), 1L, Long::sum));
        long accepted = counts.get("ACCEPTED");
        long responded = accepted + counts.get("DECLINED") + counts.get("OFFER_LAPSED");
        Double acceptanceRate = responded == 0 ? null : Math.round(1000.0 * accepted / responded) / 10.0;

        List<ReadyRow> ready = apps.stream().filter(a -> a.getStatus() == Status.SELECTED)
                .map(a -> new ReadyRow(a.getId(), a.getReferenceNo(), a.getApplicant().fullName(), programmeName(a),
                        index.get(a.getApplicant().getId()), a.getVerificationRemarks(), issues(a, index, tenant)))
                .toList();
        OffsetDateTime now = OffsetDateTime.now();
        Map<Long, AdmissionLetter> validLetters = letters.findByApplicationIdInAndStatus(
                apps.stream().filter(a -> a.getStatus() == Status.ACCEPTED).map(Application::getId).toList(), AdmissionLetter.Status.VALID)
                .stream().collect(Collectors.toMap(l -> l.getApplication().getId(), Function.identity()));
        List<ApplicantRow> rows = apps.stream().filter(a -> a.getStatus() != Status.SELECTED)
                .map(a -> row(a, index.get(a.getApplicant().getId()), now, validLetters.get(a.getId()))).toList();
        long lettersPending = apps.stream().filter(a -> a.getStatus() == Status.ACCEPTED && !validLetters.containsKey(a.getId())).count();
        return new Overview(windowId, window.getOfferAcceptanceDays(), tenant.getNactvetRegNo(), counts, acceptanceRate,
                window.getJoiningInstructions() != null, lettersPending, ready, submissionViews(windowId), rows);
    }

    // ------------------------------------------------------------------ commands

    /** Submits every selected applicant whose record passes pre-validation (BP-04 steps 1–2). */
    @Transactional
    public SubmissionView submit(Long windowId, NactvetSubmission.Channel channel) {
        AdmissionWindow window = requireWindow(windowId);
        Tenant tenant = currentTenant();
        if (blank(tenant.getNactvetRegNo())) {
            throw new BusinessRuleException("INSTITUTION_NOT_REGISTERED",
                    "The institution's NACTVET registration number is not set. Ask the system administrator to set it.");
        }
        List<Application> selected = applications.findByWindowIdAndStatusInOrderByReferenceNoAsc(windowId, Set.of(Status.SELECTED));
        Map<Long, ApplicantQualification> csee = csee(selected);
        Map<Long, String> index = new HashMap<>();
        csee.forEach((k, q) -> index.put(k, q.getIndexNumber()));
        List<Application> valid = selected.stream().filter(a -> issues(a, index, tenant).isEmpty()).toList();
        if (valid.isEmpty()) {
            throw new BusinessRuleException("NOTHING_TO_SUBMIT", selected.isEmpty()
                    ? "There are no selected applicants awaiting NACTVET verification"
                    : "None of the selected applicants is ready: fix the issues listed and try again");
        }

        NactvetSubmission sub = new NactvetSubmission();
        sub.setWindow(window);
        sub.setBatchNo((short) (submissions.maxBatchNo(windowId) + 1));
        sub.setChannel(channel);
        sub.setSubmittedAt(OffsetDateTime.now());
        sub.setSubmittedBy(CurrentUser.requireId());
        sub.setSubmittedByName(CurrentUser.name());
        List<Candidate> candidates = valid.stream().map(a -> candidate(a, csee.get(a.getApplicant().getId()),
                index.get(a.getApplicant().getId()), a.getVerificationRemarks())).toList();
        if (channel == NactvetSubmission.Channel.API) {
            sub.setExternalRef(nactvet.submit(tenant.getNactvetRegNo(), candidates));
        }
        submissions.save(sub);

        for (Application a : valid) {
            NactvetSubmissionItem item = new NactvetSubmissionItem();
            item.setSubmission(sub);
            item.setApplication(a);
            item.setWindowProgramme(a.getSelectedWindowProgramme());
            item.setIndexNumber(index.get(a.getApplicant().getId()));
            item.setProgrammeCode(a.getSelectedWindowProgramme().getProgramme().getNactvetCode());
            item.setRemarks(a.getVerificationRemarks());
            items.save(item);
            a.setStatus(Status.PENDING_VERIFICATION);
        }
        items.flush();
        audit.record("NACTVET_SUBMITTED", "NactvetSubmission", sub.getId(), Map.of("batchNo", sub.getBatchNo(),
                "channel", channel.name(), "applicants", valid.size(), "skipped", selected.size() - valid.size()));
        return submissionView(sub);
    }

    /** Fetches outcomes of an API submission from NACTVET (BP-04 step 2). */
    @Transactional
    public OutcomeResult fetchOutcomes(Long submissionId) {
        NactvetSubmission sub = requireSubmission(submissionId);
        if (sub.getChannel() != NactvetSubmission.Channel.API) {
            throw new BusinessRuleException("NOT_AN_API_SUBMISSION", "This batch was submitted as a file. Import NACTVET's outcome file instead.");
        }
        List<NactvetSubmissionItem> pending = requireOpen(sub);
        return apply(sub, pending, nactvet.fetchOutcomes(sub.getExternalRef(), candidates(pending)), "API");
    }

    /**
     * Imports NACTVET's outcome file for a submission (file channel, or when NACTVET returns results by email).
     * All rows are validated first; nothing is applied if any row is wrong.
     */
    @Transactional
    public OutcomeResult importOutcomes(Long submissionId, String csv) {
        NactvetSubmission sub = requireSubmission(submissionId);
        List<NactvetSubmissionItem> pending = requireOpen(sub);
        Map<String, NactvetSubmissionItem> byRef = pending.stream()
                .collect(Collectors.toMap(i -> i.getApplication().getReferenceNo().toUpperCase(Locale.ROOT), Function.identity()));

        List<List<String>> rows;
        try {
            rows = Csv.parse(csv);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("INVALID_FILE", e.getMessage());
        }
        if (rows.isEmpty()) {
            throw new BusinessRuleException("INVALID_FILE", "The file is empty");
        }
        Map<String, Integer> col = new HashMap<>();
        List<String> header = rows.get(0);
        for (int c = 0; c < header.size(); c++) {
            col.put(header.get(c).trim().toLowerCase(Locale.ROOT).replace(' ', '_'), c);
        }
        for (String required : List.of("reference", "outcome")) {
            if (!col.containsKey(required)) {
                throw new BusinessRuleException("INVALID_FILE", "Missing column '" + required + "'. Expected columns: " + OUTCOME_HEADER);
            }
        }

        List<String> errors = new ArrayList<>();
        List<Outcome> outcomes = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String ref = value(row, col, "reference").toUpperCase(Locale.ROOT);
            String outcome = value(row, col, "outcome").toUpperCase(Locale.ROOT);
            String reason = value(row, col, "reason");
            String vno = value(row, col, "verification_no");
            String multiple = value(row, col, "multiple_admission").toUpperCase(Locale.ROOT);
            int line = r + 1;
            if (outcome.isEmpty() || outcome.equals("PENDING")) {
                continue; // not decided yet by NACTVET
            }
            if (!byRef.containsKey(ref)) {
                errors.add("Row " + line + ": " + (ref.isEmpty() ? "reference is missing" : ref + " is not awaiting an outcome in this batch"));
                continue;
            }
            if (!seen.add(ref)) {
                errors.add("Row " + line + ": " + ref + " appears more than once");
                continue;
            }
            NactvetGateway.Result result;
            try {
                result = NactvetGateway.Result.valueOf(outcome);
            } catch (IllegalArgumentException e) {
                errors.add("Row " + line + ": outcome must be VERIFIED, QUERIED or REJECTED");
                continue;
            }
            if (result == NactvetGateway.Result.VERIFIED && vno.isEmpty()) {
                errors.add("Row " + line + ": verification_no is required for VERIFIED");
                continue;
            }
            if (result != NactvetGateway.Result.VERIFIED && reason.isEmpty()) {
                errors.add("Row " + line + ": reason is required for " + result);
                continue;
            }
            if (reason.length() > 300 || vno.length() > 40) {
                errors.add("Row " + line + ": reason (300) or verification_no (40) is too long");
                continue;
            }
            outcomes.add(new Outcome(byRef.get(ref).getApplication().getReferenceNo(), result, emptyToNull(reason),
                    emptyToNull(vno), Set.of("YES", "Y", "TRUE", "1").contains(multiple)));
        }
        if (!errors.isEmpty()) {
            String shown = String.join("; ", errors.subList(0, Math.min(10, errors.size())));
            throw new BusinessRuleException("INVALID_FILE", errors.size() + " row(s) need correcting: " + shown
                    + (errors.size() > 10 ? "; …" : ""));
        }
        if (outcomes.isEmpty()) {
            throw new BusinessRuleException("INVALID_FILE", "The file contains no outcomes");
        }
        return apply(sub, pending, outcomes, "FILE");
    }

    /** Records how a NACTVET query was resolved; the applicant goes back to the next submission (BP-04 step 3). */
    @Transactional
    public ApplicantRow resolveQuery(Long applicationId, String remarks) {
        Application app = applications.findById(applicationId).orElseThrow(() -> new NotFoundException("Application", applicationId));
        if (app.getStatus() != Status.QUERIED) {
            throw new BusinessRuleException("NOT_QUERIED", "Only applicants queried by NACTVET can be resolved");
        }
        app.setStatus(Status.SELECTED);
        app.setVerificationRemarks(remarks.trim());
        audit.record("NACTVET_QUERY_RESOLVED", "Application", applicationId,
                Map.of("query", String.valueOf(app.getVerificationReason()), "remarks", remarks.trim()));
        return row(app, indexNumbers(List.of(app)).get(app.getApplicant().getId()), OffsetDateTime.now(), null);
    }

    // ------------------------------------------------------------------ files

    /** The submission in NACTVET's admission verification template (downloads are audited: personal data leaves the system). */
    @Transactional
    public String submissionFile(Long submissionId) {
        NactvetSubmission sub = requireSubmission(submissionId);
        StringBuilder sb = new StringBuilder(Csv.line("S/N", "Institution", "Application Ref", "Index Number", "Exam Year",
                "First Name", "Middle Name", "Surname", "Sex", "Date of Birth", "Nationality", "Phone", "Programme Code",
                "Programme Name", "Award Level", "Remarks"));
        String institution = currentTenant().getNactvetRegNo();
        int n = 0;
        List<NactvetSubmissionItem> all = items.findBySubmissionIdOrderByIdAsc(submissionId);
        List<Candidate> cs = candidates(all);
        for (int k = 0; k < all.size(); k++) {
            NactvetSubmissionItem i = all.get(k);
            Candidate c = cs.get(k);
            sb.append(Csv.line(++n, institution, c.applicationRef(), c.indexNumber(), c.examYear(), c.firstName(), c.middleName(),
                    c.surname(), c.sex(), c.dateOfBirth(), c.nationality(), c.phone(), i.getProgrammeCode(), c.programmeName(),
                    c.awardLevel(), c.remarks()));
        }
        audit.record("NACTVET_FILE_DOWNLOADED", "NactvetSubmission", sub.getId(), Map.of("batchNo", sub.getBatchNo()));
        return sb.toString();
    }

    /** Outcome template pre-filled with the applicants still awaiting an outcome, to record NACTVET's response. */
    @Transactional(readOnly = true)
    public String outcomeTemplate(Long submissionId) {
        requireSubmission(submissionId);
        StringBuilder sb = new StringBuilder(OUTCOME_HEADER).append('\n');
        for (NactvetSubmissionItem i : items.findBySubmissionIdAndOutcome(submissionId, NactvetSubmissionItem.Outcome.PENDING)) {
            sb.append(Csv.line(i.getApplication().getReferenceNo(), i.getIndexNumber(), "", "", "", ""));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ outcome handling

    private OutcomeResult apply(NactvetSubmission sub, List<NactvetSubmissionItem> pending, List<Outcome> outcomes, String source) {
        Map<String, NactvetSubmissionItem> byRef = pending.stream()
                .collect(Collectors.toMap(i -> i.getApplication().getReferenceNo(), Function.identity()));
        String institution = currentTenant().getName();
        OffsetDateTime now = OffsetDateTime.now();
        int verified = 0, queried = 0, rejected = 0;
        for (Outcome o : outcomes) {
            NactvetSubmissionItem item = byRef.remove(o.applicationRef());
            if (item == null) {
                continue; // unknown or already decided: outcomes are idempotent
            }
            Application app = item.getApplication();
            item.setOutcomeAt(now);
            item.setReason(o.reason());
            switch (o.result()) {
                case VERIFIED -> {
                    item.setOutcome(NactvetSubmissionItem.Outcome.VERIFIED);
                    item.setVerificationNo(o.verificationNo());
                    item.setMultipleAdmission(o.multipleAdmission());
                    offers.issue(app, o.verificationNo(), o.multipleAdmission(), institution);
                    verified++;
                }
                case QUERIED -> {
                    item.setOutcome(NactvetSubmissionItem.Outcome.QUERIED);
                    app.setStatus(Status.QUERIED);
                    app.setVerificationReason(o.reason());
                    queried++;
                }
                case REJECTED -> {
                    item.setOutcome(NactvetSubmissionItem.Outcome.REJECTED);
                    app.setStatus(Status.NACTVET_REJECTED);
                    app.setVerificationReason(o.reason());
                    sms.send(app.getApplicant().getPhone(), "We regret that NACTVET did not verify your admission to "
                            + programmeName(app) + " at " + institution + ": " + o.reason()
                            + ". Please contact the admissions office for advice.");
                    rejected++;
                }
            }
        }
        sub.setLastOutcomeAt(now);
        if (byRef.isEmpty()) {
            sub.setStatus(NactvetSubmission.Status.COMPLETED);
            sub.setCompletedAt(now);
        }
        int applied = verified + queried + rejected;
        audit.record("NACTVET_OUTCOMES_RECORDED", "NactvetSubmission", sub.getId(), Map.of("source", source,
                "verified", verified, "queried", queried, "rejected", rejected, "stillPending", byRef.size()));
        items.flush();
        return new OutcomeResult(applied, verified, queried, rejected, submissionView(sub));
    }

    // ------------------------------------------------------------------ pre-validation

    /** Problems NACTVET would query or reject, so they are fixed before submission. */
    private List<String> issues(Application a, Map<Long, String> index, Tenant tenant) {
        List<String> out = new ArrayList<>();
        Applicant ap = a.getApplicant();
        if (index.get(ap.getId()) == null) out.add("No verified CSEE index number");
        if (blank(ap.getFirstName()) || blank(ap.getSurname())) out.add("Name incomplete");
        if (ap.getSex() == null) out.add("Sex not stated");
        if (ap.getDateOfBirth() == null) out.add("Date of birth missing");
        if (blank(ap.getNationality())) out.add("Nationality missing");
        if (a.getSelectedWindowProgramme() == null) {
            out.add("No selected programme");
        } else if (blank(a.getSelectedWindowProgramme().getProgramme().getNactvetCode())) {
            out.add("Programme " + a.getSelectedWindowProgramme().getProgramme().getCode() + " has no NACTVET programme code");
        }
        if (blank(tenant.getNactvetRegNo())) out.add("Institution NACTVET registration number not set");
        return out;
    }

    /** The CSEE sitting used for NACTVET, per applicant: the latest verified one. */
    private Map<Long, ApplicantQualification> csee(List<Application> apps) {
        if (apps.isEmpty()) return Map.of();
        Map<Long, ApplicantQualification> out = new HashMap<>();
        qualifications.findByApplicantIdIn(apps.stream().map(a -> a.getApplicant().getId()).distinct().toList()).stream()
                .filter(q -> q.getType() == ApplicantQualification.Type.CSEE && q.isVerified() && !blank(q.getIndexNumber()))
                .sorted(Comparator.comparing((ApplicantQualification q) -> q.getExamYear() == null ? 0 : q.getExamYear()))
                .forEach(q -> out.put(q.getApplicant().getId(), q));
        return out;
    }

    private Map<Long, String> indexNumbers(List<Application> apps) {
        Map<Long, String> out = new HashMap<>();
        csee(apps).forEach((applicantId, q) -> out.put(applicantId, q.getIndexNumber()));
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static Candidate candidate(Application a, ApplicantQualification q, String indexNumber, String remarks) {
        Applicant ap = a.getApplicant();
        var p = a.getSelectedWindowProgramme().getProgramme();
        Integer year = q == null || q.getExamYear() == null ? null : (int) q.getExamYear();
        return new Candidate(a.getReferenceNo(), indexNumber, year, ap.getFirstName(), ap.getMiddleName(), ap.getSurname(),
                ap.getSex(), ap.getDateOfBirth(), ap.getNationality(), ap.getPhone(), p.getNactvetCode(), p.getName(),
                p.getAwardLevel(), remarks);
    }

    /** Candidates for submitted items, as they were sent (index number and remarks from the item). */
    private List<Candidate> candidates(List<NactvetSubmissionItem> list) {
        Map<Long, ApplicantQualification> q = csee(list.stream().map(NactvetSubmissionItem::getApplication).toList());
        return list.stream().map(i -> candidate(i.getApplication(), q.get(i.getApplication().getApplicant().getId()),
                i.getIndexNumber(), i.getRemarks())).toList();
    }

    private List<NactvetSubmissionItem> requireOpen(NactvetSubmission sub) {
        List<NactvetSubmissionItem> pending = items.findBySubmissionIdAndOutcome(sub.getId(), NactvetSubmissionItem.Outcome.PENDING);
        if (pending.isEmpty()) {
            throw new BusinessRuleException("SUBMISSION_COMPLETE", "NACTVET has already returned an outcome for every applicant in this batch");
        }
        return pending;
    }

    private List<SubmissionView> submissionViews(Long windowId) {
        Map<Long, Map<NactvetSubmissionItem.Outcome, Long>> counts = new HashMap<>();
        for (Object[] r : items.outcomeCounts(windowId)) {
            counts.computeIfAbsent((Long) r[0], k -> new java.util.EnumMap<>(NactvetSubmissionItem.Outcome.class))
                    .put((NactvetSubmissionItem.Outcome) r[1], ((Number) r[2]).longValue());
        }
        return submissions.findByWindowIdOrderByBatchNoDesc(windowId).stream()
                .map(s -> view(s, counts.getOrDefault(s.getId(), Map.of()))).toList();
    }

    private SubmissionView submissionView(NactvetSubmission s) {
        Map<NactvetSubmissionItem.Outcome, Long> c = items.findBySubmissionIdOrderByIdAsc(s.getId()).stream()
                .collect(Collectors.groupingBy(NactvetSubmissionItem::getOutcome, Collectors.counting()));
        return view(s, c);
    }

    private static SubmissionView view(NactvetSubmission s, Map<NactvetSubmissionItem.Outcome, Long> c) {
        long total = c.values().stream().mapToLong(Long::longValue).sum();
        return new SubmissionView(s.getId(), s.getBatchNo(), s.getChannel().name(), s.getStatus().name(), s.getExternalRef(),
                s.getSubmittedAt(), s.getSubmittedByName(), s.getLastOutcomeAt(), s.getCompletedAt(), total,
                c.getOrDefault(NactvetSubmissionItem.Outcome.PENDING, 0L), c.getOrDefault(NactvetSubmissionItem.Outcome.VERIFIED, 0L),
                c.getOrDefault(NactvetSubmissionItem.Outcome.QUERIED, 0L), c.getOrDefault(NactvetSubmissionItem.Outcome.REJECTED, 0L));
    }

    private static ApplicantRow row(Application a, String indexNumber, OffsetDateTime now, AdmissionLetter letter) {
        Applicant ap = a.getApplicant();
        boolean expired = a.getStatus() == Status.OFFERED && a.getOfferExpiresAt() != null && !now.isBefore(a.getOfferExpiresAt());
        return new ApplicantRow(a.getId(), a.getReferenceNo(), ap.fullName(), ap.getPhone(), ap.getSex(), programmeName(a),
                a.getStatus().name(), indexNumber, a.getNactvetVerificationNo(), a.isMultipleAdmission(), a.getVerificationReason(),
                a.getVerificationRemarks(), a.getVerifiedAt(), a.getOfferExpiresAt(), a.getOfferRespondedAt(),
                a.getOfferDeclineReason(), expired, letter == null ? null : letter.getLetterNo(),
                letter == null ? null : (int) letter.getEdition(), letter == null ? null : letter.getIssuedAt());
    }

    private static String programmeName(Application a) {
        return a.getSelectedWindowProgramme() == null ? null : a.getSelectedWindowProgramme().getProgramme().getName();
    }

    private AdmissionWindow requireWindow(Long id) {
        return windows.findById(id).orElseThrow(() -> new NotFoundException("Admission window", id));
    }

    private NactvetSubmission requireSubmission(Long id) {
        return submissions.findById(id).orElseThrow(() -> new NotFoundException("NACTVET submission", id));
    }

    private Tenant currentTenant() {
        return tenants.findById(TenantContext.requireTenantId()).orElseThrow();
    }

    private static String value(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= row.size() ? "" : row.get(i).trim();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

}
