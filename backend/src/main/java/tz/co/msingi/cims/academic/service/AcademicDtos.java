package tz.co.msingi.cims.academic.service;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.academic.domain.Department;
import tz.co.msingi.cims.academic.domain.Intake;
import tz.co.msingi.cims.academic.domain.Programme;

import java.time.LocalDate;

public final class AcademicDtos {

    private AcademicDtos() {
    }

    public record AcademicYearRequest(
            @NotBlank @Pattern(regexp = "\\d{4}/\\d{4}", message = "Use the format 2027/2028") String code,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            boolean current) {
    }

    public record AcademicYearView(Long id, String code, LocalDate startDate, LocalDate endDate, boolean current) {
        public static AcademicYearView of(AcademicYear y) {
            return new AcademicYearView(y.getId(), y.getCode(), y.getStartDate(), y.getEndDate(), y.isCurrent());
        }
    }

    public record IntakeRequest(@NotBlank @Pattern(regexp = "[A-Z_]{2,20}") String code, @NotBlank String name,
                                @Min(1) @Max(12) short startMonth, boolean active) {
    }

    public record IntakeView(Long id, String code, String name, short startMonth, boolean active) {
        public static IntakeView of(Intake i) {
            return new IntakeView(i.getId(), i.getCode(), i.getName(), i.getStartMonth(), i.isActive());
        }
    }

    public record DepartmentRequest(@NotBlank @Pattern(regexp = "[A-Z0-9_]{2,20}") String code, @NotBlank String name, boolean active,
                                    Long facultyId) {
        public DepartmentRequest(String code, String name, boolean active) {
            this(code, name, active, null);
        }
    }

    public record DepartmentView(Long id, String code, String name, boolean active, Long facultyId) {
        public static DepartmentView of(Department d) {
            return new DepartmentView(d.getId(), d.getCode(), d.getName(), d.isActive(), d.getFacultyId());
        }
    }

    public record ProgrammeRequest(
            @NotNull Long departmentId,
            @NotBlank @Pattern(regexp = "[A-Z0-9_\\-]{2,30}") String code,
            String nactvetCode,
            @NotBlank String name,
            @NotNull Programme.AwardFamily awardFamily,
            @Min(1) @Max(8) short awardLevel,
            String awardName,
            @Min(1) @Max(16) short durationSemesters,
            @Positive Integer accreditedCapacity,
            String description,
            boolean active,
            @Min(1) @Max(8) Short entryLevel) {
    }

    public record ProgrammeView(Long id, String code, String nactvetCode, String name, String awardFamily, short awardLevel,
                                String awardName, short durationSemesters, Integer accreditedCapacity, String description,
                                boolean active, Long departmentId, String departmentName, int entryLevel, boolean entryLevelSet) {
        public static ProgrammeView of(Programme p) {
            return new ProgrammeView(p.getId(), p.getCode(), p.getNactvetCode(), p.getName(), p.getAwardFamily().name(),
                    p.getAwardLevel(), p.getAwardName(), p.getDurationSemesters(), p.getAccreditedCapacity(),
                    p.getDescription(), p.isActive(), p.getDepartment().getId(), p.getDepartment().getName(), p.entryLevel(),
                    p.getEntryLevelSet() != null);
        }
    }
}
