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
import tz.co.msingi.cims.admission.domain.AdmissionWindow;
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
import java.util.List;
import java.util.Map;

/** Admission window set-up and publication (BP-01). */
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
        AdmissionWindow w = id == null ? new AdmissionWindow() : find(id);
        if (w.getStatus() == AdmissionWindow.Status.CLOSED) {
            throw new BusinessRuleException("WINDOW_CLOSED", "A closed window cannot be changed");
        }
        if (id != null && w.getStatus() == AdmissionWindow.Status.PUBLISHED && applications.countByWindowId(id) > 0
                && w.getApplicationFee().compareTo(req.applicationFee()) != 0) {
            throw new BusinessRuleException("FEE_LOCKED", "The application fee cannot change after applications have started");
        }
        w.setAcademicYear(years.findById(req.academicYearId()).orElseThrow(() -> new NotFoundException("Academic year", req.academicYearId())));
        w.setIntake(intakes.findById(req.intakeId()).orElseThrow(() -> new NotFoundException("Intake", req.intakeId())));
        w.setName(req.name().trim());
        w.setOpensAt(req.opensAt());
        w.setClosesAt(req.closesAt());
        w.setApplicationFee(req.applicationFee());
        w.setMaxChoices(req.maxChoices());
        if (req.offerAcceptanceDays() != null) {
            w.setOfferAcceptanceDays(req.offerAcceptanceDays());
        }
        w.setInstructions(req.instructions());
        w = windows.save(w);
        audit.record(id == null ? "CREATE" : "UPDATE", "AdmissionWindow", w.getId(), Map.of("name", w.getName()));
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

    @Transactional
    public WindowView publish(Long id) {
        AdmissionWindow w = find(id);
        if (w.getStatus() != AdmissionWindow.Status.DRAFT) {
            throw new BusinessRuleException("INVALID_STATUS", "Only a draft window can be published");
        }
        if (windowProgrammes.findByWindowIdOrderByIdAsc(id).isEmpty()) {
            throw new BusinessRuleException("NO_PROGRAMMES", "Add at least one programme before publishing");
        }
        w.setStatus(AdmissionWindow.Status.PUBLISHED);
        audit.record("PUBLISH", "AdmissionWindow", id, null);
        return get(id);
    }

    @Transactional
    public WindowView close(Long id) {
        AdmissionWindow w = find(id);
        w.setStatus(AdmissionWindow.Status.CLOSED);
        audit.record("CLOSE", "AdmissionWindow", id, null);
        return get(id);
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
