package tz.co.msingi.cims.admission.service;

import java.util.Map;

/** Plain words for the audit actions that make up an application's history. */
public final class HistoryLabels {

    private HistoryLabels() {
    }

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("REGISTER", "Signed up with the NECTA index number"),
            Map.entry("START", "Application started"),
            Map.entry("UPDATE", "Personal details saved"),
            Map.entry("AMEND_PROFILE", "Personal details changed after submitting"),
            Map.entry("NECTA_RESULTS_ADDED", "Results added from NECTA"),
            Map.entry("NTA_AWARD_ADDED", "NTA award added"),
            Map.entry("NVA_AWARD_ADDED", "NVA award declared"),
            Map.entry("OTHER_AWARD_ADDED", "Other award declared"),
            Map.entry("QUALIFICATION_VERIFIED", "Declared qualification verified"),
            Map.entry("QUALIFICATION_REJECTED", "Declared qualification not accepted"),
            Map.entry("QUALIFICATION_REMOVED", "Qualification removed"),
            Map.entry("FEE_PAID", "Application fee paid"),
            Map.entry("SET_CHOICES", "Programme choices saved"),
            Map.entry("AMEND_CHOICES", "Programme choices changed after submitting"),
            Map.entry("SUBMIT", "Application submitted"),
            Map.entry("SELECTED", "Selected"),
            Map.entry("OFFER_ACCEPTED", "Offer accepted"),
            Map.entry("OFFER_DECLINED", "Offer declined"),
            Map.entry("WITHDRAW", "Application withdrawn"),
            Map.entry("APPLICATION_FILE_PRINTED", "Application file printed"),
            Map.entry("DOCUMENT_UPLOADED", "Document uploaded"),
            Map.entry("DOCUMENT_REMOVED", "Document removed"),
            Map.entry("DOCUMENT_VIEWED", "Document opened by the office"),
            Map.entry("DOCUMENT_ACCEPTED", "Document accepted"),
            Map.entry("DOCUMENT_REJECTED", "Document not accepted"));

    public static String of(String action) {
        String known = LABELS.get(action);
        if (known != null) return known;
        String t = action.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}
