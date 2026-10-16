package tz.co.msingi.cims.hostel;

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

public final class HostelDtos {

    private HostelDtos() {
    }

    // ------------------------------------------------------------------ inventory (FR-HST-01)

    public record FeeCategoryRequest(@NotBlank @Size(max = 20) @Pattern(regexp = "[A-Za-z0-9_-]+") String code, @NotBlank @Size(max = 120) String name,
                                     @NotNull @DecimalMin("0") BigDecimal amount, Boolean active) {
    }

    public record FeeCategoryView(Long id, String code, String name, BigDecimal amount, boolean active) {
    }

    public record HostelRequest(@NotBlank @Size(max = 20) @Pattern(regexp = "[A-Za-z0-9_-]+") String code, @NotBlank @Size(max = 120) String name,
                                @NotNull Hostel.Gender gender, @Size(max = 200) String location, Boolean active) {
    }

    public record HostelSummary(Long id, String code, String name, String gender, String location, boolean active, int rooms, int beds,
                                int available, int occupied, int allocatedUnpaid, int outOfService) {
    }

    public record RoomRequest(@NotNull Long hostelId, @Size(max = 40) String block, @Min(-2) @Max(60) Integer floor,
                              @NotBlank @Size(max = 20) String roomNo, @NotNull HostelRoom.RoomType roomType, HostelRoom.Gender gender,
                              @NotNull Long feeCategoryId, Boolean accessible, HostelRoom.Status status, @NotNull @Min(1) @Max(40) Integer beds) {
    }

    public record BedView(Long id, String label, String status, Long allocationId, String occupantRegNo, String occupantName,
                          String allocationStatus) {
    }

    public record RoomView(Long id, Long hostelId, String hostelName, String block, int floor, String roomNo, String roomType, String gender,
                           Long feeCategoryId, String feeCategory, BigDecimal fee, boolean accessible, String status, List<BedView> beds) {
    }

    public record Warden(Long id, String fullName) {
    }

    public record HostelDetail(HostelSummary hostel, List<RoomView> rooms, boolean canEdit, List<Warden> wardens, boolean canAssignWardens) {
    }

    public record BedStatusRequest(@NotNull HostelBed.Status status) {
    }

    // ------------------------------------------------------------------ windows and applications (FR-HST-02)

    public record WindowRequest(@NotNull Long academicYearId, @NotBlank @Size(max = 120) String name, @NotNull LocalDate opensOn,
                                @NotNull LocalDate closesOn, LocalDate occupancyStarts, LocalDate occupancyEnds,
                                @NotNull @Min(1) @Max(90) Integer paymentDays, Boolean firstYearsFirst, Boolean specialNeedsFirst,
                                Boolean paidFeesFirst) {
    }

    public record WindowView(Long id, Long academicYearId, String academicYear, String name, LocalDate opensOn, LocalDate closesOn,
                             LocalDate occupancyStarts, LocalDate occupancyEnds, int paymentDays, boolean firstYearsFirst,
                             boolean specialNeedsFirst, boolean paidFeesFirst, boolean open, int applications, int allocated, int waitlisted,
                             int checkedIn) {
    }

    public record ApplyRequest(@NotNull Long windowId, Long preference1Id, Long preference2Id, Long preference3Id, HostelRoom.RoomType roomType,
                               Boolean specialNeeds, @Size(max = 500) String specialNeedsNote) {
    }

    public record AllocationView(Long id, Long applicationId, Long studentId, String registrationNo, String studentName, String sex,
                                 Long bedId, String hostel, String room, String bed, String status, BigDecimal fee, Long invoiceId,
                                 String invoiceNo, String invoiceStatus, BigDecimal balance, String controlNo, LocalDate payBy, String method,
                                 String allocatedByName, OffsetDateTime allocatedAt, OffsetDateTime checkedInAt, String checkedInByName,
                                 String checkInCondition, OffsetDateTime checkedOutAt, String checkedOutByName, String checkOutCondition,
                                 String damages, BigDecimal damageCharge, String damageInvoiceNo, OffsetDateTime releasedAt, String releaseReason,
                                 RefundView refund, int bedChanges) {
    }

    public record ApplicationView(Long id, Long windowId, Long studentId, String registrationNo, String studentName, String sex,
                                  String programmeCode, int yearOfStudy, List<String> preferences, String roomType, boolean specialNeeds,
                                  String specialNeedsNote, String status, OffsetDateTime submittedAt, boolean feesCleared, int priority,
                                  AllocationView allocation) {
    }

    public record AllocateResult(int allocated, int waitlisted, List<String> notes) {
    }

    public record ManualAllocation(@NotNull Long applicationId, @NotNull Long bedId) {
    }

    public record ReleaseResult(int released, int reallocated) {
    }

    public record NoteRequest(@Size(max = 500) String note) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 300) String reason) {
    }

    /** refundUnused: raise a refund of the unused part of the paid fee (the rest of the occupancy period) for the bursary to decide. */
    public record CheckOutRequest(@Size(max = 500) String condition, @Size(max = 500) String damages, @DecimalMin("0") BigDecimal damageCharge,
                                  Boolean refundUnused) {
        public CheckOutRequest(String condition, String damages, BigDecimal damageCharge) {
            this(condition, damages, damageCharge, null);
        }
    }

    public record MoveRequest(@NotNull Long bedId, @NotBlank @Size(min = 5, max = 500) String reason) {
    }

    /** A move to another bed and what it did to the fee: charged on a new invoice, or waived or refunded once the bursary approves. */
    public record BedChangeView(Long id, String fromBed, String toBed, BigDecimal oldFee, BigDecimal newFee, BigDecimal difference,
                                String invoiceNo, String adjustmentNo, String adjustmentKind, String adjustmentStatus, String reason,
                                String changedByName, OffsetDateTime changedAt) {
    }

    /** What happened to the money when a stay ended early or was cancelled after paying. */
    public record RefundView(String adjustmentNo, BigDecimal amount, String status) {
    }

    public record FreeBed(Long bedId, String hostel, String room, String bed, String roomType, String gender, boolean accessible, BigDecimal fee) {
    }

    // ------------------------------------------------------------------ student portal

    public record HostelOption(Long id, String name, String gender) {
    }

    public record MyHostel(String sex, List<WindowView> openWindows, List<HostelOption> hostels, ApplicationView application,
                           List<AllocationView> history, String blocker) {
    }
}
