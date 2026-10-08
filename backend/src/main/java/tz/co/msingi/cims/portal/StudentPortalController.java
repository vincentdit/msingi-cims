package tz.co.msingi.cims.portal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.finance.ApplicantAccountController.SimulatedPayment;
import tz.co.msingi.cims.finance.accounts.AccountDtos.Statement;
import tz.co.msingi.cims.finance.accounts.AccountService;
import tz.co.msingi.cims.finance.accounts.ReceiptService;
import tz.co.msingi.cims.finance.accounts.SimulatedPaymentService;
import tz.co.msingi.cims.platform.security.TokenService;
import tz.co.msingi.cims.portal.PortalDtos.ChangeRequestSubmit;
import tz.co.msingi.cims.portal.PortalDtos.ChangeRequestView;
import tz.co.msingi.cims.portal.PortalDtos.Dashboard;
import tz.co.msingi.cims.portal.PortalDtos.ProfileView;
import tz.co.msingi.cims.portal.PortalDtos.StudentLoginRequest;
import tz.co.msingi.cims.portal.PortalDtos.StudentNotice;

import java.util.List;

/** Student portal (FR-STU-01/02, FR-REG-08): everything here is the signed-in student's own. */
@Tag(name = "Student portal")
@RestController
@RequiredArgsConstructor
public class StudentPortalController {

    private static final String STUDENT = "hasRole('STUDENT')";

    private final StudentPortalService portal;
    private final AccountService accounts;
    private final ReceiptService receipts;
    private final SimulatedPaymentService simulator;

    @Operation(summary = "Student sign-in with registration number, mobile number or email")
    @PostMapping("/api/student-auth/login")
    public TokenService.TokenResponse login(@Valid @RequestBody StudentLoginRequest req) {
        return portal.login(req.login(), req.password());
    }

    @GetMapping("/api/student/dashboard")
    @PreAuthorize(STUDENT)
    public Dashboard dashboard() {
        return portal.dashboard();
    }

    @GetMapping("/api/student/notices")
    @PreAuthorize(STUDENT)
    public List<StudentNotice> notices() {
        return portal.notices();
    }

    @PostMapping("/api/student/notices/{id}/read")
    @PreAuthorize(STUDENT)
    public StudentNotice read(@PathVariable Long id) {
        return portal.markRead(id);
    }

    @GetMapping("/api/student/profile")
    @PreAuthorize(STUDENT)
    public ProfileView profile() {
        return portal.profile();
    }

    @Operation(summary = "Ask Registry to correct personal details")
    @PostMapping("/api/student/profile/change-requests")
    @PreAuthorize(STUDENT)
    public ChangeRequestView requestChange(@Valid @RequestBody ChangeRequestSubmit req) {
        return portal.requestChange(req.changes(), req.reason());
    }

    @PostMapping("/api/student/profile/change-requests/{id}/cancel")
    @PreAuthorize(STUDENT)
    public ChangeRequestView cancel(@PathVariable Long id) {
        return portal.cancelRequest(id);
    }

    @Operation(summary = "Invoices, control numbers and payment history")
    @GetMapping("/api/student/account")
    @PreAuthorize(STUDENT)
    public Statement account() {
        return portal.account();
    }

    @PostMapping("/api/student/invoices/{id}/control-number")
    @PreAuthorize(STUDENT)
    public Statement controlNumber(@PathVariable Long id) {
        return accounts.requestMyControlNumber(id);
    }

    @Operation(summary = "DEVELOPMENT ONLY — pay an invoice through the simulated GePG")
    @PostMapping("/api/student/invoices/{id}/simulate-payment")
    @PreAuthorize(STUDENT)
    public Statement simulatePayment(@PathVariable Long id, @RequestBody(required = false) SimulatedPayment req) {
        return simulator.payMine(id, req == null ? null : req.amount());
    }

    @GetMapping(value = "/api/student/receipt.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(STUDENT)
    public ResponseEntity<byte[]> receipt(@org.springframework.web.bind.annotation.RequestParam("no") String receiptNo) {
        return ReceiptService.asDownload(receiptNo, receipts.mine(receiptNo));
    }
}
