package tz.co.msingi.cims.admission.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicantRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicantQualificationRepository;
import tz.co.msingi.cims.admission.domain.Applicant;
import tz.co.msingi.cims.admission.domain.ApplicantQualification;
import tz.co.msingi.cims.admission.service.AdmissionDtos.NectaCandidateView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.NectaLookupRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.SubjectView;
import tz.co.msingi.cims.integration.necta.NectaClient;
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
import java.util.List;
import java.util.Map;

/** Applicant self-registration with the NECTA Form Four index number, SMS verification, and password reset (BP-02 step 1). */
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
    private final NectaClient necta;
    private final ApplicantQualificationRepository qualifications;

    @Value("${cims.privacy.notice-version:2026-10}")
    private String privacyNoticeVersion;

    /** What NECTA holds for the index number, for the applicant to confirm it is them before registering. */
    @Transactional(readOnly = true)
    public NectaCandidateView lookup(NectaLookupRequest req) {
        NectaIndex idx = NectaIndex.parse(req.indexNumber(), req.examYear());
        NectaClient.NectaResult r = fetchCandidate(idx);
        boolean registered = applicants.findByNectaIndexNumberAndNectaExamYear(idx.indexNumber(), (short) idx.year())
                .map(a -> a.getUser().getStatus() != UserAccount.Status.PENDING_VERIFICATION).orElse(false);
        return new NectaCandidateView(idx.indexNumber(), idx.year(), r.firstName(), r.middleName(), r.surname(), r.candidateName(),
                r.sex(), r.centreName(), r.division(), r.points(),
                r.subjects().stream().map(x -> new SubjectView(x.code(), x.name(), x.grade())).toList(), registered);
    }

    /**
     * Creates the applicant account from the Form Four index number and year: names and sex come from NECTA
     * (fetched again here, never taken from the browser) and the CSEE results are stored as verified.
     * A registration never confirmed by SMS can be started again for the same index number.
     */
    @Transactional
    public RegisterResponse register(RegisterRequest req) {
        NectaIndex idx = NectaIndex.parse(req.indexNumber(), req.examYear());
        String phone = PhoneNumbers.normalise(req.phone());
        String email = blankToNull(req.email());
        Applicant existing = applicants.findByNectaIndexNumberAndNectaExamYear(idx.indexNumber(), (short) idx.year()).orElse(null);
        if (existing != null && existing.getUser().getStatus() != UserAccount.Status.PENDING_VERIFICATION) {
            throw new BusinessRuleException("INDEX_REGISTERED", "An account already exists for " + idx
                    + ". Sign in with your phone number or index number, or reset your password.");
        }
        Long keep = existing == null ? null : existing.getUser().getId();
        if (users.findByLogin(phone).filter(u -> !u.getId().equals(keep)).isPresent()) {
            throw new BusinessRuleException("PHONE_REGISTERED", "This phone number already has an account. Sign in or reset your password.");
        }
        if (email != null && users.findByLogin(email).filter(u -> !u.getId().equals(keep)).isPresent()) {
            throw new BusinessRuleException("EMAIL_REGISTERED", "This email address already has an account.");
        }
        NectaClient.NectaResult r = fetchCandidate(idx);

        UserAccount user = existing == null ? new UserAccount() : existing.getUser();
        user.setUsername(phone);
        user.setPhone(phone);
        user.setEmail(email);
        user.setFullName(r.candidateName());
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        if (existing == null) {
            user.setUserType(UserAccount.UserType.APPLICANT);
            user.setStatus(UserAccount.Status.PENDING_VERIFICATION);
            user.grant(Role.APPLICANT);
        }
        user = users.save(user);

        Applicant a = existing == null ? new Applicant() : existing;
        a.setUser(user);
        a.setFirstName(r.firstName());
        a.setMiddleName(r.middleName());
        a.setSurname(r.surname());
        if (r.sex() != null) a.setSex(r.sex());
        a.setPhone(phone);
        a.setEmail(email);
        a.setNectaIndexNumber(idx.indexNumber());
        a.setNectaExamYear((short) idx.year());
        a.setNamesFromNecta(true);
        a.setPrivacyConsentAt(OffsetDateTime.now());
        a.setPrivacyNoticeVersion(privacyNoticeVersion);
        a = applicants.save(a);
        if (!qualifications.existsByApplicantIdAndTypeAndIndexNumber(a.getId(), ApplicantQualification.Type.CSEE, idx.indexNumber())) {
            qualifications.save(ApplicantProfileService.fromNecta(a, ApplicantQualification.Type.CSEE, r));
        }
        a.setOlevelFromNecta(false); // the school of the index number registered with
        ApplicantProfileService.takeSchool(a, ApplicantQualification.Type.CSEE, r);

        String code = otp.issue(user, OtpService.PURPOSE_PHONE_VERIFICATION, institutionName());
        audit.recordAs(user.getId(), user.getFullName(), existing == null ? "REGISTER" : "REGISTER_RESTARTED", "Applicant", a.getId(),
                Map.of("privacyNotice", privacyNoticeVersion, "index", idx.indexNumber(), "year", idx.year()));
        return new RegisterResponse(user.getId(), PhoneNumbers.mask(phone),
                "We sent a verification code by SMS to " + PhoneNumbers.mask(phone) + ".", devCode(code), r.candidateName());
    }

    /**
     * Applicants sign in with their phone number, email or Form Four index number (with or without the year);
     * returns the login to check the password against.
     */
    @Transactional(readOnly = true)
    public String resolveLogin(String login) {
        String l = login.trim();
        if (l.contains("@")) return l;
        if (!NectaIndex.looksLikeIndex(l)) return PhoneNumbers.normalise(l);
        String index = NectaIndex.indexOnly(l);
        Integer year = NectaIndex.embeddedYear(l);
        List<Applicant> found = applicants.findByNectaIndexNumber(index).stream()
                .filter(a -> year == null || a.getNectaExamYear().intValue() == year).toList();
        // nothing found, or the same index number in two years: fall through to a failed sign-in
        return found.size() == 1 ? found.get(0).getUser().getPhone() : l;
    }

    private NectaClient.NectaResult fetchCandidate(NectaIndex idx) {
        NectaClient.NectaResult r = necta.fetchResults(NectaClient.ExamType.CSEE, idx.indexNumber(), idx.year())
                .orElseThrow(() -> new BusinessRuleException("NECTA_NOT_FOUND", "NECTA has no Form Four (CSEE) candidate " + idx
                        + ". Check the index number and the year on your certificate."));
        if (r.firstName() == null || r.surname() == null) {
            throw new BusinessRuleException("NECTA_NAMES_MISSING", "NECTA's record for " + idx + " has no candidate name. Contact the Admissions Office.");
        }
        return r;
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
