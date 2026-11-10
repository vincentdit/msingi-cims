package tz.co.msingi.cims.payroll;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.common.web.UploadedFile;
import tz.co.msingi.cims.platform.security.StaffAccess;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Payroll (FR-PAY-01..05). */
@Tag(name = "Payroll")
@RestController
@RequiredArgsConstructor
public class PayrollController {

    static final String READ = "hasAnyRole('INSTITUTION_ADMIN','HR_OFFICER','ACCOUNTANT','BURSAR','MANAGEMENT_VIEWER','AUDITOR')";
    static final String PREPARE = "hasAnyRole('INSTITUTION_ADMIN','HR_OFFICER','ACCOUNTANT')";

    private final PayrollService payroll;

    public record MonthRequest(String month) {
    }

    public record NoteRequest(String note) {
    }

    public record PaidRequest(LocalDate paidOn) {
    }

    @GetMapping("/api/payroll/settings")
    @PreAuthorize(READ)
    public PayrollService.Settings settings() {
        return payroll.settings();
    }

    @PutMapping("/api/payroll/settings")
    @PreAuthorize(READ)
    public PayrollService.Settings saveSettings(@RequestBody PayrollService.Settings s) {
        return payroll.saveSettings(s);
    }

    @GetMapping("/api/payroll/staff")
    @PreAuthorize(READ)
    public List<PayrollService.PayStaffRow> staff(@RequestParam(required = false) String q) {
        return payroll.staffList(q);
    }

    @GetMapping("/api/payroll/staff/{staffId}")
    @PreAuthorize(READ)
    public PayrollService.PayDetails payDetails(@PathVariable Long staffId) {
        return payroll.payDetails(staffId);
    }

    @PutMapping("/api/payroll/staff/{staffId}")
    @PreAuthorize(PREPARE)
    public PayrollService.PayDetails savePayDetails(@PathVariable Long staffId, @RequestBody PayrollService.PayDetailsRequest r) {
        return payroll.savePayDetails(staffId, r);
    }

    @Operation(summary = "Add a standing allowance or deduction (a deduction with a balance stops when it is paid off)")
    @PostMapping("/api/payroll/staff/{staffId}/items")
    @PreAuthorize(PREPARE)
    public PayrollService.PayDetails addItem(@PathVariable Long staffId, @RequestBody PayrollService.PayItemRequest r) {
        return payroll.addItem(staffId, r);
    }

    @PutMapping("/api/payroll/items/{id}")
    @PreAuthorize(PREPARE)
    public PayrollService.PayDetails saveItem(@PathVariable Long id, @RequestBody PayrollService.PayItemRequest r) {
        return payroll.saveItem(id, r);
    }

    @GetMapping("/api/payroll/runs")
    @PreAuthorize(READ)
    public List<PayrollService.RunView> runs() {
        return payroll.runs();
    }

    @Operation(summary = "Prepare the month's payroll (month: yyyy-MM), or work a draft out again")
    @PostMapping("/api/payroll/runs")
    @PreAuthorize(PREPARE)
    public PayrollService.RunDetail prepare(@RequestBody MonthRequest r) {
        YearMonth m;
        try {
            m = r.month() == null ? null : YearMonth.parse(r.month());
        } catch (java.time.format.DateTimeParseException e) {
            m = null;
        }
        return payroll.prepare(m);
    }

    @GetMapping("/api/payroll/runs/{id}")
    @PreAuthorize(READ)
    public PayrollService.RunDetail detail(@PathVariable Long id) {
        return payroll.detail(id);
    }

    @DeleteMapping("/api/payroll/runs/{id}")
    @PreAuthorize(PREPARE)
    public void delete(@PathVariable Long id) {
        payroll.delete(id);
    }

    @PostMapping("/api/payroll/runs/{id}/approve")
    @PreAuthorize(READ)
    public PayrollService.RunDetail approve(@PathVariable Long id) {
        return payroll.approve(id);
    }

    @PostMapping("/api/payroll/runs/{id}/return")
    @PreAuthorize(READ)
    public PayrollService.RunDetail returnToDraft(@PathVariable Long id, @RequestBody NoteRequest r) {
        return payroll.returnToDraft(id, r.note());
    }

    @PostMapping("/api/payroll/runs/{id}/paid")
    @PreAuthorize(READ)
    public PayrollService.RunDetail paid(@PathVariable Long id, @RequestBody PaidRequest r) {
        return payroll.markPaid(id, r.paidOn());
    }

    @Operation(summary = "The payroll workbook: register, summary, PAYE, pension, NHIF, HESLB and other deductions (Excel)")
    @GetMapping("/api/payroll/runs/{id}/workbook")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> workbook(@PathVariable Long id) {
        return UploadedFile.download("payroll-" + id + ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", payroll.workbook(id));
    }

    @Operation(summary = "The bank file for an approved payroll (CSV)")
    @GetMapping("/api/payroll/runs/{id}/bank-file")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> bankFile(@PathVariable Long id) {
        return UploadedFile.download("salaries-" + id + ".csv", "text/csv", payroll.bankFile(id));
    }

    @GetMapping("/api/payroll/payslips/{id}/pdf")
    @PreAuthorize(StaffAccess.ANY)
    public ResponseEntity<byte[]> payslip(@PathVariable Long id) {
        return UploadedFile.download("payslip-" + id + ".pdf", "application/pdf", payroll.payslipPdf(id));
    }

    @Operation(summary = "The signed-in person's paid payslips")
    @GetMapping("/api/my/payslips")
    @PreAuthorize(StaffAccess.ANY)
    public List<PayrollService.MySlip> mine() {
        return payroll.mine();
    }
}
