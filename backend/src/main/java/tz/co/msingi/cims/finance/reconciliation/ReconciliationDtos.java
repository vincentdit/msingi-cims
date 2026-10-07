package tz.co.msingi.cims.finance.reconciliation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class ReconciliationDtos {

    private ReconciliationDtos() {
    }

    public record ImportRequest(@NotNull LocalDate date, @Size(max = 200) String fileName, @NotBlank @Size(max = 5_000_000) String csv) {
    }

    public record NoteRequest(@NotBlank @Size(max = 500) String note) {
    }

    public record PostRequest(@Size(max = 500) String note) {
    }

    public record SignoffRequest(@Size(max = 500) String note) {
    }

    public record ItemView(Long id, Integer lineNo, String kind, String status, String gepgReceiptNo, String billId, String controlNo,
                           String pspName, String pspReceiptNo, String channel, String payerPhone, BigDecimal gepgAmount,
                           BigDecimal cimsAmount, OffsetDateTime paidAt, Long paymentId, boolean paidDateDiffers, String resolution,
                           String note, OffsetDateTime resolvedAt, String resolvedByName, String payerName, String receiptNo) {
    }

    public record ReconciliationView(Long id, LocalDate date, String source, String fileName, String status, int gepgCount,
                                     BigDecimal gepgTotal, int cimsCount, BigDecimal cimsTotal, int matchedCount, int exceptionCount,
                                     long openExceptions, Double matchRate, OffsetDateTime importedAt, String importedByName,
                                     OffsetDateTime closedAt, String closedByName, List<ItemView> items) {
    }

    public record DayRow(LocalDate date, long cimsCount, BigDecimal cimsTotal, Long reconciliationId, String status,
                         Integer exceptionCount, Long openExceptions, Integer matchedCount, Integer gepgCount) {
    }

    public record MonthView(LocalDate month, BigDecimal collected, long payments, int daysWithPayments, int daysClosed,
                            boolean monthEnded, boolean canSignOff, String signOffBlocker, OffsetDateTime signedAt,
                            String signedByName, String signOffNote, List<DayRow> days) {
    }
}
