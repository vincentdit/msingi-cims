package tz.co.msingi.cims.admission.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.admission.service.AdmissionDtos.LoginRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.OtpSentResponse;
import tz.co.msingi.cims.admission.service.AdmissionDtos.PhoneRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.RegisterRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.RegisterResponse;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ResetPasswordRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.VerifyRequest;
import tz.co.msingi.cims.admission.service.ApplicantAccountService;
import tz.co.msingi.cims.admission.service.PhoneNumbers;
import tz.co.msingi.cims.platform.security.LoginService;
import tz.co.msingi.cims.platform.security.TokenService;
import tz.co.msingi.cims.platform.security.UserAccount;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import tz.co.msingi.cims.admission.service.AdmissionDtos.NectaCandidateView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.NectaLookupRequest;
import tz.co.msingi.cims.common.exception.TooManyRequestsException;
import tz.co.msingi.cims.common.web.RateLimiter;

import java.time.Clock;
import java.util.Map;

@Tag(name = "Applicant accounts")
@RestController
@RequestMapping("/api/applicant-auth")
@RequiredArgsConstructor
public class ApplicantAuthController {

    private final ApplicantAccountService accounts;
    private final LoginService loginService;

    private RateLimiter lookupLimiter;

    @Value("${cims.necta.lookup-rate-limit:20}")
    void setLookupLimit(int perMinute) {
        this.lookupLimiter = new RateLimiter(perMinute, 60_000, Clock.systemUTC());
    }

    @Operation(summary = "Look up a Form Four (CSEE) candidate at NECTA by index number and year, to confirm before registering")
    @PostMapping("/necta-lookup")
    public NectaCandidateView lookup(@Valid @RequestBody NectaLookupRequest req, HttpServletRequest http) {
        if (!lookupLimiter.tryAcquire(http.getRemoteAddr())) {
            throw new TooManyRequestsException("Too many look-ups. Please wait a minute and try again.");
        }
        return accounts.lookup(req);
    }

    @Operation(summary = "Create an applicant account from the NECTA index number and year; a verification code is sent by SMS")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest req) {
        return accounts.register(req);
    }

    @Operation(summary = "Verify the phone number with the SMS code; returns an access token")
    @PostMapping("/verify")
    public TokenService.TokenResponse verify(@Valid @RequestBody VerifyRequest req) {
        return accounts.verifyPhone(req.phone(), req.code());
    }

    @Operation(summary = "Send a new verification code")
    @PostMapping("/resend-code")
    public OtpSentResponse resend(@Valid @RequestBody PhoneRequest req) {
        return accounts.resendVerification(req.phone());
    }

    @Operation(summary = "Applicant sign-in with phone number, email or Form Four index number, and password")
    @PostMapping("/login")
    public TokenService.TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return loginService.login(accounts.resolveLogin(req.login()), req.password(), UserAccount.UserType.APPLICANT);
    }

    @Operation(summary = "Request a password reset code by SMS")
    @PostMapping("/forgot-password")
    public OtpSentResponse forgot(@Valid @RequestBody PhoneRequest req) {
        return accounts.forgotPassword(req.phone());
    }

    @Operation(summary = "Set a new password using the SMS code")
    @PostMapping("/reset-password")
    public Map<String, String> reset(@Valid @RequestBody ResetPasswordRequest req) {
        accounts.resetPassword(req.phone(), req.code(), req.newPassword());
        return Map.of("message", "Your password has been changed. You can now sign in.");
    }
}
