package tz.co.msingi.cims.finance.sponsors;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class SponsorDtos {

    private SponsorDtos() {
    }

    public record SponsorRequest(@NotBlank @Size(max = 20) @Pattern(regexp = "[A-Za-z0-9_-]+", message = "letters, digits, - and _ only") String code,
                                 @NotBlank @Size(max = 200) String name, @NotNull Sponsor.Kind kind, @Size(max = 150) String contactName,
                                 @Email @Size(max = 120) String contactEmail, @Size(max = 20) String contactPhone, @Size(max = 300) String address,
                                 Boolean active) {
    }

    public record SponsorRow(Long id, String code, String name, String kind, String contactName, String contactPhone, String contactEmail,
                             String address, boolean active, long students, BigDecimal invoiced, BigDecimal paid, BigDecimal balance) {
    }

    public record SponsorshipRequest(Long studentId, @Size(max = 40) String registrationNo, @NotNull Long academicYearId,
                                     @NotNull @DecimalMin("0.01") @DecimalMax("100") BigDecimal coveragePercent, @DecimalMin("1") BigDecimal capAmount,
                                     List<@Size(max = 30) String> feeItemCodes, @Size(max = 60) String reference) {
    }

    public record SponsorshipView(Long id, Long studentId, String registrationNo, String studentName, String programmeCode, Long academicYearId,
                                  String academicYear, BigDecimal coveragePercent, BigDecimal capAmount, List<String> feeItemCodes, String reference,
                                  String status, BigDecimal share, BigDecimal invoiced, BigDecimal paid, BigDecimal outstanding, String note) {
    }

    public record ImportError(int row, String message) {
    }

    public record ImportResult(int created, int skipped, List<ImportError> errors) {
    }

    public record SponsorInvoice(Long id, String invoiceNo, String description, BigDecimal total, BigDecimal paid, BigDecimal balance,
                                 String status, String controlNo, OffsetDateTime controlNoExpiresAt, OffsetDateTime createdAt, int students) {
    }

    public record YearOption(Long id, String code) {
    }

    public record SponsorDetail(SponsorRow sponsor, List<YearOption> years, Long academicYearId, List<SponsorshipView> sponsorships,
                                List<SponsorInvoice> invoices, BigDecimal shares, BigDecimal invoiced, BigDecimal paid, BigDecimal balance,
                                BigDecimal notYetInvoiced, boolean canManage) {
    }

    public record InvoiceRunRequest(@NotNull Long academicYearId) {
    }

    public record InvoiceRunResult(Long invoiceId, String invoiceNo, int students, BigDecimal amount, List<String> skipped) {
    }

    public record RemittanceRequest(@NotNull @DecimalMin("1") BigDecimal amount, @NotBlank @Size(max = 60) String reference, @NotNull LocalDate paidOn) {
    }
}
