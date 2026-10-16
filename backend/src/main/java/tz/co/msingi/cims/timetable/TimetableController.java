package tz.co.msingi.cims.timetable;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.timetable.TimetableDtos.CopyRequest;
import tz.co.msingi.cims.timetable.TimetableDtos.CopyResult;
import tz.co.msingi.cims.timetable.TimetableDtos.ExamTimetable;
import tz.co.msingi.cims.timetable.TimetableDtos.Kind;
import tz.co.msingi.cims.timetable.TimetableDtos.MyTimetable;
import tz.co.msingi.cims.timetable.TimetableDtos.PublishRequest;
import tz.co.msingi.cims.timetable.TimetableDtos.PublishResult;
import tz.co.msingi.cims.timetable.TimetableDtos.SemesterTimetable;
import tz.co.msingi.cims.timetable.TimetableDtos.SittingRequest;
import tz.co.msingi.cims.timetable.TimetableDtos.SittingView;
import tz.co.msingi.cims.timetable.TimetableDtos.SlotRequest;
import tz.co.msingi.cims.timetable.TimetableDtos.SlotView;
import tz.co.msingi.cims.timetable.TimetableDtos.StudentTimetable;
import tz.co.msingi.cims.timetable.TimetableDtos.VenueRequest;
import tz.co.msingi.cims.timetable.TimetableDtos.VenueView;

import java.util.List;

/** Timetabling (FR-TCH-05, FR-EXM-15): teaching venues, the class and examination timetables, and personal timetables. */
@Tag(name = "Timetables")
@RestController
@RequiredArgsConstructor
public class TimetableController {

    /** Who may look at the timetables of the institution. */
    private static final String STAFF = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT','PROGRAMME_COORDINATOR',"
            + "'EXAMINATIONS_OFFICER','LECTURER','ASSESSOR','REGISTRAR','MANAGEMENT_VIEWER')";
    /** Who may change something (each service checks the exact office). */
    private static final String KEEPERS = "hasAnyRole('INSTITUTION_ADMIN','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT','EXAMINATIONS_OFFICER')";

    private final TimetableService classes;
    private final ExamTimetableService exams;
    private final MyTimetableService personal;
    private final TimetableExport export;

    // ------------------------------------------------------------------ venues

    @GetMapping("/api/timetable/venues")
    @PreAuthorize(STAFF)
    public List<VenueView> venues() {
        return classes.venues();
    }

    @PostMapping("/api/timetable/venues")
    @PreAuthorize(KEEPERS)
    @ResponseStatus(HttpStatus.CREATED)
    public VenueView createVenue(@Valid @RequestBody VenueRequest req) {
        return classes.createVenue(req);
    }

    @PutMapping("/api/timetable/venues/{id}")
    @PreAuthorize(KEEPERS)
    public VenueView updateVenue(@PathVariable Long id, @Valid @RequestBody VenueRequest req) {
        return classes.updateVenue(id, req);
    }

    // ------------------------------------------------------------------ the class timetable

    @GetMapping("/api/timetable/semesters/{id}")
    @PreAuthorize(STAFF)
    public SemesterTimetable semester(@PathVariable Long id) {
        return classes.semester(id);
    }

    @PostMapping("/api/timetable/semesters/{id}/slots")
    @PreAuthorize(KEEPERS)
    @ResponseStatus(HttpStatus.CREATED)
    public SlotView addSlot(@PathVariable Long id, @Valid @RequestBody SlotRequest req) {
        return classes.createSlot(id, req);
    }

    @PutMapping("/api/timetable/slots/{id}")
    @PreAuthorize(KEEPERS)
    public SlotView updateSlot(@PathVariable Long id, @Valid @RequestBody SlotRequest req) {
        return classes.updateSlot(id, req);
    }

    @DeleteMapping("/api/timetable/slots/{id}")
    @PreAuthorize(KEEPERS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSlot(@PathVariable Long id) {
        classes.deleteSlot(id);
    }

    @PostMapping("/api/timetable/semesters/{id}/copy")
    @PreAuthorize(KEEPERS)
    public CopyResult copy(@PathVariable Long id, @Valid @RequestBody CopyRequest req) {
        return classes.copy(id, req.fromSemesterId());
    }

    // ------------------------------------------------------------------ the examination timetable

    @GetMapping("/api/timetable/semesters/{id}/exams")
    @PreAuthorize(STAFF)
    public ExamTimetable examTimetable(@PathVariable Long id) {
        return exams.semester(id);
    }

    @PostMapping("/api/timetable/semesters/{id}/exams")
    @PreAuthorize(KEEPERS)
    @ResponseStatus(HttpStatus.CREATED)
    public SittingView addPaper(@PathVariable Long id, @Valid @RequestBody SittingRequest req) {
        return exams.create(id, req);
    }

    @PutMapping("/api/timetable/exams/{id}")
    @PreAuthorize(KEEPERS)
    public SittingView updatePaper(@PathVariable Long id, @Valid @RequestBody SittingRequest req) {
        return exams.update(id, req);
    }

    @DeleteMapping("/api/timetable/exams/{id}")
    @PreAuthorize(KEEPERS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePaper(@PathVariable Long id) {
        exams.delete(id);
    }

    // ------------------------------------------------------------------ publishing and printing

    @PostMapping("/api/timetable/semesters/{id}/publish")
    @PreAuthorize(KEEPERS)
    public PublishResult publish(@PathVariable Long id, @Valid @RequestBody PublishRequest req) {
        return classes.publish(id, req.kind(), req.sendSms());
    }

    @PostMapping("/api/timetable/semesters/{id}/withdraw")
    @PreAuthorize(KEEPERS)
    public PublishResult withdraw(@PathVariable Long id, @Valid @RequestBody PublishRequest req) {
        return classes.withdraw(id, req.kind());
    }

    @GetMapping("/api/timetable/semesters/{id}/export")
    @PreAuthorize(STAFF)
    public ResponseEntity<byte[]> export(@PathVariable Long id, @RequestParam(defaultValue = "CLASS") Kind kind) {
        TimetableExport.Document d = export.excel(id, kind);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.filename() + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(d.bytes());
    }

    // ------------------------------------------------------------------ personal timetables

    @GetMapping("/api/timetable/mine")
    @PreAuthorize(STAFF)
    public MyTimetable mine(@RequestParam(required = false) Long semesterId) {
        return personal.lecturer(semesterId);
    }

    @GetMapping("/api/student/timetable")
    @PreAuthorize("hasRole('STUDENT')")
    public StudentTimetable student(@RequestParam(required = false) Long semesterId) {
        return personal.student(semesterId);
    }
}
