package tz.co.msingi.cims.admission.offer;

/**
 * Published when an applicant accepts an admission offer (BP-04 step 5).
 * Finance raises the first-year invoice and Registry prepares registration from this event.
 */
public record OfferAcceptedEvent(Long tenantId, Long applicationId, Long applicantId, Long windowProgrammeId) {
}
