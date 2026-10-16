package tz.co.msingi.cims.registry.status;

import java.time.LocalDate;

/**
 * Published, inside the same transaction, when an approved status change takes effect, so that the bursary (open fees) and the
 * hostel office (beds) can follow up without the registry knowing about them.
 */
public record StudentStatusAppliedEvent(Long changeId, Long studentId, StatusChange.Type type, LocalDate effectiveDate, String reference,
                                        Long decidedById, String decidedByName) {
}
