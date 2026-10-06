package tz.co.msingi.cims.admission.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicantRepository;
import tz.co.msingi.cims.admission.domain.Applicant;
import tz.co.msingi.cims.admission.service.AdmissionDtos.OtpSentResponse;
import tz.co.msingi.cims.admission.service.AdmissionDtos.RegisterRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.RegisterResponse;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.OtpService;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.security.TokenService;
import tz.co.msingi.cims.platform.security.UserAccount;
import tz.co.msingi.cims.platform.security.UserAccountRepository;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.OffsetDateTime;
import java.util.Map;

/** Applicant self-registration with SMS verification, and password reset (BP-02 step 1). */
@Service
@RequiredArgsConstructor
public class ApplicantAccountService {

    private final UserAccountRepository users;
    private final ApplicantRepository applicants;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otp;
    private final TokenService tokens;
    private final TenantRepository tenants;
    private final AuditService audit;

    @Value("${cims.privacy.notice-version:2026-10}")
    private String privacyNoticeVersion;

    @Transactional
    public RegisterResponse register(RegisterRequest req) {
        String phone = PhoneNumbers.normalise(req.phone());
        if (users.existsByPhone(phone)) {
            throw new BusinessRuleException("PHONE_REGISTERED", "This phone number already has an account. Sign in or reset your password.");
        }
        if (req.email() != null && !req.email().isBlank() && users.existsByEmailIgnoreCase(req.email().trim())) {
            throw new BusinessRuleException("EMAIL_REGISTERED", "This email address already has an account.");
        }
        UserAccount user = new UserAccount();
        user.setUsername(phone);
        user.setPhone(phone);
        user.setEmail(blankToNull(req.email()));
        user.setFullName(fullName(req.firstName(), req.middleName(), req.surname()));
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setUserType(UserAccount.UserType.APPLICANT);
        user.setStatus(UserAccount.Status.PENDING_VERIFICATION);
        user.grant(Role.APPLICANT);
        user = users.save(user);

        Applicant a = new Applicant();
        a.setUser(user);
        a.setFirstName(req.firstName().trim());
        a.setMiddleName(blankToNull(req.middleName()));
        a.setSurname(req.surname().trim());
        a.setPhone(phone);
        a.setEmail(user.getEmail());
        a.setPrivacyConsentAt(OffsetDateTime.now());
        a.setPrivacyNoticeVersion(privacyNoticeVersion);
        applicants.save(a);

        String code = otp.issue(user, OtpService.PURPOSE_PHONE_VERIFICATION, institutionName());
        audit.recordAs(user.getId(), user.getFullName(), "REGISTER", "Applicant", a.getId(), Map.of("privacyNotice", privacyNoticeVersion));
        return new RegisterResponse(user.getId(), PhoneNumbers.mask(phone),
                "We sent a verification code by SMS to " + PhoneNumbers.mask(phone) + ".", devCode(code));
    }

    @Transactional(noRollbackFor = BusinessRuleException.class)
    public TokenService.TokenResponse verifyPhone(String phoneInput, String code) {
        UserAccount user = applicantByPhone(phoneInput);
        if (user.getStatus() != UserAccount.Status.PENDING_VERIFICATION) {
            throw new BusinessRuleException("ALREADY_VERIFIED", "This phone number is already verified. Please sign in.");
        }
        otp.verify(user.getId(), OtpService.PURPOSE_PHONE_VERIFICATION, code);
        user.setStatus(UserAccount.Status.ACTIVE);
        user.setLastLoginAt(OffsetDateTime.now());
        audit.recordAs(user.getId(), user.getFullName(), "PHONE_VERIFIED", "UserAccount", user.getId(), null);
        return tokens.issue(user);
    }

    @Transactional
    public OtpSentResponse resendVerification(String phoneInput) {
        UserAccount user = applicantByPhone(phoneInput);
        if (user.getStatus() != UserAccount.Status.PENDING_VERIFICATION) {
            throw new BusinessRuleException("ALREADY_VERIFIED", "This phone number is already verified. Please sign in.");
        }
        String code = otp.issue(user, OtpService.PURPOSE_PHONE_VERIFICATION, institutionName());
        return new OtpSentResponse(PhoneNumbers.mask(user.getPhone()), "A new code has been sent.", devCode(code));
    }

    /** Always answers the same way, so the endpoint cannot be used to discover registered numbers. */
    @Transactional
    public OtpSentResponse forgotPassword(String phoneInput) {
        String phone = PhoneNumbers.normalise(phoneInput);
        String code = users.findByLogin(phone)
                .filter(u -> u.getUserType() == UserAccount.UserType.APPLICANT)
                .map(u -> otp.issue(u, OtpService.PURPOSE_PASSWORD_RESET, institutionName()))
                .orElse(null);
        return new OtpSentResponse(PhoneNumbers.mask(phone), "If the number is registered, a reset code has been sent.", devCode(code));
    }

    @Transactional(noRollbackFor = BusinessRuleException.class)
    public void resetPassword(String phoneInput, String code, String newPassword) {
        UserAccount user = applicantByPhone(phoneInput);
        otp.verify(user.getId(), OtpService.PURPOSE_PASSWORD_RESET, code);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setFailedLogins(0);
        if (user.getStatus() == UserAccount.Status.LOCKED || user.getStatus() == UserAccount.Status.PENDING_VERIFICATION) {
            user.setStatus(UserAccount.Status.ACTIVE); // the code proves control of the phone
        }
        audit.recordAs(user.getId(), user.getFullName(), "PASSWORD_RESET", "UserAccount", user.getId(), null);
    }

    private UserAccount applicantByPhone(String phoneInput) {
        String phone = PhoneNumbers.normalise(phoneInput);
        return users.findByLogin(phone)
                .filter(u -> u.getUserType() == UserAccount.UserType.APPLICANT)
                .orElseThrow(() -> new BusinessRuleException("OTP_INVALID", "The code is invalid or has expired. Request a new one."));
    }

    private String institutionName() {
        return tenants.findById(TenantContext.requireTenantId()).map(t -> t.getShortName() != null ? t.getShortName() : t.getName()).orElse("CIMS");
    }

    private String devCode(String code) {
        return otp.exposeInResponse() ? code : null;
    }

    static String fullName(String first, String middle, String surname) {
        return first.trim() + (middle == null || middle.isBlank() ? "" : " " + middle.trim()) + " " + surname.trim();
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
