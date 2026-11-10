package tz.co.msingi.cims.platform.security;

/** Security expressions shared by controllers. */
public final class StaffAccess {

    /** Any member of an institution's staff, whatever their role (pages every member of staff may read). */
    public static final String ANY = "hasAnyRole('INSTITUTION_ADMIN','MANAGEMENT_VIEWER','DEPUTY_PRINCIPAL_ACADEMIC','ADMISSIONS_OFFICER','REGISTRAR',"
            + "'HEAD_OF_DEPARTMENT','PROGRAMME_COORDINATOR','LECTURER','ASSESSOR','EXAMINATIONS_OFFICER','BURSAR','ACCOUNTANT','HOSTEL_WARDEN',"
            + "'IPT_COORDINATOR','CLEARANCE_OFFICER','SPONSOR_OFFICER','AUDITOR',"
            + "'LIBRARIAN','HR_OFFICER','STORES_OFFICER')";

    private StaffAccess() {
    }
}
