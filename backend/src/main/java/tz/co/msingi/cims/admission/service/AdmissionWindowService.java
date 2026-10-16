package tz.co.msingi.cims.admission.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.IntakeRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.AdmissionWindowRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicationRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.WindowProgrammeRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.AdmissionWindowEventRepository;
import tz.co.msingi.cims.admission.domain.AdmissionWindow;
import tz.co.msingi.cims.admission.domain.AdmissionWindowEvent;
import tz.co.msingi.cims.admission.selection.SelectionRepositories.SelectionRoundRepository;
import tz.co.msingi.cims.admission.service.AdmissionDtos.CloseRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.OpenRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ReopenRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.WindowEventView;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.domain.WindowProgramme;
import tz.co.msingi.cims.admission.eligibility.EntryRules;
import tz.co.msingi.cims.admission.service.AdmissionDtos.WindowProgrammeRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.WindowProgrammeView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.WindowRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.WindowStats;
import tz.co.msingi.cims.admission.service.AdmissionDtos.WindowView;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/** Admission window set-up, publication, opening, closing, reopening and extension (BP-01, FR-ADM-01). */
@Service
@RequiredArgsConstructor
public class AdmissionWindowService {

    private final AdmissionWindowRepository windows;
    private final WindowProgrammeRepository windowProgrammes;
    private final AcademicYearRepository years;
    private final IntakeRepository intakes;
    private final ProgrammeRepository programmes;
    private final ApplicationRepository applications;
    private final AuditService audit;
    private final AdmissionWindowEventRepository events;
    private final SelectionRoundRepository rounds;
    private final Messaging messaging;

    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter SMS_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH);

    @Transactional(readOnly = true)
    public List<WindowView> list() {
        return windows.findAllByOrderByOpensAtDesc().stream().map(w -> WindowView.of(w, null)).toList();
    }

    @Transactional(readOnly = true)
    public WindowView get(Long id) {
        AdmissionWindow w = find(id);
        return WindowView.of(w, windowProgrammes.findByWindowIdOrderByIdAsc(id));
    }

    @Transactional(readOnly = true)
    public List<WindowView> openWindows() {
        return windows.findOpen(OffsetDateTime.now()).stream()
                .map(w -> WindowView.of(w, windowProgrammes.findByWindowIdOrderByIdAsc(w.getId())))
                .toList();
    }

    @Transactional
    public WindowView save(Long id, WindowRequest req) {
        if (!req.closesAt().isAfter(req.opensAt())) {
            throw new BusinessRuleException("INVALID_DATES", "The closing date must be after the opening date");
        }
        OffsetDateTime now = OffsetDateTime.now();
        AdmissionWindow w = id == null ? new AdmissionWindow() : find(id);
        String state = id == null ? "DRAFT" : w.state(now);
        if (state.equals("CLOSED")) {
            throw new BusinessRuleException("WINDOW_CLOSED", "This window is closed. Reopen it with a new closing date instead.");
        }
        if (id != null && w.getStatus() == AdmissionWindow.Status.PUBLISHED && applications.countByWindowId(id) > 0
                && w.getApplicationFee().compareTo(req.applicationFee()) != 0) {
            throw new BusinessRuleException("FEE_LOCKED", "The application fee cannot change after applications have started");
        }
        OffsetDateTime oldOpens = w.getOpensAt();
        OffsetDateTime oldCloses = w.getClosesAt();
        if (w.getStatus() == AdmissionWindow.Status.PUBLISHED) {
            if (!req.closesAt().isAfter(now)) {
                throw new BusinessRuleException("CLOSING_DATE_PAST", "The closing date has to be in the future. To stop applications now, close the window.");
            }
            if (state.equals("OPEN") && !req.opensAt().truncatedTo(ChronoUnit.SECONDS).isEqual(oldOpens.truncatedTo(ChronoUnit.SECONDS))) {
                throw new BusinessRuleException("ALREADY_OPEN", "The window is already open, so its opening date cannot change");
            }
        }
        w.setAcademicYear(years.findById(req.academicYearId()).orElseThrow(() -> new NotFoundException("Academic year", req.academicYearId())));
        w.setIntake(intakes.findById(req.intakeId()).orElseThrow(() -> new NotFoundException("Intake", req.intakeId())));
        w.setName(req.name().trim());
        w.setOpensAt(state.equals("OPEN") ? oldOpens : req.opensAt());
        w.setClosesAt(req.closesAt());
        w.setApplicationFee(req.applicationFee());
        w.setMaxChoices(req.maxChoices());
        if (req.offerAcceptanceDays() != null) {
            w.setOfferAcceptanceDays(req.offerAcceptanceDays());
        }
        w.setInstructions(req.instructions());
        if (req.feeReminders() != null) w.setFeeReminders(req.feeReminders());
        w = windows.save(w);
        audit.record(id == null ? "CREATE" : "UPDATE", "AdmissionWindow", w.getId(), Map.of("name", w.getName()));
        if (id == null) {
            event(w, AdmissionWindowEvent.Action.CREATED, null, null, 0, false);
        } else if (w.getStatus() == AdmissionWindow.Status.PUBLISHED && (!oldCloses.isEqual(w.getClosesAt()) || !oldOpens.isEqual(w.getOpensAt()))) {
            boolean extended = w.getClosesAt().isAfter(oldCloses) && oldOpens.isEqual(w.getOpensAt());
            event(w, extended ? AdmissionWindowEvent.Action.EXTENDED : AdmissionWindowEvent.Action.RESCHEDULED, null, oldCloses, 0, false);
        }
        return get(w.getId());
    }

    @Transactional
    public WindowProgrammeView addProgramme(Long windowId, WindowProgrammeRequest req) {
        AdmissionWindow w = find(windowId);
        requireNotClosed(w);
        if (windowProgrammes.existsByWindowIdAndProgrammeId(windowId, req.programmeId())) {
            throw new BusinessRuleException("PROGRAMME_EXISTS", "This programme is already offered in the window");
        }
        Programme p = programmes.findById(req.programmeId()).orElseThrow(() -> new NotFoundException("Programme", req.programmeId()));
        if (!p.isActive()) {
            throw new BusinessRuleException("PROGRAMME_INACTIVE", "Inactive programmes cannot be offered");
        }
        checkCapacity(p, req.capacity());
        validateRules(req.entryRules());
        WindowProgramme wp = new WindowProgramme();
        wp.setWindow(w);
        wp.setProgramme(p);
        wp.setCapacity(req.capacity());
        wp.setEntryRules(req.entryRules());
        wp = windowProgrammes.save(wp);
        audit.record("ADD_PROGRAMME", "AdmissionWindow", windowId, Map.of("programme", p.getCode(), "capacity", req.capacity()));
        return WindowProgrammeView.of(wp);
    }

    @Transactional
    public WindowProgrammeView updateProgramme(Long windowId, Long windowProgrammeId, WindowProgrammeRequest req) {
        AdmissionWindow w = find(windowId);
        requireNotClosed(w);
        WindowProgramme wp = windowProgrammes.findById(windowProgrammeId)
                .filter(x -> x.getWindow().getId().equals(windowId))
                .orElseThrow(() -> new NotFoundException("Window programme", windowProgrammeId));
        checkCapacity(wp.getProgramme(), req.capacity());
        validateRules(req.entryRules());
        wp.setCapacity(req.capacity());
        wp.setEntryRules(req.entryRules());
        audit.record("UPDATE_PROGRAMME", "AdmissionWindow", windowId, Map.of("programme", wp.getProgramme().getCode(), "capacity", req.capacity()));
        return WindowProgrammeView.of(wp);
    }

    /** Publishes a draft: applicants see it at once and can apply from its opening date to its closing date. */
    @Transactional
    public WindowView publish(Long id) {
        AdmissionWindow w = find(id);
        if (w.getStatus() != AdmissionWindow.Status.DRAFT) {
            throw new BusinessRuleException("INVALID_STATUS", "Only a draft window can be published");
        }
        requireProgrammes(id);
        if (!w.getClosesAt().isAfter(OffsetDateTime.now())) {
            throw new BusinessRuleException("CLOSING_DATE_PAST", "The closing date has passed. Change it before publishing.");
        }
        w.setStatus(AdmissionWindow.Status.PUBLISHED);
        audit.record("PUBLISH", "AdmissionWindow", id, Map.of("opensAt", w.getOpensAt().toString(), "closesAt", w.getClosesAt().toString()));
        event(w, AdmissionWindowEvent.Action.PUBLISHED, null, null, 0, false);
        return get(id);
    }

    /** Opens a draft or scheduled window now, optionally with a new closing date. A closed window is reopened instead. */
    @Transactional
    public WindowView open(Long id, OpenRequest req) {
        AdmissionWindow w = find(id);
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        String state = w.state(now);
        if (state.equals("OPEN")) throw new BusinessRuleException("ALREADY_OPEN", "This window is already open");
        if (state.equals("CLOSED")) throw new BusinessRuleException("WINDOW_CLOSED", "This window is closed. Reopen it with a new closing date instead.");
        requireProgrammes(id);
        OffsetDateTime previous = w.getClosesAt();
        OffsetDateTime closes = req == null || req.closesAt() == null ? w.getClosesAt() : req.closesAt();
        if (!closes.isAfter(now)) {
            throw new BusinessRuleException("CLOSING_DATE_PAST", "Give a closing date in the future");
        }
        w.setOpensAt(now);
        w.setClosesAt(closes);
        w.setStatus(AdmissionWindow.Status.PUBLISHED);
        audit.record("OPEN", "AdmissionWindow", id, Map.of("closesAt", closes.toString()));
        event(w, AdmissionWindowEvent.Action.OPENED, null, previous.isEqual(closes) ? null : previous, 0, false);
        return get(id);
    }

    /** Stops applications now, with the reason; applicants who have not submitted are told by SMS unless asked not to. */
    @Transactional
    public WindowView close(Long id, CloseRequest req) {
        AdmissionWindow w = find(id);
        OffsetDateTime now = OffsetDateTime.now();
        String state = w.state(now);
        if (w.getStatus() == AdmissionWindow.Status.DRAFT) throw new BusinessRuleException("INVALID_STATUS", "A draft window is not open, so there is nothing to close");
        if (w.getStatus() == AdmissionWindow.Status.CLOSED) throw new BusinessRuleException("WINDOW_CLOSED", "This window is already closed");
        OffsetDateTime previous = w.getClosesAt();
        if (state.equals("OPEN")) {
            w.setClosesAt(now.truncatedTo(ChronoUnit.MICROS));
        }
        w.setStatus(AdmissionWindow.Status.CLOSED);
        int notified = Boolean.FALSE.equals(req.notifyApplicants()) || !state.equals("OPEN") ? 0
                : notifyUnsubmitted(w, "ADMISSION_WINDOW_CLOSED", Map.of("window", w.getName(), "reason", req.reason().trim()));
        audit.record("CLOSE", "AdmissionWindow", id, Map.of("reason", req.reason().trim(), "applicantsNotified", notified));
        event(w, AdmissionWindowEvent.Action.CLOSED, req.reason().trim(), previous.isEqual(w.getClosesAt()) ? null : previous, notified, false);
        return get(id);
    }

    /** Takes applications again until a new closing date, as long as selection has not started for the window. */
    @Transactional
    public WindowView reopen(Long id, ReopenRequest req) {
        AdmissionWindow w = find(id);
        OffsetDateTime now = OffsetDateTime.now();
        if (!w.state(now).equals("CLOSED")) throw new BusinessRuleException("INVALID_STATUS", "Only a closed window can be reopened");
        if (!rounds.findByWindowIdOrderByRoundNoDesc(id).isEmpty()) {
            throw new BusinessRuleException("SELECTION_STARTED", "Selection has started for this window, so it cannot take more applications. Open a new window instead.");
        }
        if (!req.closesAt().isAfter(now)) throw new BusinessRuleException("CLOSING_DATE_PAST", "Give a closing date in the future");
        requireProgrammes(id);
        OffsetDateTime previous = w.getClosesAt();
        if (w.getOpensAt().isAfter(now)) w.setOpensAt(now.truncatedTo(ChronoUnit.MICROS));
        w.setClosesAt(req.closesAt());
        w.setStatus(AdmissionWindow.Status.PUBLISHED);
        int notified = Boolean.FALSE.equals(req.notifyApplicants()) ? 0
                : notifyUnsubmitted(w, "ADMISSION_WINDOW_REOPENED", Map.of("window", w.getName(), "deadline", SMS_DATE.format(req.closesAt().atZoneSameInstant(ZONE))));
        audit.record("REOPEN", "AdmissionWindow", id, Map.of("reason", req.reason().trim(), "closesAt", req.closesAt().toString(), "applicantsNotified", notified));
        event(w, AdmissionWindowEvent.Action.REOPENED, req.reason().trim(), previous, notified, false);
        return get(id);
    }

    /** Closes published windows whose closing date has passed (run every few minutes for each institution). */
    @Transactional
    public int closeExpired() {
        List<AdmissionWindow> due = windows.findPastClosingDate(OffsetDateTime.now());
        for (AdmissionWindow w : due) {
            w.setStatus(AdmissionWindow.Status.CLOSED);
            AdmissionWindowEvent e = newEvent(w, AdmissionWindowEvent.Action.CLOSED, "Closing date reached", null, 0);
            e.setAutomatic(true);
            e.setByName("System");
            events.save(e);
        }
        return due.size();
    }

    @Transactional(readOnly = true)
    public List<WindowEventView> events(Long id) {
        find(id);
        return events.findByWindowIdOrderByAtDescIdDesc(id).stream().map(e -> new WindowEventView(e.getId(), e.getAction().name(), e.getReason(),
                e.getOpensAt(), e.getClosesAt(), e.getPreviousClosesAt(), e.getApplicantsNotified(), e.isAutomatic(), e.getByName(), e.getAt())).toList();
    }

    /** Published windows that open later, for the public site. */
    @Transactional(readOnly = true)
    public List<WindowView> upcomingWindows() {
        return windows.findUpcoming(OffsetDateTime.now()).stream().map(w -> WindowView.of(w, null)).toList();
    }

    private int notifyUnsubmitted(AdmissionWindow w, String code, Map<String, ?> values) {
        int n = 0;
        for (Application a : applications.findByWindowIdAndStatusInOrderByReferenceNoAsc(w.getId(),
                List.of(Application.Status.DRAFT, Application.Status.PAYMENT_PENDING))) {
            String phone = a.getApplicant().getPhone();
            if (phone == null) continue;
            messaging.event(code, phone, values);
            n++;
        }
        return n;
    }

    private void event(AdmissionWindow w, AdmissionWindowEvent.Action action, String reason, OffsetDateTime previousCloses, int notified, boolean automatic) {
        AdmissionWindowEvent e = newEvent(w, action, reason, previousCloses, notified);
        e.setAutomatic(automatic);
        e.setByName(CurrentUser.name());
        events.save(e);
    }

    private static AdmissionWindowEvent newEvent(AdmissionWindow w, AdmissionWindowEvent.Action action, String reason, OffsetDateTime previousCloses, int notified) {
        AdmissionWindowEvent e = new AdmissionWindowEvent();
        e.setWindowId(w.getId());
        e.setAction(action);
        e.setReason(reason);
        e.setOpensAt(w.getOpensAt());
        e.setClosesAt(w.getClosesAt());
        e.setPreviousClosesAt(previousCloses);
        e.setApplicantsNotified(notified);
        return e;
    }

    private void requireProgrammes(Long id) {
        if (windowProgrammes.findByWindowIdOrderByIdAsc(id).isEmpty()) {
            throw new BusinessRuleException("NO_PROGRAMMES", "Add at least one programme first");
        }
    }

    @Transactional(readOnly = true)
    public WindowStats stats(Long id) {
        find(id);
        return new WindowStats(id, applications.countByWindowId(id),
                applications.countByWindowIdAndStatus(id, Application.Status.DRAFT),
                applications.countByWindowIdAndStatus(id, Application.Status.PAYMENT_PENDING),
                applications.countByWindowIdAndStatus(id, Application.Status.SUBMITTED));
    }

    private AdmissionWindow find(Long id) {
        return windows.findById(id).orElseThrow(() -> new NotFoundException("Admission window", id));
    }

    private static void requireNotClosed(AdmissionWindow w) {
        if (w.getStatus() == AdmissionWindow.Status.CLOSED) {
            throw new BusinessRuleException("WINDOW_CLOSED", "A closed window cannot be changed");
        }
    }

    private static void checkCapacity(Programme p, int capacity) {
        if (p.getAccreditedCapacity() != null && capacity > p.getAccreditedCapacity()) {
            throw new BusinessRuleException("CAPACITY_EXCEEDED",
                    "Capacity cannot exceed the accredited capacity of " + p.getAccreditedCapacity() + " for " + p.getCode());
        }
    }

    private static void validateRules(EntryRules rules) {
        for (EntryRules.Pathway p : rules.pathways()) {
            for (EntryRules.Condition c : p.conditions()) {
                if (c.type() == null) {
                    throw new BusinessRuleException("INVALID_RULE", "Every condition needs a type");
                }
                boolean needsSubjects = c.type() == EntryRules.ConditionType.CSEE_SUBJECT || c.type() == EntryRules.ConditionType.ACSEE_SUBJECT;
                if (needsSubjects && (c.subjects() == null || c.subjects().isEmpty())) {
                    throw new BusinessRuleException("INVALID_RULE", c.type() + " needs at least one subject");
                }
                if (c.type() == EntryRules.ConditionType.PRIOR_AWARD && (c.family() == null || c.minLevel() == null)) {
                    throw new BusinessRuleException("INVALID_RULE", "PRIOR_AWARD needs family (NTA/NVA) and minLevel");
                }
            }
        }
    }
}
