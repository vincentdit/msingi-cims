package tz.co.msingi.cims.exams;

/** Published (inside the deciding transaction) when the administrator approves or rejects a result amendment. */
public record AmendmentDecided(Long amendmentId, boolean approved, String note) {
}
