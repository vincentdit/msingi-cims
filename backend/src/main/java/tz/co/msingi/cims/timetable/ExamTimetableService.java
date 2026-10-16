package tz.co.msingi.cims.timetable;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.exams.ExamDtos.Person;
import tz.co.msingi.cims.exams.ExamRepositories.ModuleOfferingRepository;
import tz.co.msingi.cims.exams.ModuleOffering;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.timetable.Clashes.ExamItem;
import tz.co.msingi.cims.timetable.TimetableData.Offering;
import tz.co.msingi.cims.timetable.TimetableDtos.ExamOffering;
import tz.co.msingi.cims.timetable.TimetableDtos.ExamTimetable;
import tz.co.msingi.cims.timetable.TimetableDtos.SittingRequest;
import tz.co.msingi.cims.timetable.TimetableDtos.SittingView;
import tz.co.msingi.cims.timetable.TimetableDtos.VenueRef;
import tz.co.msingi.cims.timetable.TimetableDtos.VenueView;
import tz.co.msingi.cims.timetable.TimetableRepositories.ExamSittingRepository;
import tz.co.msingi.cims.timetable.TimetableRepositories.TeachingVenueRepository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The examination timetable of a semester (FR-EXM-15): a paper per module offering (or several), on a date within the semester, in one
 * or more venues, with invigilators. The candidates are the students registered for the module. Kept and published by the
 * Examinations Office; a registered student can never have two papers at once.
 */
@Service
@RequiredArgsConstructor
public class ExamTimetableService {

    private final ExamSittingRepository sittings;
    private final TeachingVenueRepository venues;
    private final ModuleOfferingRepository offerings;
    private final TimetableData data;
    private final TimetableService timetable;
    private final ExamAccess access;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public ExamTimetable semester(Long semesterId) {
        TimetableData.Semester sem = data.semester(semesterId);
        Map<Long, Offering> offs = data.offerings(semesterId);
        List<ExamSitting> all = sittings.findBySemester(semesterId);
        Map<Long, String> names = names(all);
        Map<Long, Integer> papers = new HashMap<>();
        all.forEach(s -> papers.merge(s.getOffering().getId(), 1, Integer::sum));
        List<ExamOffering> rows = offs.values().stream().map(o -> new ExamOffering(o.id(), o.code(), o.title(), o.departmentName(), o.students().size(),
                papers.getOrDefault(o.id(), 0))).toList();
        List<VenueView> vs = timetable.venues();
        Map<Long, String> codes = new HashMap<>();
        vs.forEach(v -> codes.put(v.id(), v.code()));
        return new ExamTimetable(sem.id(), sem.label(), sem.startDate(), sem.endDate(), sem.examPublishedAt(), sem.examPublishedBy(),
                access.has(TimetableService.EXAMS), rows, all.stream().map(s -> view(s, offs, names, sem.examPublishedAt())).toList(),
                vs.stream().filter(VenueView::active).toList(), data.staff(), Clashes.examWarnings(items(all), offs, codes));
    }

    @Transactional
    public SittingView create(Long semesterId, SittingRequest req) {
        access.require("keep the examination timetable", TimetableService.EXAMS);
        ModuleOffering o = offerings.findById(req.offeringId()).orElseThrow(() -> new NotFoundException("Module offering", req.offeringId()));
        if (!o.getSemester().getId().equals(semesterId)) throw new BusinessRuleException("OFFERING_NOT_IN_SEMESTER", "That module is not offered in this semester.");
        String paper = paper(req.paper());
        if (sittings.existsByOfferingIdAndPaperIgnoreCase(o.getId(), paper)) {
            throw new BusinessRuleException("PAPER_EXISTS", o.getModule().getCode() + " already has " + paper + ". Give the paper another name.");
        }
        ExamSitting s = new ExamSitting();
        s.setOffering(o);
        apply(s, req, paper);
        check(s);
        sittings.save(s);
        audit.record("EXAM_PAPER_SCHEDULED", "ModuleOffering", o.getId(), Map.of("module", o.getModule().getCode(), "paper", paper,
                "when", item(s).when()));
        return single(s);
    }

    @Transactional
    public SittingView update(Long id, SittingRequest req) {
        access.require("keep the examination timetable", TimetableService.EXAMS);
        ExamSitting s = sittings.findById(id).orElseThrow(() -> new NotFoundException("Examination paper", id));
        if (!req.offeringId().equals(s.getOffering().getId())) {
            throw new BusinessRuleException("OFFERING_FIXED", "A paper stays with its module: remove it and add one for the other module.");
        }
        String paper = paper(req.paper());
        if (!paper.equalsIgnoreCase(s.getPaper()) && sittings.existsByOfferingIdAndPaperIgnoreCase(s.getOffering().getId(), paper)) {
            throw new BusinessRuleException("PAPER_EXISTS", s.getOffering().getModule().getCode() + " already has " + paper + ".");
        }
        String before = item(s).when();
        apply(s, req, paper);
        check(s);
        audit.record("EXAM_PAPER_CHANGED", "ModuleOffering", s.getOffering().getId(), Map.of("module", s.getOffering().getModule().getCode(),
                "paper", paper, "from", before, "to", item(s).when()));
        return single(s);
    }

    @Transactional
    public void delete(Long id) {
        access.require("keep the examination timetable", TimetableService.EXAMS);
        ExamSitting s = sittings.findById(id).orElseThrow(() -> new NotFoundException("Examination paper", id));
        audit.record("EXAM_PAPER_REMOVED", "ModuleOffering", s.getOffering().getId(), Map.of("module", s.getOffering().getModule().getCode(),
                "paper", s.getPaper()));
        sittings.delete(s);
    }

    private void apply(ExamSitting s, SittingRequest req, String paper) {
        var sem = s.getOffering().getSemester();
        if (!req.endTime().isAfter(req.startTime())) throw new BusinessRuleException("INVALID_TIMES", "The paper must end after it starts.");
        if (req.date().isBefore(sem.getStartDate()) || req.date().isAfter(sem.getEndDate())) {
            throw new BusinessRuleException("SITTING_OUTSIDE_SEMESTER", "The date must fall within the semester (" + sem.getStartDate() + " to "
                    + sem.getEndDate() + ").");
        }
        Set<Long> venueIds = new LinkedHashSet<>(req.venueIds());
        List<TeachingVenue> vs = new ArrayList<>();
        for (Long vid : venueIds) {
            TeachingVenue v = venues.findById(vid).orElseThrow(() -> new NotFoundException("Venue", vid));
            if (!v.isActive()) throw new BusinessRuleException("VENUE_INACTIVE", v.getCode() + " is not in use.");
            vs.add(v);
        }
        Set<Long> invigilators = new LinkedHashSet<>(req.invigilatorIds() == null ? List.of() : req.invigilatorIds());
        Set<Long> staff = new HashSet<>();
        data.staff().forEach(p -> staff.add(p.id()));
        for (Long u : invigilators) if (!staff.contains(u)) throw new BusinessRuleException("NOT_STAFF", "Invigilators must be active staff.");
        s.setPaper(paper);
        s.setSittingDate(req.date());
        s.setStartTime(req.startTime());
        s.setEndTime(req.endTime());
        s.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        s.getVenues().removeIf(x -> !venueIds.contains(x.getVenue().getId()));
        for (TeachingVenue v : vs) {
            if (s.getVenues().stream().noneMatch(x -> x.getVenue().getId().equals(v.getId()))) {
                ExamSitting.Venue x = new ExamSitting.Venue();
                x.setSitting(s);
                x.setVenue(v);
                s.getVenues().add(x);
            }
        }
        s.getInvigilators().removeIf(x -> !invigilators.contains(x.getUserId()));
        for (Long u : invigilators) {
            if (s.getInvigilators().stream().noneMatch(x -> x.getUserId().equals(u))) {
                ExamSitting.Invigilator x = new ExamSitting.Invigilator();
                x.setSitting(s);
                x.setUserId(u);
                s.getInvigilators().add(x);
            }
        }
        s.setChangedAt(OffsetDateTime.now());
    }

    private void check(ExamSitting s) {
        Long semesterId = s.getOffering().getSemester().getId();
        List<ExamSitting> all = sittings.findBySemester(semesterId);
        Set<Long> people = new HashSet<>();
        all.forEach(x -> x.getInvigilators().forEach(i -> people.add(i.getUserId())));
        s.getInvigilators().forEach(i -> people.add(i.getUserId()));
        Clashes.checkExam(item(s), items(all), data.offerings(semesterId), data.names(people));
    }

    private SittingView single(ExamSitting s) {
        Long semesterId = s.getOffering().getSemester().getId();
        return view(s, data.offerings(semesterId), names(List.of(s)), data.semester(semesterId).examPublishedAt());
    }

    private Map<Long, String> names(List<ExamSitting> list) {
        Set<Long> ids = new HashSet<>();
        list.forEach(s -> s.getInvigilators().forEach(i -> ids.add(i.getUserId())));
        return data.names(ids);
    }

    static SittingView view(ExamSitting s, Map<Long, Offering> offs, Map<Long, String> names, OffsetDateTime publishedAt) {
        Offering o = offs.get(s.getOffering().getId());
        List<VenueRef> vs = s.getVenues().stream().map(v -> new VenueRef(v.getVenue().getId(), v.getVenue().getCode(), v.getVenue().getName(),
                v.getVenue().seatsForExams())).sorted(java.util.Comparator.comparing(VenueRef::code)).toList();
        List<Person> inv = s.getInvigilators().stream().map(i -> new Person(i.getUserId(), names.getOrDefault(i.getUserId(), "?")))
                .sorted(java.util.Comparator.comparing(Person::fullName)).toList();
        return new SittingView(s.getId(), s.getOffering().getId(), s.getOffering().getModule().getCode(), s.getOffering().getModule().getTitle(),
                s.getPaper(), s.getSittingDate(), s.getStartTime(), s.getEndTime(), vs, inv, o == null ? 0 : o.students().size(),
                vs.stream().mapToInt(VenueRef::seats).sum(), s.getNote(),
                publishedAt != null && s.getChangedAt().isAfter(publishedAt));
    }

    static ExamItem item(ExamSitting s) {
        Set<Long> vids = new HashSet<>();
        int seats = 0;
        for (ExamSitting.Venue v : s.getVenues()) {
            vids.add(v.getVenue().getId());
            seats += v.getVenue().seatsForExams();
        }
        Set<Long> inv = new HashSet<>();
        s.getInvigilators().forEach(i -> inv.add(i.getUserId()));
        return new ExamItem(s.getId(), s.getOffering().getId(), s.getPaper(), s.getSittingDate(), s.getStartTime(), s.getEndTime(), vids, seats, inv);
    }

    static List<ExamItem> items(List<ExamSitting> list) {
        return list.stream().map(ExamTimetableService::item).toList();
    }

    private static String paper(String p) {
        return p == null || p.isBlank() ? "Paper 1" : p.trim();
    }
}
