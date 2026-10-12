package tz.co.msingi.cims.admission.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.admission.offer.OfferService;
import tz.co.msingi.cims.admission.service.AdmissionDtos.AcceptOfferRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ApplicationView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ChoicesRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.DeclineOfferRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.FeeView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.NectaFetchRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.PriorAwardRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ProfileRequest;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ProfileView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.ProgrammeEligibilityView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.QualificationView;
import tz.co.msingi.cims.admission.service.AdmissionDtos.StartApplicationRequest;
import tz.co.msingi.cims.admission.service.ApplicantProfileService;
import tz.co.msingi.cims.admission.service.ApplicationService;

import java.util.List;

/** Self-service endpoints for the signed-in applicant. */
@Tag(name = "Applicant self-service")
@RestController
@RequestMapping("/api/applicant")
@PreAuthorize("hasRole('APPLICANT')")
@RequiredArgsConstructor
public class ApplicantController {

    private final ApplicantProfileService profiles;
    private final ApplicationService applications;
    private final OfferService offers;

    @GetMapping("/profile")
    public ProfileView profile() {
        return profiles.myProfile();
    }

    @PutMapping("/profile")
    public ProfileView updateProfile(@Valid @RequestBody ProfileRequest req) {
        return profiles.updateProfile(req);
    }

    @GetMapping("/qualifications")
    public List<QualificationView> qualifications() {
        return profiles.myQualifications();
    }

    @Operation(summary = "Fetch CSEE/ACSEE results from NECTA by index number")
    @PostMapping("/qualifications/necta")
    @ResponseStatus(HttpStatus.CREATED)
    public QualificationView fetchNecta(@Valid @RequestBody NectaFetchRequest req) {
        return profiles.fetchNectaResults(req);
    }

    @Operation(summary = "Look up an NTA award at NACTVET by registration number and graduation year")
    @PostMapping("/qualifications/nta")
    @ResponseStatus(HttpStatus.CREATED)
    public QualificationView lookupNtaAward(@Valid @RequestBody tz.co.msingi.cims.admission.service.AdmissionDtos.NtaAwardRequest req) {
        return profiles.lookupNtaAward(req);
    }

    @Operation(summary = "Declare another (non-NTA) award; checked by the Admissions Office")
    @PostMapping("/qualifications/other")
    @ResponseStatus(HttpStatus.CREATED)
    public QualificationView addOtherAward(@Valid @RequestBody tz.co.msingi.cims.admission.service.AdmissionDtos.OtherAwardRequest req) {
        return profiles.addOtherAward(req);
    }

    @Operation(summary = "Add a prior NTA (AVN) or NVA award (verified later by the Admissions Office)")
    @PostMapping("/qualifications/prior-award")
    @ResponseStatus(HttpStatus.CREATED)
    public QualificationView addPriorAward(@Valid @RequestBody PriorAwardRequest req) {
        return profiles.addPriorAward(req);
    }

    @DeleteMapping("/qualifications/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeQualification(@PathVariable Long id) {
        profiles.removeQualification(id);
    }

    @GetMapping("/applications")
    public List<ApplicationView> myApplications() {
        return applications.myApplications();
    }

    @PostMapping("/applications")
    public ApplicationView start(@Valid @RequestBody StartApplicationRequest req) {
        return applications.start(req.windowId());
    }

    @GetMapping("/applications/{id}")
    public ApplicationView application(@PathVariable Long id) {
        return applications.myApplication(id);
    }

    @Operation(summary = "Get a GePG control number for the application fee")
    @PostMapping("/applications/{id}/fee")
    public FeeView requestFee(@PathVariable Long id) {
        return applications.requestFee(id);
    }

    @GetMapping("/applications/{id}/fee")
    public FeeView fee(@PathVariable Long id) {
        return applications.fee(id);
    }

    @Operation(summary = "DEVELOPMENT ONLY — simulate paying the control number (simulated GePG)")
    @PostMapping("/applications/{id}/fee/simulate-payment")
    public FeeView simulatePayment(@PathVariable Long id) {
        return applications.simulatePayment(id);
    }

    @Operation(summary = "Eligibility for each programme in a window, with reasons")
    @GetMapping("/eligibility")
    public List<ProgrammeEligibilityView> eligibility(@RequestParam Long windowId) {
        return applications.eligibility(windowId);
    }

    @PutMapping("/applications/{id}/choices")
    public ApplicationView setChoices(@PathVariable Long id, @Valid @RequestBody ChoicesRequest req) {
        return applications.setChoices(id, req.windowProgrammeIds());
    }

    @PostMapping("/applications/{id}/submit")
    public ApplicationView submit(@PathVariable Long id) {
        return applications.submit(id);
    }

    @Operation(summary = "Accept the admission offer (multiple-admission applicants give NACTVET's confirmation code)")
    @PostMapping("/applications/{id}/offer/accept")
    public ApplicationView acceptOffer(@PathVariable Long id, @Valid @RequestBody AcceptOfferRequest req) {
        offers.accept(id, req.confirmationCode());
        return applications.myApplication(id);
    }

    @Operation(summary = "Decline the admission offer; the place is released to the next selection round")
    @PostMapping("/applications/{id}/offer/decline")
    public ApplicationView declineOffer(@PathVariable Long id, @Valid @RequestBody DeclineOfferRequest req) {
        offers.decline(id, req.reason());
        return applications.myApplication(id);
    }
}
