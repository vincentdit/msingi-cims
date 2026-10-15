package tz.co.msingi.cims.platform.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Groups audited records into the areas of the institution an auditor looks at, and marks the security events. */
public final class AuditCatalog {

    private AuditCatalog() {
    }

    public static final String SECURITY = "Security";

    private static final Map<String, List<String>> AREAS = new LinkedHashMap<>();

    static {
        AREAS.put(SECURITY, List.of("UserAccount", "StaffUser"));
        AREAS.put("Admissions", List.of("AdmissionWindow", "Applicant", "Application", "SelectionRound", "AdmissionLetter",
                "NactvetVerification", "VerificationBatch", "LetterTemplate", "ImportBatch"));
        AREAS.put("Registry", List.of("Student", "SemesterRegistration", "ChangeRequest", "StudentChangeRequest", "NactvetRegistrationBatch"));
        AREAS.put("Teaching", List.of("Curriculum", "Semester", "AcademicYear", "ClassSession", "ModuleOffering", "Programme", "Department"));
        AREAS.put("Examinations", List.of("ResultSheet", "RegulationSet", "EligibilityExemption", "ResultAmendment", "ResitSession",
                "ResitRegistration", "Transcript", "NactvetSubmission"));
        AREAS.put("Finance", List.of("Invoice", "Payment", "FeeStructure", "FeeItem", "Waiver", "Refund", "Reconciliation", "SmsTopup", "GepgReconciliation",
                "Sponsorship", "Sponsor", "MusePosting", "ExternalExchange"));
        AREAS.put("Student services", List.of("Clearance", "Graduation", "Graduand", "HostelAllocation", "HostelApplication", "HostelWindow",
                "Hostel", "IptPlacement", "LogbookEntry", "IptPeriod"));
        AREAS.put("Communication", List.of("Notice", "MessageCampaign", "MessageTemplate", "MessagingSettings"));
        AREAS.put("Institution", List.of("InstitutionPhoto", "Report", "Tenant", "IntegrationSettings"));
    }

    /** Sign-ins, failed sign-ins, locked accounts, password changes and staff account changes. */
    public static final Set<String> SECURITY_ACTIONS = Set.of("LOGIN", "LOGIN_FAILED", "ACCOUNT_LOCKED", "PASSWORD_CHANGED",
            "PASSWORD_RESET", "AUDIT_EXPORTED");

    public static List<String> areas() {
        List<String> out = new java.util.ArrayList<>(AREAS.keySet());
        out.add("Other");
        return out;
    }

    public static List<String> entitiesOf(String area) {
        return AREAS.getOrDefault(area, List.of());
    }

    /** All entities named in some area (records outside these are "Other"). */
    public static List<String> knownEntities() {
        return AREAS.values().stream().flatMap(List::stream).toList();
    }

    public static String areaOf(String entity) {
        return AREAS.entrySet().stream().filter(e -> e.getValue().contains(entity)).map(Map.Entry::getKey).findFirst().orElse("Other");
    }
}
