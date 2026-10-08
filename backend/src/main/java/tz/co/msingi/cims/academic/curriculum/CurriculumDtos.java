package tz.co.msingi.cims.academic.curriculum;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class CurriculumDtos {

    private CurriculumDtos() {
    }

    // ------------------------------------------------------------------ calendar (FR-ACA-04)

    public record SemesterRequest(@NotNull Long academicYearId, @Min(1) @Max(3) short semesterNo, @NotBlank @Size(max = 60) String name,
                                  @NotNull LocalDate startDate, @NotNull LocalDate endDate, @NotNull LocalDate registrationOpens,
                                  @NotNull LocalDate registrationCloses, LocalDate lateRegistrationCloses) {
    }

    public record SemesterView(Long id, Long academicYearId, String academicYear, short semesterNo, String name, LocalDate startDate,
                               LocalDate endDate, LocalDate registrationOpens, LocalDate registrationCloses, LocalDate lateRegistrationCloses,
                               String window) {
        static SemesterView of(Semester s, LocalDate today) {
            return new SemesterView(s.getId(), s.getAcademicYear().getId(), s.getAcademicYear().getCode(), s.getSemesterNo(), s.getName(),
                    s.getStartDate(), s.getEndDate(), s.getRegistrationOpens(), s.getRegistrationCloses(), s.getLateRegistrationCloses(),
                    s.window(today).name());
        }
    }

    public record EventRequest(@NotNull Long academicYearId, Long semesterId, @NotNull CalendarEvent.Kind kind,
                               @NotBlank @Size(max = 150) String title, @NotNull LocalDate startDate, @NotNull LocalDate endDate) {
    }

    public record EventView(Long id, Long academicYearId, Long semesterId, String semesterName, String kind, String title, LocalDate startDate,
                            LocalDate endDate) {
        static EventView of(CalendarEvent e) {
            return new EventView(e.getId(), e.getAcademicYear().getId(), e.getSemester() == null ? null : e.getSemester().getId(),
                    e.getSemester() == null ? null : e.getSemester().getName(), e.getKind().name(), e.getTitle(), e.getStartDate(), e.getEndDate());
        }
    }

    public record CalendarView(Long academicYearId, String academicYear, List<SemesterView> semesters, List<EventView> events) {
    }

    // ------------------------------------------------------------------ modules and curricula (FR-ACA-02/03)

    public record ModuleRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9 _/-]{2,20}") String code, @NotBlank @Size(max = 200) String title,
                                @NotNull @DecimalMin(value = "0.5") @DecimalMax("60") BigDecimal credits, @Min(1) @Max(8) Short ntaLevel,
                                @NotNull Module.AssessmentType assessmentType, Long departmentId, @Size(max = 1000) String description,
                                boolean active) {
    }

    public record ModuleView(Long id, String code, String title, BigDecimal credits, Short ntaLevel, String assessmentType, Long departmentId,
                             String departmentName, String description, boolean active, long usedInCurricula) {
    }

    public record CurriculumModuleRequest(@NotNull Long moduleId, @Min(1) @Max(8) short yearOfStudy, @Min(1) @Max(3) short semesterNo,
                                          @NotNull CurriculumModule.Category category) {
    }

    public record ElectiveRuleRequest(@Min(1) @Max(8) short yearOfStudy, @Min(1) @Max(3) short semesterNo, @Min(0) short minElectives,
                                      @Min(0) short maxElectives) {
    }

    public record CurriculumRequest(@NotNull Long programmeId, @NotNull Long effectiveYearId, @Size(max = 1000) String notes,
                                    @NotNull List<@Valid CurriculumModuleRequest> modules, @NotNull List<@Valid ElectiveRuleRequest> electiveRules) {
    }

    public record CurriculumModuleView(Long id, Long moduleId, String code, String title, BigDecimal credits, Short ntaLevel, String assessmentType,
                                       short yearOfStudy, short semesterNo, String category) {
    }

    public record SlotView(short yearOfStudy, short semesterNo, BigDecimal coreCredits, int core, int electives, Short minElectives,
                           Short maxElectives, List<String> problems) {
    }

    public record CurriculumView(Long id, Long programmeId, String programmeCode, String programmeName, int programmeSemesters,
                                 short curriculumVersion, Long effectiveYearId, String effectiveYear, String status, String notes,
                                 String createdByName, OffsetDateTime approvedAt, String approvedByName, OffsetDateTime retiredAt,
                                 boolean canApprove, long studentsOnIt, List<CurriculumModuleView> modules, List<ElectiveRuleRequest> electiveRules,
                                 List<SlotView> slots, List<String> problems) {
    }

    public record CurriculumSummary(Long id, Long programmeId, String programmeCode, String programmeName, short curriculumVersion,
                                    String effectiveYear, String status, int modules, BigDecimal totalCredits, OffsetDateTime approvedAt) {
    }
}
