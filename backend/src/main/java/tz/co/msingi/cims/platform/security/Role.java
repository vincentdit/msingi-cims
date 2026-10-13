package tz.co.msingi.cims.platform.security;

/** System roles (Document 03, section 3.1). Data scope is held on the role assignment. */
public enum Role {
    PLATFORM_ADMIN,
    INSTITUTION_ADMIN,
    MANAGEMENT_VIEWER,
    DEPUTY_PRINCIPAL_ACADEMIC,
    ADMISSIONS_OFFICER,
    REGISTRAR,
    HEAD_OF_DEPARTMENT,
    PROGRAMME_COORDINATOR,
    LECTURER,
    ASSESSOR,
    EXAMINATIONS_OFFICER,
    BURSAR,
    ACCOUNTANT,
    HOSTEL_WARDEN,
    IPT_COORDINATOR,
    CLEARANCE_OFFICER,
    SPONSOR_OFFICER,
    AUDITOR,
    APPLICANT,
    STUDENT;

    public String authority() {
        return "ROLE_" + name();
    }
}
