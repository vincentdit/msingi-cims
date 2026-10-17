package tz.co.msingi.cims.admission.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.AdmissionWindowRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicantQualificationRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicationRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.WindowProgrammeRepository;
import tz.co.msingi.cims.admission.domain.AdmissionWindow;
import tz.co.msingi.cims.admission.domain.Applicant;
import tz.co.msingi.cims.admission.domain.ApplicantQualification;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.domain.ApplicationChoice;
import tz.co.msingi.cims.admission.domain.WindowProgramme;
import tz.co.msingi.cims.admission.eligibility.EligibilityEngine;
import tz.co.msingi.cims.admission.eligibility.EntryLevel;
import tz.co.msingi.cims.admission.eligibility.EligibilityResult;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ApplicationDetailView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ApplicationSummaryView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ApplicationView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.LetterView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.StudentInfo;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.admission.service.AdmissionDtos.FeeView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ProfileView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ProgrammeEligibilityView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.QualificationView;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.common.web.PageResponse;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.gepg.GepgProperties;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.finance.service.InvoicePaidEvent;
import tz.co.msingi.cims.admission.letter.AdmissionLetter;
import tz.co.msingi.cims.admission.letter.AdmissionLetterRepository;
import tz.co.msingi.cims.admission.offer.OfferService;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.security.OtpService;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.numbering.NumberSequenceService;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The applicant's application: start, pay fee, choose programmes, submit (BP-02, BRU-01/02). */
@Service
@RequiredArgsConstructor
public class ApplicationService {

    public static final String PURPOSE_APPLICATION_FEE = "APPLICATION_FEE";

    /** Testing period: lets the simulate-payment button pay fees whatever payment route is set up (cims.payments.allow-simulation). */
    @org.springframework.beans.factory.annotation.Value("${cims.payments.allow-simulation:false}")
    private boolean allowPaymentSimulation;

    private final ApplicationRepository applications;
    private final AdmissionWindowRepository windows;
    private final WindowProgrammeRepository windowProgrammes;
    private final ApplicantQualificationRepository qualifications;
    private final ApplicantProfileService profiles;
    private final EligibilityEngine engine;
    private final BillingService billing;
    private final GepgProperties gepgProps;
    private final NumberSequenceService sequences;
    private final AuditService audit;
    private final Messaging messaging;
    private final OtpService otp;
    private final OfferService offers;
    private final AdmissionLetterRepository letters;
    private final StudentRepository students;
    private final ApplicationEditGuard editGuard;
    private final tz.co.msingi.cims.platform.audit.AuditLogRepository auditLogs;
    private final tz.co.msingi.cims.admission.document.ApplicationDocumentService documents;

    // ------------------------------------------------------------------ applicant side

    @Transactional(readOnly = true)
    public List<ApplicationView> myApplications() {
        Applicant a = profiles.currentApplicant();
        return applications.findByApplicantIdOrderByCreatedAtDesc(a.getId()).stream().map(app -> view(app, a)).toList();
    }

    @Transactional(readOnly = true)
    public ApplicationView myApplication(Long id) {
        Applicant a = profiles.currentApplicant();
        return view(own(id, a), a);
    }

    @Transactional
    public ApplicationView start(Long windowId) {
        Applicant a = profiles.currentApplicant();
        AdmissionWindow w = windows.findById(windowId).orElseThrow(() -> new NotFoundException("Admission window", windowId));
        if (!w.isOpen(OffsetDateTime.now())) {
            throw new BusinessRuleException("WINDOW_NOT_OPEN", "This admission window is not open for applications");
        }
        return applications.findByApplicantIdAndWindowId(a.getId(), windowId)
                .map(existing -> view(existing, a))
                .orElseGet(() -> {
                    Application app = new Application();
                    app.setApplicant(a);
                    app.setWindow(w);
                    app.setStatus(Application.Status.DRAFT);
                    if (w.getApplicationFee().signum() == 0) {
                        app.setFeePaid(true); // free window
                    }
                    app = applications.save(app);
                    audit.record("START", "Application", app.getId(), Map.of("window", w.getName()));
                    return view(app, a);
                });
    }

    /** Creates the application-fee invoice (once) and returns a usable GePG control number. */
    @Transactional
    public FeeView requestFee(Long applicationId) {
        Applicant a = profiles.currentApplicant();
        Application app = own(applicationId, a);
        requireEditableAndOpen(app);
        if (app.isFeePaid()) {
            return feeView(app);
        }
        if (app.getFeeInvoiceId() == null) {
            AdmissionWindow w = app.getWindow();
            Invoice inv = billing.createInvoice(new BillingService.NewInvoice(
                    Invoice.PayerType.APPLICANT, a.getId(), a.fullName(), a.getPhone(), a.getEmail(),
                    PURPOSE_APPLICATION_FEE, app.getId(), "Application fee — " + w.getName(),
                    List.of(new BillingService.NewInvoice.Line(PURPOSE_APPLICATION_FEE, "Application fee", w.getApplicationFee()))));
            app.setFeeInvoiceId(inv.getId());
        }
        billing.ensureControlNumber(app.getFeeInvoiceId());
        app.setStatus(Application.Status.PAYMENT_PENDING);
        return feeView(app);
    }

    @Transactional(readOnly = true)
    public FeeView fee(Long applicationId) {
        return feeView(own(applicationId, profiles.currentApplicant()));
    }

    /** Marks the application fee as paid when GePG confirms payment of its invoice. */
    @EventListener
    public void onInvoicePaid(InvoicePaidEvent e) {
        if (!PURPOSE_APPLICATION_FEE.equals(e.purpose()) || e.referenceId() == null) {
            return;
        }
        applications.findById(e.referenceId()).ifPresent(app -> {
            app.setFeePaid(true);
            if (app.getStatus() == Application.Status.PAYMENT_PENDING) {
                app.setStatus(Application.Status.DRAFT);
            }
            audit.recordAs(null, "Payment", "FEE_PAID", "Application", app.getId(), Map.of("invoiceId", e.invoiceId()));
        });
    }

    /** Development only: simulates paying the current control number or payment reference. */
    @Transactional
    public FeeView simulatePayment(Long applicationId) {
        if (!allowPaymentSimulation && !gepgProps.isSimulated()) {
            throw new BusinessRuleException("NOT_AVAILABLE", "Payment simulation is only available with simulated GePG");
        }
        Application app = own(applicationId, profiles.currentApplicant());
        if (app.getFeeInvoiceId() == null) {
            throw new BusinessRuleException("NO_INVOICE", "Request a control number first");
        }
        ControlNumber cn = billing.ensureControlNumber(app.getFeeInvoiceId());
        Invoice inv = billing.get(app.getFeeInvoiceId());
        billing.recordPayment(new BillingService.PaymentNotification(cn.getBillId(), cn.getControlNo(),
                "SIM" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(),
                "PSP-" + System.currentTimeMillis(), "Simulated Bank", "MOBILE", inv.balance(), inv.getPayerPhone(), OffsetDateTime.now()));
        return feeView(applications.findById(applicationId).orElseThrow());
    }

    /** Eligibility of the current applicant for every programme in a window (shown before choosing). */
    @Transactional(readOnly = true)
    public List<ProgrammeEligibilityView> eligibility(Long windowId) {
        Applicant a = profiles.currentApplicant();
        List<EligibilityEngine.Qualification> quals = engineQualifications(a);
        EntryLevel level = EntryLevel.of(quals);
        return windowProgrammes.findByWindowIdOrderByIdAsc(windowId).stream().map(wp -> {
            EligibilityResult r = engine.evaluate(wp.getProgramme().getAwardFamily().name(), wp.getProgramme().entryLevel(), wp.getEntryRules(), quals);
            var p = wp.getProgramme();
            return new ProgrammeEligibilityView(wp.getId(), p.getCode(), p.getName(), p.getAwardName(),
                    p.getDepartment().getName(), wp.getCapacity(), r.eligible(), r, p.getAwardFamily().name(), p.entryLevel(),
                    level.allows(p.getAwardFamily().name(), p.entryLevel(), wp.getEntryRules()));
        }).toList();
    }

    /** The level the current applicant may apply at, from their highest verified qualification (as in NOAS). */
    @Transactional(readOnly = true)
    public EntryLevel entryLevel() {
        return EntryLevel.of(engineQualifications(profiles.currentApplicant()));
    }

    /** Replaces the programme choices. Requires the fee to be paid and every choice to be eligible (BRU-01, BRU-02). */
    @Transactional
    public ApplicationView setChoices(Long applicationId, List<Long> windowProgrammeIds) {
        Applicant a = profiles.currentApplicant();
        Application app = own(applicationId, a);
        boolean amending = editGuard.amendable(app);
        if (!amending) requireEditableAndOpen(app);
        if (!app.isFeePaid()) {
            throw new BusinessRuleException("FEE_NOT_PAID", "Pay the application fee before choosing programmes");
        }
        if (amending && windowProgrammeIds.isEmpty()) {
            throw new BusinessRuleException("CHOICE_REQUIRED", "A submitted application needs at least one programme");
        }
        AdmissionWindow w = app.getWindow();
        if (windowProgrammeIds.size() > w.getMaxChoices()) {
            throw new BusinessRuleException("TOO_MANY_CHOICES", "You can choose at most " + w.getMaxChoices() + " programmes");
        }
        Set<Long> unique = new HashSet<>(windowProgrammeIds);
        if (unique.size() != windowProgrammeIds.size()) {
            throw new BusinessRuleException("DUPLICATE_CHOICE", "Each programme can be chosen only once");
        }
        List<EligibilityEngine.Qualification> quals = engineQualifications(a);
        List<ApplicationChoice> newChoices = new ArrayList<>();
        short order = 1;
        for (Long wpId : windowProgrammeIds) {
            WindowProgramme wp = windowProgrammes.findById(wpId)
                    .filter(x -> x.getWindow().getId().equals(w.getId()))
                    .orElseThrow(() -> new BusinessRuleException("INVALID_CHOICE", "Programme " + wpId + " is not offered in this window"));
            EligibilityResult r = engine.evaluate(wp.getProgramme().getAwardFamily().name(), wp.getProgramme().entryLevel(), wp.getEntryRules(), quals);
            if (!r.eligible()) {
                throw new BusinessRuleException("NOT_ELIGIBLE",
                        "You do not meet the minimum entry requirements for " + wp.getProgramme().getName() + ". " + r.summary());
            }
            ApplicationChoice c = new ApplicationChoice();
            c.setApplication(app);
            c.setWindowProgramme(wp);
            c.setChoiceOrder(order++);
            c.setEligible(true);
            c.setEligibilityDetail(r);
            newChoices.add(c);
        }
        app.getChoices().clear();
        applications.flush(); // delete old rows before inserting new ones (unique choice order)
        app.getChoices().addAll(newChoices);
        audit.record(amending ? "AMEND_CHOICES" : "SET_CHOICES", "Application", app.getId(), Map.of("windowProgrammeIds", windowProgrammeIds));
        editGuard.markAmended(app);
        return view(applications.saveAndFlush(app), a);
    }

    @Transactional
    public ApplicationView submit(Long applicationId) {
        Applicant a = profiles.currentApplicant();
        Application app = own(applicationId, a);
        List<String> blockers = blockers(app, a);
        if (!blockers.isEmpty()) {
            throw new BusinessRuleException("NOT_READY", "Complete these steps before submitting: " + String.join("; ", blockers));
        }
        String year = app.getWindow().getAcademicYear().getCode().substring(0, 4);
        String prefix = TenantContext.require().code() + "/APP/" + year + "/";
        app.setReferenceNo(NumberSequenceService.format(prefix, sequences.next("APPLICATION-" + year), 6));
        app.setStatus(Application.Status.SUBMITTED);
        app.setSubmittedAt(OffsetDateTime.now());
        audit.record("SUBMIT", "Application", app.getId(), Map.of("referenceNo", app.getReferenceNo()));
        messaging.event("APPLICATION_RECEIVED", a.getPhone(), Map.of("referenceNo", app.getReferenceNo()));
        return view(app, a);
    }

    // ------------------------------------------------------------------ staff side

    @Transactional(readOnly = true)
    public PageResponse<ApplicationSummaryView> search(Long windowId, Application.Status status, String q, int page, int size) {
        Page<Application> result = applications.search(windowId, status, q == null || q.isBlank() ? null : q.trim(),
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(result, ApplicationSummaryView::of);
    }

    @Transactional(readOnly = true)
    public ApplicationDetailView detail(Long id) {
        Application app = applications.findById(id).orElseThrow(() -> new NotFoundException("Application", id));
        Applicant a = app.getApplicant();
        List<QualificationView> quals = qualifications.findByApplicantIdOrderByIdAsc(a.getId()).stream().map(QualificationView::of).toList();
        return new ApplicationDetailView(view(app, a), ProfileView.of(a), quals, feeView(app), history(app));
    }

    /** The application's history: its own audit entries and its applicant's, oldest first. */
    @Transactional(readOnly = true)
    public List<AdmissionDtos.HistoryEntry> history(Application app) {
        List<tz.co.msingi.cims.platform.audit.AuditLog> all = new java.util.ArrayList<>(auditLogs.findByEntityAndEntityIdOrderByOccurredAtAsc("Application", app.getId()));
        all.addAll(auditLogs.findByEntityAndEntityIdOrderByOccurredAtAsc("Applicant", app.getApplicant().getId()));
        return all.stream().sorted(java.util.Comparator.comparing(tz.co.msingi.cims.platform.audit.AuditLog::getOccurredAt))
                .map(l -> new AdmissionDtos.HistoryEntry(l.getOccurredAt(), l.getAction(), HistoryLabels.of(l.getAction()), l.getActorName(), l.getDetails()))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private Application own(Long id, Applicant a) {
        return applications.findById(id)
                .filter(app -> app.getApplicant().getId().equals(a.getId()))
                .orElseThrow(() -> new NotFoundException("Application", id));
    }

    private void requireEditableAndOpen(Application app) {
        if (!app.isEditable()) {
            throw new BusinessRuleException("APPLICATION_LOCKED", app.getStatus() == Application.Status.SUBMITTED
                    ? "This application can no longer be changed: the window has closed or selection has started"
                    : "This application has already been submitted");
        }
        if (!app.getWindow().isOpen(OffsetDateTime.now())) {
            throw new BusinessRuleException("WINDOW_NOT_OPEN", "The admission window is closed");
        }
    }

    private List<EligibilityEngine.Qualification> engineQualifications(Applicant a) {
        return qualifications.findByApplicantIdOrderByIdAsc(a.getId()).stream().map(ApplicantQualification::toEngine).toList();
    }

    /** Steps still missing before submission, in plain English (the submit refusal; the portal shows {@link #todo} in the applicant's language). */
    List<String> blockers(Application app, Applicant a) {
        return todo(app, a).stream().map(ApplicationService::english).toList();
    }

    /** Steps still missing before submission — shown to the applicant as a checklist; a code and its details, worded by the portal. */
    List<AdmissionDtos.Todo> todo(Application app, Applicant a) {
        List<AdmissionDtos.Todo> b = new ArrayList<>();
        if (!app.isEditable()) {
            return b;
        }
        if (!app.getWindow().isOpen(OffsetDateTime.now())) b.add(new AdmissionDtos.Todo("WINDOW_CLOSED", List.of()));
        if (!a.missingProfileFields().isEmpty()) b.add(new AdmissionDtos.Todo("PROFILE", a.missingProfileFields()));
        if (qualifications.findByApplicantIdOrderByIdAsc(a.getId()).stream().noneMatch(ApplicantQualification::isVerified)) {
            b.add(new AdmissionDtos.Todo("NECTA", List.of()));
        }
        if (!app.isFeePaid()) b.add(new AdmissionDtos.Todo("FEE", List.of()));
        if (app.getChoices().isEmpty()) b.add(new AdmissionDtos.Todo("CHOICE", List.of()));
        List<String> docs = documents.missingRequired(app);
        if (!docs.isEmpty()) b.add(new AdmissionDtos.Todo("DOCUMENTS", docs));
        // qualifications added since choosing may have changed the entry level (as in NOAS)
        List<EligibilityEngine.Qualification> quals = engineQualifications(a);
        for (ApplicationChoice c : app.getChoices()) {
            var p = c.getWindowProgramme().getProgramme();
            EligibilityResult r = engine.evaluate(p.getAwardFamily().name(), p.entryLevel(), c.getWindowProgramme().getEntryRules(), quals);
            if (!r.eligible()) b.add(new AdmissionDtos.Todo("CHANGE_CHOICE", List.of(p.getName(), r.summary())));
        }
        return b;
    }

    private static final java.util.Map<String, String> FIELD_WORDS = java.util.Map.of("sex", "sex", "dateOfBirth", "date of birth",
            "nationality", "nationality", "region", "region", "nextOfKinName", "next of kin's name", "nextOfKinPhone", "next of kin's phone");

    static String english(AdmissionDtos.Todo t) {
        return switch (t.code()) {
            case "WINDOW_CLOSED" -> "The admission window is closed";
            case "PROFILE" -> "Complete your personal details: " + String.join(", ", t.items().stream().map(f -> FIELD_WORDS.getOrDefault(f, f)).toList());
            case "NECTA" -> "Add your NECTA results";
            case "FEE" -> "Pay the application fee";
            case "CHOICE" -> "Choose at least one programme";
            case "DOCUMENTS" -> "Upload " + String.join(", ", t.items());
            case "CHANGE_CHOICE" -> "Change your choice of " + t.items().get(0) + ": " + t.items().get(1);
            default -> t.code();
        };
    }

    private ApplicationView view(Application app, Applicant a) {
        String devCode = otp.exposeInResponse() ? offers.developmentConfirmationCode(app).orElse(null) : null;
        LetterView letter = letters.findByApplicationIdAndStatus(app.getId(), AdmissionLetter.Status.VALID)
                .map(l -> new LetterView(l.getLetterNo(), l.getEdition(), l.getVerificationCode(), l.getIssuedAt())).orElse(null);
        StudentInfo student = students.findByApplicationId(app.getId())
                .map(st -> new StudentInfo(st.getRegistrationNo(), st.getStatus().name())).orElse(null);
        List<AdmissionDtos.Todo> todo = todo(app, a);
        return ApplicationView.of(app, todo.stream().map(ApplicationService::english).toList(), todo, devCode, letter, student, editGuard.amendable(app));
    }

    private FeeView feeView(Application app) {
        if (app.getFeeInvoiceId() == null) {
            BigDecimal fee = app.getWindow().getApplicationFee();
            return new FeeView(null, null, fee, BigDecimal.ZERO, null, null, null, app.isFeePaid());
        }
        Invoice inv = billing.get(app.getFeeInvoiceId());
        ControlNumber current = billing.currentControlNumber(inv.getId());
        return new FeeView(inv.getId(), inv.getInvoiceNo(), inv.getTotalAmount(), inv.getPaidAmount(), inv.getStatus().name(),
                current == null ? null : current.getControlNo(), current == null ? null : current.getExpiresAt(), app.isFeePaid());
    }
}
