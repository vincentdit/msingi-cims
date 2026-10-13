package tz.co.msingi.cims.ipt;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.ModuleRepository;
import tz.co.msingi.cims.academic.curriculum.CurriculumRepositories.SemesterRepository;
import tz.co.msingi.cims.academic.curriculum.Module;
import tz.co.msingi.cims.academic.curriculum.Semester;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.exams.ExamRegistrationQueries;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleOfferingRepository;
import tz.co.msingi.cims.exams.ModuleOffering;
import tz.co.msingi.cims.exams.OfferingService;
import tz.co.msingi.cims.ipt.IptDtos.AssessmentRequest;
import tz.co.msingi.cims.ipt.IptDtos.DecisionRequest;
import tz.co.msingi.cims.ipt.IptDtos.EligibleStudent;
import tz.co.msingi.cims.ipt.IptDtos.LogbookRequest;
import tz.co.msingi.cims.ipt.IptDtos.LogbookView;
import tz.co.msingi.cims.ipt.IptDtos.MyIpt;
import tz.co.msingi.cims.ipt.IptDtos.OrganisationOption;
import tz.co.msingi.cims.ipt.IptDtos.OrganisationRequest;
import tz.co.msingi.cims.ipt.IptDtos.OrganisationView;
import tz.co.msingi.cims.ipt.IptDtos.PeriodRequest;
import tz.co.msingi.cims.ipt.IptDtos.PeriodView;
import tz.co.msingi.cims.ipt.IptDtos.Permissions;
import tz.co.msingi.cims.ipt.IptDtos.PlacementRequest;
import tz.co.msingi.cims.ipt.IptDtos.PlacementSummary;
import tz.co.msingi.cims.ipt.IptDtos.PlacementUpdate;
import tz.co.msingi.cims.ipt.IptDtos.PlacementView;
import tz.co.msingi.cims.ipt.IptDtos.ReviewRequest;
import tz.co.msingi.cims.ipt.IptDtos.ScoreView;
import tz.co.msingi.cims.ipt.IptDtos.StaffOption;
import tz.co.msingi.cims.ipt.IptDtos.StudentRequest;
import tz.co.msingi.cims.ipt.IptDtos.TransferResult;
import tz.co.msingi.cims.ipt.IptDtos.VisitRequest;
import tz.co.msingi.cims.ipt.IptDtos.VisitView;
import tz.co.msingi.cims.ipt.IptRepositories.AssessmentRepository;
import tz.co.msingi.cims.ipt.IptRepositories.LogbookRepository;
import tz.co.msingi.cims.ipt.IptRepositories.OrganisationRepository;
import tz.co.msingi.cims.ipt.IptRepositories.PeriodRepository;
import tz.co.msingi.cims.ipt.IptRepositories.PlacementRepository;
import tz.co.msingi.cims.ipt.IptRepositories.VisitRepository;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.numbering.NumberSequenceService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;
import tz.co.msingi.cims.registry.semester.ModuleRegistration;
import tz.co.msingi.cims.registry.semester.SemesterRegistration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Industrial practical training (FR-IPT-01..03, BP-13): organisations, periods, placements requested by students or assigned by the
 * IPT Coordinator, placement letters, weekly logbooks reviewed by the institution supervisor, supervision visits, the three marks
 * (workplace supervisor, institution supervisor, logbook) and their transfer to the IPT module's marks, which then go through the
 * usual approval of results (BP-08).
 */
@Service
@RequiredArgsConstructor
public class IptService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    static final Role[] COORDINATORS = {Role.INSTITUTION_ADMIN, Role.IPT_COORDINATOR};
    static final Role[] VIEWERS = {Role.INSTITUTION_ADMIN, Role.IPT_COORDINATOR, Role.REGISTRAR, Role.DEPUTY_PRINCIPAL_ACADEMIC, Role.HEAD_OF_DEPARTMENT,
            Role.EXAMINATIONS_OFFICER, Role.MANAGEMENT_VIEWER};
    private static final Set<IptPlacement.Status> LIVE = EnumSet.of(IptPlacement.Status.REQUESTED, IptPlacement.Status.CONFIRMED, IptPlacement.Status.COMPLETED);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final tz.co.msingi.cims.platform.security.DataScope scope;
    private final OrganisationRepository organisations;
    private final PeriodRepository periods;
    private final PlacementRepository placements;
    private final LogbookRepository logbook;
    private final VisitRepository visits;
    private final AssessmentRepository assessments;
    private final SemesterRepository semesters;
    private final ModuleRepository modules;
    private final ExamRegistrationQueries registrations;
    private final ModuleOfferingRepository offerings;
    private final OfferingService offeringService;
    private final StudentRepository students;
    private final UserAccountRepository users;
    private final TenantRepository tenants;
    private final NumberSequenceService sequences;
    private final ExamAccess access;
    private final Messaging messaging;
    private final AuditService audit;

    // ------------------------------------------------------------------ organisations (FR-IPT-01)

    @Transactional(readOnly = true)
    public List<OrganisationView> organisations() {
        access.require("see placement organisations", VIEWERS);
        Map<Long, Long> counts = placements.findAll().stream().filter(IptPlacement::live)
                .collect(Collectors.groupingBy(p -> p.getOrganisation().getId(), Collectors.counting()));
        return organisations.findAllByOrderByNameAsc().stream().map(o -> orgView(o, counts.getOrDefault(o.getId(), 0L).intValue())).toList();
    }

    @Transactional
    public OrganisationView saveOrganisation(Long id, OrganisationRequest req) {
        access.require("manage placement organisations", COORDINATORS);
        PlacementOrganisation o = id == null ? new PlacementOrganisation() : organisations.findById(id).orElseThrow(() -> new NotFoundException("Organisation", id));
        organisations.findFirstByNameIgnoreCase(req.name().trim()).filter(x -> !x.getId().equals(o.getId())).ifPresent(x -> {
            throw new BusinessRuleException("DUPLICATE_ORGANISATION", req.name().trim() + " is already registered");
        });
        o.setName(req.name().trim());
        o.setSector(blank(req.sector()));
        o.setRegion(blank(req.region()));
        o.setDistrict(blank(req.district()));
        o.setAddress(blank(req.address()));
        o.setContactName(blank(req.contactName()));
        o.setContactTitle(blank(req.contactTitle()));
        o.setContactPhone(blank(req.contactPhone()));
        o.setContactEmail(blank(req.contactEmail()));
        o.setCapacity(req.capacity());
        o.setActive(req.active() == null || req.active());
        o.setProposedByStudent(false);                                     // checked by the coordinator from now on
        organisations.saveAndFlush(o);
        audit.record(id == null ? "IPT_ORGANISATION_CREATED" : "IPT_ORGANISATION_UPDATED", "PlacementOrganisation", o.getId(), Map.of("name", o.getName()));
        return orgView(o, 0);
    }

    // ------------------------------------------------------------------ periods

    @Transactional(readOnly = true)
    public List<PeriodView> periods() {
        access.require("see IPT periods", VIEWERS);
        return periods.findAllByOrderByStartsOnDesc().stream().map(this::periodView).toList();
    }

    @Transactional
    public PeriodView savePeriod(Long id, PeriodRequest req) {
        access.require("manage IPT periods", COORDINATORS);
        requireWholeInstitution("set up IPT periods");
        if (req.endsOn().isBefore(req.startsOn())) throw new BusinessRuleException("INVALID_DATES", "The period must end after it starts");
        if (req.workplaceWeight().add(req.institutionWeight()).add(req.logbookWeight()).compareTo(BigDecimal.valueOf(100)) != 0) {
            throw new BusinessRuleException("INVALID_WEIGHTS", "The three weights must add up to 100");
        }
        Semester sem = semesters.findById(req.semesterId()).orElseThrow(() -> new NotFoundException("Semester", req.semesterId()));
        Module mod = req.moduleId() == null ? null : modules.findById(req.moduleId()).orElseThrow(() -> new NotFoundException("Module", req.moduleId()));
        IptPeriod p = id == null ? new IptPeriod() : periods.findById(id).orElseThrow(() -> new NotFoundException("IPT period", id));
        if (id == null) p.setCreatedByName(CurrentUser.name());
        p.setName(req.name().trim());
        p.setSemester(sem);
        p.setModule(mod);
        p.setStartsOn(req.startsOn());
        p.setEndsOn(req.endsOn());
        p.setRequestsClose(req.requestsClose());
        p.setWorkplaceWeight(req.workplaceWeight());
        p.setInstitutionWeight(req.institutionWeight());
        p.setLogbookWeight(req.logbookWeight());
        p.setMinLogbookWeeks(req.minLogbookWeeks() == null ? 1 : req.minLogbookWeeks().shortValue());
        periods.saveAndFlush(p);
        audit.record(id == null ? "IPT_PERIOD_CREATED" : "IPT_PERIOD_UPDATED", "IptPeriod", p.getId(), Map.of("name", p.getName(),
                "module", mod == null ? "—" : mod.getCode()));
        return periodView(p);
    }

    /** Students who should be placed in a period: those registered for its IPT module, or every active student when it has none. */
    @Transactional(readOnly = true)
    public List<EligibleStudent> eligible(Long periodId) {
        access.require("see IPT students", VIEWERS);
        IptPeriod p = period(periodId);
        Map<Long, IptPlacement> live = placements.findByPeriodIdOrderByRequestedAtAsc(p.getId()).stream().filter(IptPlacement::live)
                .collect(Collectors.toMap(IptPlacement::getStudentId, Function.identity(), (a, b) -> a));
        var mine = scope.current();
        return eligibleStudents(p).stream().filter(s -> mine.programme(s.getProgramme())).sorted(Comparator.comparing(Student::getRegistrationNo)).map(s -> {
            IptPlacement pl = live.get(s.getId());
            return new EligibleStudent(s.getId(), s.getRegistrationNo(), s.fullName(), s.getProgramme().getCode(), s.getYearOfStudy(), s.getPhone(),
                    pl == null ? null : pl.getId(), pl == null ? null : pl.getStatus().name());
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<StaffOption> staffOptions() {
        access.require("list supervisors", COORDINATORS);
        return users.findAll().stream().filter(u -> u.getUserType() == UserAccount.UserType.STAFF && u.getStatus() == UserAccount.Status.ACTIVE)
                .sorted(Comparator.comparing(UserAccount::getFullName))
                .map(u -> new StaffOption(u.getId(), u.getFullName(), u.getRoles().stream().map(r -> r.getRole().name()).distinct().sorted().toList())).toList();
    }

    // ------------------------------------------------------------------ placements (FR-IPT-02)

    @Transactional(readOnly = true)
    public List<PlacementSummary> placements(Long periodId) {
        access.require("see IPT placements", VIEWERS);
        var mine = scope.current();
        List<IptPlacement> list = placements.findByPeriodIdOrderByRequestedAtAsc(period(periodId).getId());
        if (!mine.all()) {
            Map<Long, Student> ss = students.findAllById(list.stream().map(IptPlacement::getStudentId).collect(Collectors.toSet())).stream()
                    .collect(Collectors.toMap(Student::getId, Function.identity()));
            list = list.stream().filter(pl -> mine.programme(ss.get(pl.getStudentId()).getProgramme())).toList();
        }
        return summaries(list);
    }

    /** The placements the signed-in staff member supervises for the institution. */
    @Transactional(readOnly = true)
    public List<PlacementSummary> supervised() {
        return summaries(placements.findByInstitutionSupervisorIdOrderByStartsOnDesc(access.me()).stream().filter(IptPlacement::live).toList());
    }

    @Transactional
    public PlacementView assign(PlacementRequest req) {
        access.require("place students", COORDINATORS);
        IptPeriod p = period(req.periodId());
        Student s = students.findById(req.studentId()).orElseThrow(() -> new NotFoundException("Student", req.studentId()));
        if (!scope.current().programme(s.getProgramme())) throw new AccessDeniedException("That student is outside " + scope.current().describe());
        requireEligible(p, s);
        PlacementOrganisation o = organisations.findById(req.organisationId()).orElseThrow(() -> new NotFoundException("Organisation", req.organisationId()));
        IptPlacement pl = new IptPlacement();
        pl.setPeriod(p);
        pl.setStudentId(s.getId());
        pl.setOrganisation(o);
        pl.setStartsOn(req.startsOn() == null ? p.getStartsOn() : req.startsOn());
        pl.setEndsOn(req.endsOn() == null ? p.getEndsOn() : req.endsOn());
        pl.setUnit(blank(req.unit()));
        pl.setWorkplaceSupervisorName(blank(req.workplaceSupervisorName()));
        pl.setWorkplaceSupervisorTitle(blank(req.workplaceSupervisorTitle()));
        pl.setWorkplaceSupervisorPhone(blank(req.workplaceSupervisorPhone()));
        pl.setWorkplaceSupervisorEmail(blank(req.workplaceSupervisorEmail()));
        pl.setInstitutionSupervisorId(staffId(req.institutionSupervisorId()));
        checkDates(p, pl);
        saveNew(pl);
        confirm(pl, s, null);
        return view(pl);
    }

    @Transactional
    public PlacementView update(Long id, PlacementUpdate req) {
        access.require("change placements", COORDINATORS);
        IptPlacement pl = placement(id);
        if (!inScope(pl)) throw new AccessDeniedException("That placement is outside " + scope.current().describe());
        if (!pl.live()) throw new BusinessRuleException("PLACEMENT_CLOSED", "This placement was cancelled or declined");
        PlacementOrganisation o = organisations.findById(req.organisationId()).orElseThrow(() -> new NotFoundException("Organisation", req.organisationId()));
        boolean moved = !o.getId().equals(pl.getOrganisation().getId());
        pl.setOrganisation(o);
        pl.setStartsOn(req.startsOn());
        pl.setEndsOn(req.endsOn());
        pl.setUnit(blank(req.unit()));
        pl.setWorkplaceSupervisorName(blank(req.workplaceSupervisorName()));
        pl.setWorkplaceSupervisorTitle(blank(req.workplaceSupervisorTitle()));
        pl.setWorkplaceSupervisorPhone(blank(req.workplaceSupervisorPhone()));
        pl.setWorkplaceSupervisorEmail(blank(req.workplaceSupervisorEmail()));
        pl.setInstitutionSupervisorId(staffId(req.institutionSupervisorId()));
        checkDates(pl.getPeriod(), pl);
        if (moved && pl.getLetterNo() != null) pl.setLetterNo(null);           // a new letter for the new organisation
        if (moved && pl.getStatus() != IptPlacement.Status.REQUESTED) issueLetter(pl);
        audit.record("IPT_PLACEMENT_UPDATED", "IptPlacement", pl.getId(), Map.of("organisation", o.getName()));
        return view(pl);
    }

    @Transactional
    public PlacementView decide(Long id, DecisionRequest req) {
        access.require("decide placement requests", COORDINATORS);
        IptPlacement pl = placement(id);
        if (!inScope(pl)) throw new AccessDeniedException("That placement is outside " + scope.current().describe());
        if (pl.getStatus() != IptPlacement.Status.REQUESTED) throw new BusinessRuleException("NOT_REQUESTED", "This placement is not waiting for a decision");
        Student s = students.findById(pl.getStudentId()).orElseThrow();
        if (Boolean.TRUE.equals(req.confirm())) {
            if (req.institutionSupervisorId() != null) pl.setInstitutionSupervisorId(staffId(req.institutionSupervisorId()));
            confirm(pl, s, blank(req.note()));
        } else {
            if (blank(req.note()) == null) throw new BusinessRuleException("NOTE_REQUIRED", "Tell the student why the request is declined");
            pl.setStatus(IptPlacement.Status.DECLINED);
            pl.setDecisionNote(req.note().trim());
            messaging.event("IPT_PLACEMENT_DECLINED", s.getPhone(), Map.of("organisation", pl.getOrganisation().getName(), "reason", pl.getDecisionNote()));
            audit.record("IPT_PLACEMENT_DECLINED", "IptPlacement", pl.getId(), Map.of("student", s.getRegistrationNo(), "note", pl.getDecisionNote()));
        }
        return view(pl);
    }

    @Transactional
    public PlacementView cancel(Long id, String reason) {
        access.require("cancel placements", COORDINATORS);
        IptPlacement pl = placement(id);
        if (!inScope(pl)) throw new AccessDeniedException("That placement is outside " + scope.current().describe());
        if (pl.getTransferredAt() != null) throw new BusinessRuleException("RESULT_TRANSFERRED", "The IPT mark has gone to the examination record");
        if (!pl.live()) throw new BusinessRuleException("PLACEMENT_CLOSED", "This placement is already closed");
        pl.setStatus(IptPlacement.Status.CANCELLED);
        pl.setDecisionNote(reason.trim());
        audit.record("IPT_PLACEMENT_CANCELLED", "IptPlacement", pl.getId(), Map.of("reason", pl.getDecisionNote()));
        return view(pl);
    }

    private void confirm(IptPlacement pl, Student s, String note) {
        PlacementOrganisation o = pl.getOrganisation();
        if (o.getCapacity() != null && placements.countLive(pl.getPeriod().getId(), o.getId(),
                EnumSet.of(IptPlacement.Status.CONFIRMED, IptPlacement.Status.COMPLETED)) >= o.getCapacity()) {
            throw new BusinessRuleException("ORGANISATION_FULL", o.getName() + " takes " + o.getCapacity() + " student(s) per period and is full");
        }
        pl.setStatus(IptPlacement.Status.CONFIRMED);
        pl.setConfirmedByName(CurrentUser.name());
        pl.setConfirmedAt(OffsetDateTime.now());
        pl.setDecisionNote(note);
        o.setProposedByStudent(false);
        issueLetter(pl);
        messaging.event("IPT_PLACEMENT_CONFIRMED", s.getPhone(), Map.of("organisation", o.getName(), "start", DATE.format(pl.getStartsOn())));
        audit.record("IPT_PLACEMENT_CONFIRMED", "IptPlacement", pl.getId(), Map.of("student", s.getRegistrationNo(), "organisation", o.getName(),
                "letter", pl.getLetterNo()));
    }

    private void issueLetter(IptPlacement pl) {
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        int y = Year.now(ZONE).getValue();
        pl.setLetterNo(NumberSequenceService.format(t.getCode() + "/IPT/" + y + "/", sequences.next("IPT-LETTER-" + y), 5));
        pl.setLetterIssuedAt(OffsetDateTime.now());
    }

    // ------------------------------------------------------------------ one placement

    @Transactional(readOnly = true)
    public PlacementView get(Long id) {
        IptPlacement pl = placement(id);
        if (!(access.has(VIEWERS) && inScope(pl)) && !supervises(pl)) throw new AccessDeniedException("Not your placement");
        return view(pl);
    }

    /** The placement letter for the organisation (BP-13 step 1). */
    @Transactional(readOnly = true)
    public PlacementLetter.Download letter(Long id) {
        IptPlacement pl = placement(id);
        if (!(access.has(VIEWERS) && inScope(pl)) && !supervises(pl)) throw new AccessDeniedException("Not your placement");
        return letterOf(pl);
    }

    @Transactional(readOnly = true)
    public PlacementLetter.Download myLetter() {
        IptPlacement pl = myCurrent().orElseThrow(() -> new NotFoundException("You have no placement"));
        return letterOf(pl);
    }

    private PlacementLetter.Download letterOf(IptPlacement pl) {
        if (pl.getLetterNo() == null) throw new BusinessRuleException("NO_LETTER", "The letter is issued once the placement is confirmed");
        Student s = students.findById(pl.getStudentId()).orElseThrow();
        Tenant t = tenants.findById(TenantContext.requireTenantId()).orElseThrow();
        String supervisor = pl.getInstitutionSupervisorId() == null ? null : users.findById(pl.getInstitutionSupervisorId()).map(UserAccount::getFullName).orElse(null);
        return PlacementLetter.render(t, pl, s, supervisor);
    }

    // ------------------------------------------------------------------ logbook (FR-IPT-03)

    @Transactional
    public PlacementView writeLogbook(LogbookRequest req) {
        Student s = me();
        IptPlacement pl = myCurrent().filter(x -> x.getStatus() == IptPlacement.Status.CONFIRMED)
                .orElseThrow(() -> new BusinessRuleException("NO_CONFIRMED_PLACEMENT", "The logbook opens once your placement is confirmed"));
        LocalDate today = LocalDate.now(ZONE);
        if (req.weekStarting().isAfter(today)) throw new BusinessRuleException("FUTURE_WEEK", "Write each week's entry during or after that week");
        if (req.weekStarting().isBefore(pl.getStartsOn().minusDays(6)) || req.weekStarting().isAfter(pl.getEndsOn())) {
            throw new BusinessRuleException("OUTSIDE_PLACEMENT", "That week is outside your placement (" + DATE.format(pl.getStartsOn()) + " to " + DATE.format(pl.getEndsOn()) + ")");
        }
        LogbookEntry e = logbook.findByPlacementIdAndWeekNo(pl.getId(), req.weekNo().shortValue()).orElse(null);
        if (e != null && (e.getStatus() == LogbookEntry.Status.SUBMITTED || e.getStatus() == LogbookEntry.Status.APPROVED)) {
            throw new BusinessRuleException("ENTRY_LOCKED", "Week " + req.weekNo() + " has been submitted; it can change only if your supervisor returns it");
        }
        if (e == null) {
            e = new LogbookEntry();
            e.setPlacement(pl);
            e.setWeekNo(req.weekNo().shortValue());
        }
        e.setWeekStarting(req.weekStarting());
        e.setActivities(req.activities().trim());
        e.setSkills(blank(req.skills()));
        e.setChallenges(blank(req.challenges()));
        e.setHours(req.hours());
        e.setStatus(req.submit() ? LogbookEntry.Status.SUBMITTED : LogbookEntry.Status.DRAFT);
        if (req.submit()) e.setSubmittedAt(OffsetDateTime.now());
        logbook.saveAndFlush(e);
        audit.record(req.submit() ? "IPT_LOGBOOK_SUBMITTED" : "IPT_LOGBOOK_SAVED", "LogbookEntry", e.getId(), Map.of("student", s.getRegistrationNo(),
                "week", req.weekNo()));
        return view(pl);
    }

    @Transactional
    public LogbookView review(Long entryId, ReviewRequest req) {
        LogbookEntry e = logbook.findById(entryId).orElseThrow(() -> new NotFoundException("Logbook entry", entryId));
        requireSupervisor(e.getPlacement());
        if (e.getStatus() != LogbookEntry.Status.SUBMITTED) throw new BusinessRuleException("NOT_SUBMITTED", "This week has not been submitted");
        if (!req.approve() && blank(req.comment()) == null) throw new BusinessRuleException("COMMENT_REQUIRED", "Tell the student what to improve");
        e.setStatus(req.approve() ? LogbookEntry.Status.APPROVED : LogbookEntry.Status.RETURNED);
        e.setReviewerComment(blank(req.comment()));
        e.setReviewedByName(CurrentUser.name());
        e.setReviewedAt(OffsetDateTime.now());
        if (!req.approve()) {
            Student s = students.findById(e.getPlacement().getStudentId()).orElseThrow();
            messaging.event("IPT_LOGBOOK_RETURNED", s.getPhone(), Map.of("week", String.valueOf(e.getWeekNo()), "reason", e.getReviewerComment()));
        }
        audit.record(req.approve() ? "IPT_LOGBOOK_APPROVED" : "IPT_LOGBOOK_RETURNED", "LogbookEntry", e.getId(), Map.of("week", e.getWeekNo()));
        return logView(e);
    }

    // ------------------------------------------------------------------ supervision and assessment

    @Transactional
    public PlacementView visit(Long placementId, VisitRequest req) {
        IptPlacement pl = placement(placementId);
        requireSupervisor(pl);
        if (req.visitedOn().isAfter(LocalDate.now(ZONE))) throw new BusinessRuleException("FUTURE_VISIT", "Record a visit after it happened");
        SupervisionVisit v = new SupervisionVisit();
        v.setPlacement(pl);
        v.setVisitedOn(req.visitedOn());
        v.setMode(req.mode());
        v.setNotes(req.notes().trim());
        v.setRecordedByName(CurrentUser.name());
        visits.save(v);
        audit.record("IPT_VISIT_RECORDED", "IptPlacement", pl.getId(), Map.of("date", req.visitedOn().toString(), "mode", req.mode().name()));
        return view(pl);
    }

    /** Records one of the three marks; with all three the weighted total is worked out and the placement completed. */
    @Transactional
    public PlacementView assess(Long placementId, AssessmentRequest req) {
        IptPlacement pl = placement(placementId);
        requireSupervisor(pl);
        if (pl.getStatus() != IptPlacement.Status.CONFIRMED && pl.getStatus() != IptPlacement.Status.COMPLETED) {
            throw new BusinessRuleException("NOT_PLACED", "Only confirmed placements are assessed");
        }
        if (req.kind() == IptAssessment.Kind.LOGBOOK) {
            long approved = logbook.findByPlacementIdOrderByWeekNoAsc(pl.getId()).stream().filter(x -> x.getStatus() == LogbookEntry.Status.APPROVED).count();
            if (approved < pl.getPeriod().getMinLogbookWeeks()) {
                throw new BusinessRuleException("LOGBOOK_INCOMPLETE", "Approve at least " + pl.getPeriod().getMinLogbookWeeks() + " logbook week(s) before marking it ("
                        + approved + " so far)");
            }
        }
        IptAssessment a = assessments.findByPlacementIdAndKind(pl.getId(), req.kind()).orElse(null);
        if (a == null) {
            a = new IptAssessment();
            a.setPlacement(pl);
            a.setKind(req.kind());
        }
        a.setScore(req.score().setScale(2, RoundingMode.HALF_UP));
        a.setComments(blank(req.comments()));
        a.setAssessedByName(CurrentUser.name());
        a.setAssessedAt(OffsetDateTime.now());
        assessments.saveAndFlush(a);
        Map<IptAssessment.Kind, BigDecimal> by = assessments.findByPlacementId(pl.getId()).stream()
                .collect(Collectors.toMap(IptAssessment::getKind, IptAssessment::getScore));
        BigDecimal before = pl.getTotalMark();
        if (by.size() == 3) {
            IptPeriod p = pl.getPeriod();
            pl.setTotalMark(by.get(IptAssessment.Kind.WORKPLACE).multiply(p.getWorkplaceWeight())
                    .add(by.get(IptAssessment.Kind.INSTITUTION).multiply(p.getInstitutionWeight()))
                    .add(by.get(IptAssessment.Kind.LOGBOOK).multiply(p.getLogbookWeight()))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).setScale(2));
            pl.setStatus(IptPlacement.Status.COMPLETED);
        }
        if (!Objects.equals(before, pl.getTotalMark())) pl.setTransferredAt(null);   // the examination record needs the new mark
        audit.record("IPT_ASSESSED", "IptPlacement", pl.getId(), Map.of("kind", req.kind().name(), "score", a.getScore()));
        return view(pl);
    }

    /** Sends the IPT marks of completed placements to the IPT module's marks (BP-13 step 4); results then follow BP-08. */
    @Transactional
    public TransferResult transfer(Long periodId) {
        access.require("transfer IPT results", COORDINATORS);
        requireWholeInstitution("transfer IPT results to the examination record");
        IptPeriod p = period(periodId);
        if (p.getModule() == null) throw new BusinessRuleException("NO_IPT_MODULE", "Link the period to its IPT module first");
        ModuleOffering o = offerings.findBySemesterIdAndModuleId(p.getSemester().getId(), p.getModule().getId()).orElseThrow(() ->
                new BusinessRuleException("NO_OFFERING", p.getModule().getCode() + " is not set up for " + p.getSemester().getName()
                        + " yet (Marks › Set up modules from registrations)"));
        Map<Long, Long> regByStudent = registrations.students(p.getSemester().getId(), p.getModule().getId(), SemesterRegistration.Status.APPROVED).stream()
                .collect(Collectors.toMap(ModuleRegistration::getStudentId, ModuleRegistration::getId, (a, b) -> a));
        Map<Long, BigDecimal> marks = new HashMap<>();
        List<IptPlacement> sent = new ArrayList<>();
        List<String> notReady = new ArrayList<>();
        Map<Long, Student> ss = students.findAllById(placements.findByPeriodIdOrderByRequestedAtAsc(p.getId()).stream().map(IptPlacement::getStudentId).toList())
                .stream().collect(Collectors.toMap(Student::getId, Function.identity()));
        for (IptPlacement pl : placements.findByPeriodIdOrderByRequestedAtAsc(p.getId())) {
            if (!pl.live()) continue;
            Student s = ss.get(pl.getStudentId());
            if (pl.getTotalMark() == null) {
                notReady.add(s.getRegistrationNo() + ": " + (3 - assessments.findByPlacementId(pl.getId()).size()) + " mark(s) missing");
                continue;
            }
            Long reg = regByStudent.get(pl.getStudentId());
            if (reg == null) {
                notReady.add(s.getRegistrationNo() + ": not registered for " + p.getModule().getCode());
                continue;
            }
            marks.put(reg, pl.getTotalMark());
            sent.add(pl);
        }
        if (marks.isEmpty()) throw new BusinessRuleException("NOTHING_TO_TRANSFER", "No placement has all three marks yet");
        int n = offeringService.transferMarks(o.getId(), marks, "IPT");
        OffsetDateTime now = OffsetDateTime.now();
        sent.forEach(pl -> pl.setTransferredAt(now));
        p.setResultsTransferredAt(now);
        p.setResultsTransferredBy(CurrentUser.name());
        audit.record("IPT_RESULTS_TRANSFERRED", "IptPeriod", p.getId(), Map.of("module", p.getModule().getCode(), "students", n));
        return new TransferResult(n, notReady, p.getModule().getCode());
    }

    // ------------------------------------------------------------------ student portal (FR-STU-04)

    @Transactional(readOnly = true)
    public MyIpt mine() {
        Student s = me();
        List<PeriodView> open = periods.findAllByOrderByStartsOnDesc().stream().filter(p -> !p.getEndsOn().isBefore(LocalDate.now(ZONE)))
                .filter(p -> isEligible(p, s)).map(this::periodView).toList();
        List<OrganisationOption> orgs = organisations.findAllByOrderByNameAsc().stream().filter(PlacementOrganisation::isActive)
                .map(o -> new OrganisationOption(o.getId(), o.getName(), o.getRegion())).toList();
        Optional<IptPlacement> current = myCurrent();
        List<IptPlacement> all = placements.findByStudentIdOrderByRequestedAtDesc(s.getId());
        return new MyIpt(open, orgs, current.map(this::view).orElse(null),
                summaries(all.stream().filter(x -> current.isEmpty() || !x.getId().equals(current.get().getId())).toList()));
    }

    @Transactional
    public MyIpt request(StudentRequest req) {
        Student s = me();
        IptPeriod p = period(req.periodId());
        requireEligible(p, s);
        LocalDate today = LocalDate.now(ZONE);
        if ((p.getRequestsClose() != null && today.isAfter(p.getRequestsClose())) || today.isAfter(p.getEndsOn())) {
            throw new BusinessRuleException("REQUESTS_CLOSED", "Placement requests for " + p.getName() + " have closed. See the IPT Coordinator.");
        }
        PlacementOrganisation o;
        if (req.organisationId() != null) {
            o = organisations.findById(req.organisationId()).filter(PlacementOrganisation::isActive)
                    .orElseThrow(() -> new NotFoundException("Organisation", req.organisationId()));
        } else {
            String name = blank(req.newOrganisationName());
            if (name == null) throw new BusinessRuleException("ORGANISATION_REQUIRED", "Choose an organisation or give the name of a new one");
            o = organisations.findFirstByNameIgnoreCase(name).orElseGet(() -> {
                PlacementOrganisation n = new PlacementOrganisation();
                n.setName(name);
                n.setRegion(blank(req.newOrganisationRegion()));
                n.setContactName(blank(req.newOrganisationContact()));
                n.setContactPhone(blank(req.newOrganisationPhone()));
                n.setProposedByStudent(true);
                return organisations.saveAndFlush(n);
            });
        }
        IptPlacement pl = new IptPlacement();
        pl.setPeriod(p);
        pl.setStudentId(s.getId());
        pl.setOrganisation(o);
        pl.setStartsOn(req.startsOn() == null ? p.getStartsOn() : req.startsOn());
        pl.setEndsOn(req.endsOn() == null ? p.getEndsOn() : req.endsOn());
        pl.setUnit(blank(req.unit()));
        pl.setWorkplaceSupervisorName(blank(req.workplaceSupervisorName()));
        pl.setWorkplaceSupervisorPhone(blank(req.workplaceSupervisorPhone()));
        pl.setStudentNote(blank(req.note()));
        checkDates(p, pl);
        saveNew(pl);
        audit.record("IPT_PLACEMENT_REQUESTED", "IptPlacement", pl.getId(), Map.of("student", s.getRegistrationNo(), "organisation", o.getName()));
        return mine();
    }

    // ------------------------------------------------------------------ helpers

    private void saveNew(IptPlacement pl) {
        try {
            placements.saveAndFlush(pl);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessRuleException("ALREADY_PLACED", "The student already has a placement for this period");
        }
    }

    private static void checkDates(IptPeriod p, IptPlacement pl) {
        if (pl.getEndsOn().isBefore(pl.getStartsOn())) throw new BusinessRuleException("INVALID_DATES", "The placement must end after it starts");
        if (pl.getStartsOn().isBefore(p.getStartsOn().minusDays(14)) || pl.getEndsOn().isAfter(p.getEndsOn().plusDays(14))) {
            throw new BusinessRuleException("OUTSIDE_PERIOD", "Placement dates must fall within " + p.getName() + " (" + DATE.format(p.getStartsOn()) + " to "
                    + DATE.format(p.getEndsOn()) + ")");
        }
    }

    private List<Student> eligibleStudents(IptPeriod p) {
        if (p.getModule() == null) return students.findAll().stream().filter(s -> s.getStatus() == Student.Status.ACTIVE).toList();
        return registrations.students(p.getSemester().getId(), p.getModule().getId(), SemesterRegistration.Status.APPROVED).stream()
                .map(m -> m.getRegistration().getStudent()).toList();
    }

    private boolean isEligible(IptPeriod p, Student s) {
        if (p.getModule() == null) return s.getStatus() == Student.Status.ACTIVE;
        return registrations.students(p.getSemester().getId(), p.getModule().getId(), SemesterRegistration.Status.APPROVED).stream()
                .anyMatch(m -> m.getStudentId().equals(s.getId()));
    }

    private void requireEligible(IptPeriod p, Student s) {
        if (!isEligible(p, s)) {
            throw new BusinessRuleException("NOT_ELIGIBLE", s.getRegistrationNo() + (p.getModule() == null ? " is not an active student"
                    : " is not registered for " + p.getModule().getCode() + " in " + p.getSemester().getName()));
        }
    }

    private Long staffId(Long id) {
        if (id == null) return null;
        UserAccount u = users.findById(id).orElseThrow(() -> new NotFoundException("Staff member", id));
        if (u.getUserType() != UserAccount.UserType.STAFF) throw new BusinessRuleException("UNKNOWN_STAFF", "Choose a staff member as the institution supervisor");
        return u.getId();
    }

    private boolean supervises(IptPlacement pl) {
        Long me = CurrentUser.id().orElse(null);
        return me != null && me.equals(pl.getInstitutionSupervisorId());
    }

    private void requireSupervisor(IptPlacement pl) {
        if (!supervises(pl) && !access.has(COORDINATORS)) throw new AccessDeniedException("Only the student's institution supervisor or the IPT Coordinator does this");
    }

    private Optional<IptPlacement> myCurrent() {
        Student s = me();
        return placements.findByStudentIdOrderByRequestedAtDesc(s.getId()).stream().filter(IptPlacement::live).findFirst();
    }

    private IptPeriod period(Long id) {
        return periods.findById(id).orElseThrow(() -> new NotFoundException("IPT period", id));
    }

    private IptPlacement placement(Long id) {
        return placements.findById(id).orElseThrow(() -> new NotFoundException("Placement", id));
    }

    private Student me() {
        return students.findByUserId(CurrentUser.requireId()).orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ------------------------------------------------------------------ views

    private PeriodView periodView(IptPeriod p) {
        List<IptPlacement> ps = placements.findByPeriodIdOrderByRequestedAtAsc(p.getId());
        LocalDate today = LocalDate.now(ZONE);
        return new PeriodView(p.getId(), p.getName(), p.getSemester().getId(), p.getSemester().getName() + " " + p.getSemester().getAcademicYear().getCode(),
                p.getModule() == null ? null : p.getModule().getId(), p.getModule() == null ? null : p.getModule().getCode(),
                p.getModule() == null ? null : p.getModule().getTitle(), p.getStartsOn(), p.getEndsOn(), p.getRequestsClose(), p.getWorkplaceWeight(),
                p.getInstitutionWeight(), p.getLogbookWeight(), p.getMinLogbookWeeks(), eligibleStudents(p).size(),
                (int) ps.stream().filter(x -> x.getStatus() == IptPlacement.Status.REQUESTED).count(),
                (int) ps.stream().filter(x -> x.getStatus() == IptPlacement.Status.CONFIRMED).count(),
                (int) ps.stream().filter(x -> x.getStatus() == IptPlacement.Status.COMPLETED).count(), p.getResultsTransferredAt(), p.getResultsTransferredBy(),
                (p.getRequestsClose() == null || !today.isAfter(p.getRequestsClose())) && !today.isAfter(p.getEndsOn()));
    }

    /** A department's IPT Coordinator places that department's students; periods and transfers are for the whole institution. */
    private void requireWholeInstitution(String what) {
        if (!scope.current().all()) throw new AccessDeniedException("Only an IPT Coordinator for the whole institution can " + what);
    }

    /** A Head of Department (or a department's IPT Coordinator) sees the placements of their department's students. */
    private boolean inScope(IptPlacement pl) {
        var mine = scope.current();
        return mine.all() || students.findById(pl.getStudentId()).map(st -> mine.programme(st.getProgramme())).orElse(false);
    }

    private List<PlacementSummary> summaries(List<IptPlacement> list) {
        if (list.isEmpty()) return List.of();
        List<Long> ids = list.stream().map(IptPlacement::getId).toList();
        Map<Long, List<LogbookEntry>> logs = logbook.findByPlacementIdIn(ids).stream().collect(Collectors.groupingBy(e -> e.getPlacement().getId()));
        Map<Long, Long> scores = assessments.findByPlacementIdIn(ids).stream().collect(Collectors.groupingBy(a -> a.getPlacement().getId(), Collectors.counting()));
        Map<Long, Student> ss = students.findAllById(list.stream().map(IptPlacement::getStudentId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        Map<Long, String> sup = users.findAllById(list.stream().map(IptPlacement::getInstitutionSupervisorId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
        return list.stream().map(pl -> summary(pl, ss.get(pl.getStudentId()), logs.getOrDefault(pl.getId(), List.of()),
                scores.getOrDefault(pl.getId(), 0L).intValue(), sup.get(pl.getInstitutionSupervisorId()))).toList();
    }

    private static PlacementSummary summary(IptPlacement pl, Student s, List<LogbookEntry> logs, int scores, String supervisor) {
        return new PlacementSummary(pl.getId(), pl.getPeriod().getId(), s.getId(), s.getRegistrationNo(), s.fullName(), s.getProgramme().getCode(),
                pl.getOrganisation().getId(), pl.getOrganisation().getName(), pl.getOrganisation().getRegion(), pl.getStatus().name(), pl.getStartsOn(),
                pl.getEndsOn(), pl.getWorkplaceSupervisorName(), pl.getInstitutionSupervisorId(), supervisor, logs.size(),
                (int) logs.stream().filter(e -> e.getStatus() == LogbookEntry.Status.APPROVED).count(),
                (int) logs.stream().filter(e -> e.getStatus() == LogbookEntry.Status.SUBMITTED).count(), scores, pl.getTotalMark(), pl.getTransferredAt(),
                pl.getLetterNo());
    }

    private PlacementView view(IptPlacement pl) {
        Student s = students.findById(pl.getStudentId()).orElseThrow();
        List<LogbookEntry> logs = logbook.findByPlacementIdOrderByWeekNoAsc(pl.getId());
        List<IptAssessment> as = assessments.findByPlacementId(pl.getId());
        String sup = pl.getInstitutionSupervisorId() == null ? null : users.findById(pl.getInstitutionSupervisorId()).map(UserAccount::getFullName).orElse(null);
        PlacementOrganisation o = pl.getOrganisation();
        boolean student = CurrentUser.id().isPresent() && s.getUserId() != null && s.getUserId().equals(CurrentUser.id().get());
        boolean manage = !student && access.has(COORDINATORS);
        boolean supervise = !student && (supervises(pl) || manage) && (pl.getStatus() == IptPlacement.Status.CONFIRMED || pl.getStatus() == IptPlacement.Status.COMPLETED);
        return new PlacementView(summary(pl, s, logs, as.size(), sup), pl.getUnit(), pl.getWorkplaceSupervisorTitle(), pl.getWorkplaceSupervisorPhone(),
                pl.getWorkplaceSupervisorEmail(), pl.getStudentNote(), pl.getDecisionNote(), pl.getRequestedAt(), pl.getConfirmedByName(), pl.getConfirmedAt(),
                pl.getLetterIssuedAt(), o.getContactName(), o.getContactPhone(), o.getAddress(), logs.stream().map(IptService::logView).toList(),
                visits.findByPlacementIdOrderByVisitedOnDesc(pl.getId()).stream()
                        .map(v -> new VisitView(v.getId(), v.getVisitedOn(), v.getMode().name(), v.getNotes(), v.getRecordedByName(), v.getRecordedAt())).toList(),
                as.stream().sorted(Comparator.comparing(IptAssessment::getKind))
                        .map(a -> new ScoreView(a.getKind().name(), a.getScore(), a.getComments(), a.getAssessedByName(), a.getAssessedAt())).toList(),
                pl.getPeriod().getWorkplaceWeight(), pl.getPeriod().getInstitutionWeight(), pl.getPeriod().getLogbookWeight(),
                new Permissions(manage, supervise, student && pl.getStatus() == IptPlacement.Status.CONFIRMED));
    }

    private static LogbookView logView(LogbookEntry e) {
        return new LogbookView(e.getId(), e.getWeekNo(), e.getWeekStarting(), e.getActivities(), e.getSkills(), e.getChallenges(), e.getHours(),
                e.getStatus().name(), e.getSubmittedAt(), e.getReviewerComment(), e.getReviewedByName(), e.getReviewedAt());
    }

    private static OrganisationView orgView(PlacementOrganisation o, int count) {
        return new OrganisationView(o.getId(), o.getName(), o.getSector(), o.getRegion(), o.getDistrict(), o.getAddress(), o.getContactName(),
                o.getContactTitle(), o.getContactPhone(), o.getContactEmail(), o.getCapacity(), o.isActive(), o.isProposedByStudent(), count);
    }
}
