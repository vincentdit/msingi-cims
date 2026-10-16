package tz.co.msingi.cims.timetable;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tz.co.msingi.cims.exams.ExamDtos.Person;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

/** Timetabling (FR-TCH-05, FR-EXM-15). */
public final class TimetableDtos {

    private TimetableDtos() {
    }

    // ------------------------------------------------------------------ venues

    public record VenueRequest(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 120) String name, @NotNull TeachingVenue.Kind kind,
                               @NotNull @Positive @Max(5000) Integer capacity, @Positive @Max(5000) Integer examCapacity, Long campusId, Boolean active) {
    }

    public record VenueView(Long id, String code, String name, String kind, int capacity, Integer examCapacity, int examSeats, Long campusId,
                            String campusName, boolean active) {
    }

    // ------------------------------------------------------------------ the weekly class timetable

    public record SlotRequest(@NotNull Long offeringId, @NotNull String kind, @NotNull @Min(1) @Max(7) Integer dayOfWeek, @NotNull LocalTime startTime,
                              @NotNull LocalTime endTime, @NotNull Long venueId, Long lecturerId, @Size(max = 30) String groupLabel,
                              @Size(max = 200) String note) {
    }

    public record SlotView(Long id, Long offeringId, String moduleCode, String moduleTitle, String kind, int dayOfWeek, LocalTime startTime,
                           LocalTime endTime, Long venueId, String venueCode, String venueName, Long lecturerId, String lecturerName,
                           String groupLabel, String note, int students, boolean changedSincePublished) {
    }

    /** A programme and year of study whose (approved) curriculum has the module this semester. */
    public record Cohort(Long programmeId, String programmeCode, int yearOfStudy, String category) {
    }

    public record OfferingRow(Long id, String code, String title, String departmentName, List<Person> lecturers, int students, List<Cohort> cohorts,
                              int minutesPerWeek, int slots, boolean canEdit) {
    }

    /** A clash or other point to look at: severity error (must be fixed), warning, or info. */
    public record Warning(String kind, String severity, String message, List<Long> ids) {
    }

    public record SemesterTimetable(Long semesterId, String semesterName, LocalDate startDate, LocalDate endDate, OffsetDateTime publishedAt,
                                    String publishedBy, boolean canEdit, boolean canPublish, List<OfferingRow> offerings, List<SlotView> slots,
                                    List<VenueView> venues, List<Warning> warnings) {
    }

    public record CopyRequest(@NotNull Long fromSemesterId) {
    }

    public record CopyResult(int copied, int skipped, List<String> notes) {
    }

    // ------------------------------------------------------------------ the examination timetable

    public record SittingRequest(@NotNull Long offeringId, @Size(max = 40) String paper, @NotNull LocalDate date, @NotNull LocalTime startTime,
                                 @NotNull LocalTime endTime, @NotEmpty List<Long> venueIds, List<Long> invigilatorIds, @Size(max = 200) String note) {
    }

    public record VenueRef(Long id, String code, String name, int seats) {
    }

    public record SittingView(Long id, Long offeringId, String moduleCode, String moduleTitle, String paper, LocalDate date, LocalTime startTime,
                              LocalTime endTime, List<VenueRef> venues, List<Person> invigilators, int candidates, int seats, String note,
                              boolean changedSincePublished) {
    }

    public record ExamOffering(Long id, String code, String title, String departmentName, int candidates, int papers) {
    }

    public record ExamTimetable(Long semesterId, String semesterName, LocalDate startDate, LocalDate endDate, OffsetDateTime publishedAt,
                                String publishedBy, boolean canEdit, List<ExamOffering> offerings, List<SittingView> sittings, List<VenueView> venues,
                                List<Person> staff, List<Warning> warnings) {
    }

    // ------------------------------------------------------------------ publishing

    public enum Kind { CLASS, EXAM }

    public record PublishRequest(@NotNull Kind kind, boolean sendSms) {
    }

    public record PublishResult(Kind kind, OffsetDateTime publishedAt, String publishedBy, int notified) {
    }

    // ------------------------------------------------------------------ personal timetables

    public record SemesterOption(Long id, String name, String yearCode, LocalDate startDate, LocalDate endDate) {
    }

    /** An examination in a personal timetable: the person's own module, or one they invigilate. */
    public record MyExam(SittingView sitting, boolean invigilating) {
    }

    public record MyTimetable(List<SemesterOption> semesters, SemesterOption semester, boolean classPublished, boolean examPublished,
                              List<SlotView> slots, List<MyExam> exams) {
    }

    public record StudentTimetable(Long semesterId, String semesterName, boolean classPublished, OffsetDateTime classPublishedAt,
                                   boolean examPublished, OffsetDateTime examPublishedAt, List<SlotView> slots, List<SittingView> exams,
                                   List<String> unscheduled) {
    }
}
