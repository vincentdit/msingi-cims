package tz.co.msingi.cims.finance.fees;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class FeeDtos {

    private FeeDtos() {
    }

    public record FeeItemRequest(
            @NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Z0-9_\\-]+$", message = "use capital letters, digits, - and _") String code,
            @NotBlank @Size(max = 150) String name,
            @NotNull FeeItem.Category category,
            @Size(max = 20) String gfsCode,
            @Min(1) @Max(999) short allocationPriority,
            boolean active) {
    }

    public record FeeItemView(Long id, String code, String name, String category, String gfsCode, int allocationPriority, boolean active) {
        static FeeItemView of(FeeItem i) {
            return new FeeItemView(i.getId(), i.getCode(), i.getName(), i.getCategory().name(), i.getGfsCode(), i.getAllocationPriority(), i.isActive());
        }
    }

    public record LineRequest(@NotNull Long feeItemId, @NotNull @PositiveOrZero BigDecimal amount,
                              @PositiveOrZero BigDecimal foreignAmount, @Min(1) @Max(4) short instalment) {
    }

    public record StructureRequest(@NotNull Long academicYearId, @NotNull Long programmeId, @Min(1) @Max(8) short yearOfStudy,
                                   @Size(max = 500) String notes, @NotEmpty @Size(max = 40) List<@Valid LineRequest> lines) {
    }

    public record LinesRequest(@Size(max = 500) String notes, @NotEmpty @Size(max = 40) List<@Valid LineRequest> lines) {
    }

    public record LineView(Long feeItemId, String feeItemCode, String feeItemName, String category, BigDecimal amount,
                           BigDecimal foreignAmount, int instalment) {
    }

    public record StructureView(Long id, Long academicYearId, String academicYear, Long programmeId, String programmeCode,
                                String programmeName, int yearOfStudy, int structureVersion, String status, String notes,
                                String createdByName, OffsetDateTime approvedAt, String approvedByName, BigDecimal total,
                                BigDecimal foreignTotal, BigDecimal firstInstalment, List<LineView> lines, boolean canApprove) {
    }

    public record GateRequest(boolean enabled, @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal minPercentPaid,
                              boolean firstInstalmentRequired) {
    }

    public record GateView(String gate, boolean enabled, BigDecimal minPercentPaid, boolean firstInstalmentRequired, boolean appliedNow) {
    }
}
