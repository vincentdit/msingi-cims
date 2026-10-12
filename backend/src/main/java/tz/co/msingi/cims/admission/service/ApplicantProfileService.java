package tz.co.msingi.cims.admission.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicantQualificationRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicantRepository;
import tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicationRepository;
import tz.co.msingi.cims.admission.domain.Applicant;
import tz.co.msingi.cims.admission.domain.ApplicantQualification;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.service.AdmissionDtos.NectaFetchRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.PriorAwardRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ProfileRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ProfileView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.QualificationView;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.integration.necta.NectaClient;
import tz.co.msingi.cims.reference.Address;
import tz.co.msingi.cims.reference.GeographyService;
import tz.co.msingi.cims.reference.PrimarySchoolDirectory;
import tz.co.msingi.cims.reference.ProfileOptions;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Applicant profile and qualifications (NECTA results, prior NTA/NVA awards). */
@Service
@RequiredArgsConstructor
public class ApplicantProfileService {

    private final ApplicantRepository applicants;
    private final ApplicantQualificationRepository qualifications;
    private final ApplicationRepository applications;
    private final NectaClient necta;
    private final GeographyService geo;
    private final PrimarySchoolDirectory primarySchools;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public Applicant currentApplicant() {
        return applicants.findByUserId(CurrentUser.requireId())
                .orElseThrow(() -> new NotFoundException("Applicant profile not found for this account"));
    }

    @Transactional(readOnly = true)
    public ProfileView myProfile() {
        return ProfileView.of(currentApplicant());
    }

    @Transactional
    public ProfileView updateProfile(ProfileRequest req) {
        Applicant a = currentApplicant();
        requireNoSubmittedApplication(a);
        if (!a.isNamesFromNecta()) { // names registered from NECTA stay as NECTA has them
            a.setFirstName(req.firstName().trim());
            a.setMiddleName(ApplicantAccountService.blankToNull(req.middleName()));
            a.setSurname(req.surname().trim());
        }
        a.setSex(req.sex());
        a.setDateOfBirth(req.dateOfBirth());
        a.setNationality(ApplicantAccountService.blankToNull(req.nationality()));
        a.setEmail(ApplicantAccountService.blankToNull(req.email()));
        Address home = Address.check(geo, "", req.region(), req.district(), req.ward(), req.street());
        Address kin = Address.check(geo, "Next of kin", req.nextOfKinRegion(), req.nextOfKinDistrict(), req.nextOfKinWard(), req.nextOfKinStreet());
        a.setDisability(Address.choice(ProfileOptions.DISABILITIES, req.disability(), a.getDisability(), "INVALID_DISABILITY", "impairment"));
        a.setNextOfKinRelation(Address.choice(ProfileOptions.RELATIONSHIPS, req.nextOfKinRelation(), a.getNextOfKinRelation(), "INVALID_RELATIONSHIP", "relationship"));
        a.setRegion(home.region());
        a.setDistrict(home.district());
        a.setWard(home.ward());
        a.setStreet(home.street());
        a.setPostalAddress(ApplicantAccountService.blankToNull(req.postalAddress()));
        a.setNextOfKinName(ApplicantAccountService.blankToNull(req.nextOfKinName()));
        a.setNextOfKinPhone(req.nextOfKinPhone() == null || req.nextOfKinPhone().isBlank() ? null : PhoneNumbers.normalise(req.nextOfKinPhone()));
        a.setNextOfKinEmail(ApplicantAccountService.blankToNull(req.nextOfKinEmail()));
        a.setNextOfKinAddress(ApplicantAccountService.blankToNull(req.nextOfKinAddress()));
        a.setNextOfKinRegion(kin.region());
        a.setNextOfKinDistrict(kin.district());
        a.setNextOfKinWard(kin.ward());
        a.setNextOfKinStreet(kin.street());
        // primary school: one from NECTA's PSLE list (by code), or typed when it is not on the list
        Optional<PrimarySchoolDirectory.School> listed = primarySchools.byCode(req.primarySchoolCode())
                .filter(x -> req.primarySchool() == null || req.primarySchool().isBlank() || x.name().equalsIgnoreCase(req.primarySchool().trim()));
        a.setPrimarySchool(listed.map(PrimarySchoolDirectory.School::name).orElse(ApplicantAccountService.blankToNull(req.primarySchool())));
        a.setPrimarySchoolCode(listed.map(PrimarySchoolDirectory.School::code).orElse(null));
        // secondary schools from NECTA results stay as NECTA has them
        if (!a.isOlevelFromNecta()) a.setOlevelSchool(upper(req.olevelSchool()));
        if (!a.isAlevelFromNecta()) a.setAlevelSchool(upper(req.alevelSchool()));
        a.getUser().setFullName(a.fullName());
        audit.record("UPDATE", "Applicant", a.getId(), null);
        return ProfileView.of(a);
    }

    @Transactional(readOnly = true)
    public List<QualificationView> myQualifications() {
        return qualifications.findByApplicantIdOrderByIdAsc(currentApplicant().getId()).stream().map(QualificationView::of).toList();
    }

    /** Retrieves CSEE/ACSEE results from NECTA and stores them as verified (BR-04). */
    @Transactional
    public QualificationView fetchNectaResults(NectaFetchRequest req) {
        if (req.type() != ApplicantQualification.Type.CSEE && req.type() != ApplicantQualification.Type.ACSEE) {
            throw new BusinessRuleException("INVALID_TYPE", "NECTA results are CSEE or ACSEE");
        }
        Applicant a = currentApplicant();
        requireNoSubmittedApplication(a);
        String index = req.indexNumber().trim().toUpperCase();
        if (qualifications.existsByApplicantIdAndTypeAndIndexNumber(a.getId(), req.type(), index)) {
            throw new BusinessRuleException("QUALIFICATION_EXISTS", "These results are already on your application");
        }
        NectaClient.NectaResult r = necta.fetchResults(NectaClient.ExamType.valueOf(req.type().name()), index, req.year())
                .orElseThrow(() -> new BusinessRuleException("NECTA_NOT_FOUND",
                        "No NECTA results found for " + index + " (" + req.year() + "). Check the index number and year."));
        ApplicantQualification q = fromNecta(a, req.type(), r);
        takeSchool(a, req.type(), r);
        qualifications.save(q);
        audit.record("NECTA_RESULTS_ADDED", "Applicant", a.getId(), Map.of("index", index, "year", req.year()));
        return QualificationView.of(q);
    }

    /** The secondary school of the first CSEE / ACSEE results fetched from NECTA becomes the O-level / A-level school. */
    static void takeSchool(Applicant a, ApplicantQualification.Type type, NectaClient.NectaResult r) {
        if (r.centreName() == null) return;
        if (type == ApplicantQualification.Type.CSEE && !a.isOlevelFromNecta()) {
            a.setOlevelSchool(r.centreName());
            a.setOlevelFromNecta(true);
        } else if (type == ApplicantQualification.Type.ACSEE && !a.isAlevelFromNecta()) {
            a.setAlevelSchool(r.centreName());
            a.setAlevelFromNecta(true);
        }
    }

    private static String upper(String s) {
        String v = ApplicantAccountService.blankToNull(s);
        return v == null ? null : v.toUpperCase();
    }

    /** A verified CSEE/ACSEE qualification from NECTA's answer. */
    static ApplicantQualification fromNecta(Applicant a, ApplicantQualification.Type type, NectaClient.NectaResult r) {
        ApplicantQualification q = new ApplicantQualification();
        q.setApplicant(a);
        q.setType(type);
        q.setIndexNumber(r.indexNumber());
        q.setExamYear((short) r.year());
        q.setCentreName(r.centreName());
        q.setDivision(r.division());
        q.setPoints(r.points() == null ? null : r.points().shortValue());
        q.setCandidateName(r.candidateName());
        q.setSource(ApplicantQualification.Source.NECTA);
        q.setVerified(true);
        q.setVerifiedAt(OffsetDateTime.now());
        r.subjects().forEach(sub -> q.addSubject(sub.code(), sub.name(), sub.grade()));
        return q;
    }

    /**
     * Records a prior NTA (by AVN) or NVA award. It stays unverified until checked by an
     * Admissions Officer (or automatically once the NACTVET AVN interface is connected).
     */
    @Transactional
    public QualificationView addPriorAward(PriorAwardRequest req) {
        if (req.type() != ApplicantQualification.Type.NTA && req.type() != ApplicantQualification.Type.NVA) {
            throw new BusinessRuleException("INVALID_TYPE", "Prior awards are NTA or NVA");
        }
        boolean nva = req.type() == ApplicantQualification.Type.NVA;
        if ((nva && (req.level() < 1 || req.level() > 3)) || (!nva && (req.level() < 4 || req.level() > 8))) {
            throw new BusinessRuleException("INVALID_AWARD_LEVEL", nva ? "NVA levels are 1–3" : "NTA levels are 4–8");
        }
        Applicant a = currentApplicant();
        requireNoSubmittedApplication(a);
        ApplicantQualification q = new ApplicantQualification();
        q.setApplicant(a);
        q.setType(req.type());
        q.setIndexNumber(req.number().trim().toUpperCase());
        q.setAwardLevel(req.level());
        q.setExamYear(req.year());
        q.setSource(ApplicantQualification.Source.MANUAL);
        q.setVerified(false);
        qualifications.save(q);
        audit.record("PRIOR_AWARD_ADDED", "Applicant", a.getId(), Map.of("type", req.type().name(), "number", q.getIndexNumber()));
        return QualificationView.of(q);
    }

    @Transactional
    public void removeQualification(Long id) {
        Applicant a = currentApplicant();
        requireNoSubmittedApplication(a);
        ApplicantQualification q = qualifications.findById(id)
                .filter(x -> x.getApplicant().getId().equals(a.getId()))
                .orElseThrow(() -> new NotFoundException("Qualification", id));
        if (QualificationView.of(q).registration()) {
            throw new BusinessRuleException("QUALIFICATION_LOCKED", "These are the Form Four results you registered with, so they cannot be removed");
        }
        qualifications.delete(q);
        // the school came from these results: take it from another NECTA sitting, or let the applicant type it
        if (q.getSource() == ApplicantQualification.Source.NECTA && (q.getType() == ApplicantQualification.Type.CSEE || q.getType() == ApplicantQualification.Type.ACSEE)) {
            boolean olevel = q.getType() == ApplicantQualification.Type.CSEE;
            Optional<ApplicantQualification> other = qualifications.findByApplicantIdOrderByIdAsc(a.getId()).stream()
                    .filter(x -> !x.getId().equals(q.getId()) && x.getType() == q.getType() && x.getSource() == ApplicantQualification.Source.NECTA
                            && x.getCentreName() != null).findFirst();
            if (olevel) {
                a.setOlevelSchool(other.map(ApplicantQualification::getCentreName).orElse(null));
                a.setOlevelFromNecta(other.isPresent());
            } else {
                a.setAlevelSchool(other.map(ApplicantQualification::getCentreName).orElse(null));
                a.setAlevelFromNecta(other.isPresent());
            }
        }
        audit.record("QUALIFICATION_REMOVED", "Applicant", a.getId(), Map.of("index", q.getIndexNumber()));
    }

    private void requireNoSubmittedApplication(Applicant a) {
        boolean submitted = applications.findByApplicantIdOrderByCreatedAtDesc(a.getId()).stream()
                .anyMatch(app -> !app.isEditable() && app.getStatus() != Application.Status.WITHDRAWN);
        if (submitted) {
            throw new BusinessRuleException("APPLICATION_SUBMITTED",
                    "Your details are locked because an application has been submitted. Contact the Admissions Office for corrections.");
        }
    }
}
