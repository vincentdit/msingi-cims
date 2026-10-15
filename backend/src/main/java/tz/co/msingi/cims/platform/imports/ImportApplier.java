package tz.co.msingi.cims.platform.imports;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.domain.Payment;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.imports.ImportService.Kind;
import tz.co.msingi.cims.platform.numbering.NumberSequenceService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Writes a checked import into CIMS (inside the sign-off transaction). */
@Component
@RequiredArgsConstructor
class ImportApplier {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final BillingService billing;
    private final NumberSequenceService sequences;
    private final Messaging messaging;
    private final tz.co.msingi.cims.integration.necta.NectaClient necta;
    private final tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicantRepository applicants;
    private final tz.co.msingi.cims.admission.domain.AdmissionRepositories.ApplicantQualificationRepository qualifications;
    private final SecureRandom random = new SecureRandom();

    Map<String, Object> apply(Kind kind, Long batchId, List<Map<String, String>> rows, Long windowId, String body) {
        return switch (kind) {
            case STUDENTS -> students(batchId, rows);
            case RESULTS -> results(batchId, rows);
            case BALANCES -> balances(rows);
            case SELECTIONS -> selections(batchId, rows, windowId, body);
        };
    }

    // ---------------------------------------------------------------- students

    private Map<String, Object> students(Long batchId, List<Map<String, String>> rows) {
        List<String> yearsAdded = new ArrayList<>();
        int accounts = 0;
        for (Map<String, String> r : rows) {
            Long programme = id("select id from programmes where upper(code) = upper(?)", r.get("programmeCode"));
            Long intake = id("select id from intakes where upper(code) = upper(?)", r.get("intakeCode"));
            Long year = academicYear(r.get("entryYear"), yearsAdded);
            Long campus = r.get("campusCode") == null
                    ? jdbc.queryForList("select id from campuses where main", Long.class).stream().findFirst().orElse(null)
                    : id("select id from campuses where upper(code) = upper(?)", r.get("campusCode"));
            String name = String.join(" ", Objects.toString(r.get("firstName"), ""), Objects.toString(r.get("middleName"), ""), r.get("surname")).replaceAll("\\s+", " ").trim();
            // a sign-in for the student portal: by registration number, or the phone once they set a password with it
            boolean phoneFree = count("select count(*) from user_accounts where phone = ?", r.get("phone")) == 0;
            Long user = null;
            if (count("select count(*) from user_accounts where lower(username) = lower(?)", r.get("registrationNo")) == 0) {
                user = jdbc.queryForObject("""
                        insert into user_accounts (tenant_id, username, email, phone, full_name, password_hash, user_type, status)
                        values (cims_current_tenant(), ?, ?, ?, ?, ?, 'APPLICANT', 'ACTIVE') returning id""", Long.class,
                        r.get("registrationNo"), r.get("email") != null && count("select count(*) from user_accounts where lower(email) = lower(?)", r.get("email")) == 0 ? r.get("email") : null,
                        phoneFree ? r.get("phone") : null, name, encoder.encode(randomSecret()));
                jdbc.update("insert into user_roles (user_id, role) values (?, 'STUDENT')", user);
                accounts++;
            }
            jdbc.update("""
                    insert into students (tenant_id, user_id, registration_no, first_name, middle_name, surname, sex, date_of_birth, nationality, phone,
                      email, programme_id, entry_academic_year_id, intake_id, year_of_study, status, admitted_at, admitted_by_name, campus_id, import_batch_id)
                    values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?::date, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?)""",
                    user, r.get("registrationNo"), r.get("firstName"), r.get("middleName"), r.get("surname"), r.get("sex"), r.get("dateOfBirth"),
                    r.get("nationality"), r.get("phone"), r.get("email"), programme, year, intake, Short.parseShort(r.get("yearOfStudy")),
                    r.get("status"), "Data migration (" + CurrentUser.name() + ")", campus, batchId);
        }
        return Map.of("students", rows.size(), "accounts", accounts, "academicYearsAdded", yearsAdded);
    }

    private Long academicYear(String code, List<String> added) {
        List<Long> found = jdbc.queryForList("select id from academic_years where code = ?", Long.class, code);
        if (!found.isEmpty()) return found.get(0);
        int start = Integer.parseInt(code.substring(0, 4));
        added.add(code);
        return jdbc.queryForObject("insert into academic_years (tenant_id, code, start_date, end_date) values (cims_current_tenant(), ?, ?, ?) returning id",
                Long.class, code, LocalDate.of(start, 9, 1), LocalDate.of(start + 1, 8, 31));
    }

    // ---------------------------------------------------------------- results

    private Map<String, Object> results(Long batchId, List<Map<String, String>> rows) {
        Map<String, Long> semesters = new LinkedHashMap<>();
        for (Map<String, String> r : rows) {
            String key = r.get("registrationNo").toUpperCase() + "|" + r.get("academicYear") + "|" + r.get("semester");
            Long sem = semesters.computeIfAbsent(key, k -> jdbc.queryForObject("""
                    insert into legacy_semester_results (tenant_id, student_id, academic_year, semester_no, year_of_study, gpa, cgpa, decision, batch_id)
                    values (cims_current_tenant(), (select id from students where upper(registration_no) = upper(?)), ?, ?, ?, ?, ?, ?, ?) returning id""",
                    Long.class, r.get("registrationNo"), r.get("academicYear"), Short.parseShort(r.get("semester")), Short.parseShort(r.get("yearOfStudy")),
                    ImportService.decimal(r.get("gpa")), ImportService.decimal(r.get("cgpa")), r.get("decision"), batchId));
            jdbc.update("""
                    insert into legacy_module_results (tenant_id, semester_result_id, module_code, module_title, credits, marks, grade, points)
                    values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?)""",
                    sem, r.get("moduleCode"), r.get("moduleTitle"), ImportService.decimal(r.get("credits")), ImportService.decimal(r.get("marks")),
                    r.get("grade").toUpperCase(), ImportService.decimal(r.get("points")));
        }
        long students = rows.stream().map(r -> r.get("registrationNo").toUpperCase()).distinct().count();
        return Map.of("students", students, "semesters", semesters.size(), "modules", rows.size());
    }

    // ---------------------------------------------------------------- balances

    private Map<String, Object> balances(List<Map<String, String>> rows) {
        BigDecimal owed = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        int invoices = 0, credits = 0;
        for (Map<String, String> r : rows) {
            Map<String, Object> s = jdbc.queryForMap("""
                    select id, trim(first_name || ' ' || coalesce(middle_name || ' ', '') || surname) as name, phone, email
                    from students where upper(registration_no) = upper(?)""", r.get("registrationNo"));
            BigDecimal amount = new BigDecimal(r.get("amount"));
            String asAt = DATE.format(LocalDate.parse(r.get("asAt")));
            String desc = r.get("description") != null ? r.get("description")
                    : (amount.signum() > 0 ? "Balance brought forward as at " : "Credit brought forward as at ") + asAt;
            Long studentId = ((Number) s.get("id")).longValue();
            Invoice inv = billing.createInvoice(new BillingService.NewInvoice(Invoice.PayerType.STUDENT, studentId, (String) s.get("name"),
                    (String) s.get("phone"), (String) s.get("email"), "OPENING_BALANCE", null, desc,
                    List.of(new BillingService.NewInvoice.Line("OPENING_BALANCE", desc, amount.signum() > 0 ? amount : BigDecimal.ZERO))));
            if (amount.signum() > 0) {
                owed = owed.add(amount);
                invoices++;
            } else {
                // money the student paid in excess before CIMS: held as credit and used against the next invoice
                billing.postDirect(inv, amount.negate(), Payment.Source.INTERNAL, "Opening balance", "Credit brought forward " + asAt, null,
                        LocalDate.parse(r.get("asAt")).atStartOfDay().atOffset(ZoneOffset.ofHours(3)));
                credit = credit.add(amount.negate());
                credits++;
            }
        }
        return Map.of("balancesOwed", invoices, "amountOwed", owed, "credits", credits, "amountInCredit", credit);
    }

    // ---------------------------------------------------------------- external selections

    private Map<String, Object> selections(Long batchId, List<Map<String, String>> rows, Long windowId, String body) {
        Map<String, Object> w = jdbc.queryForMap("""
                select ay.code as year, w.name from admission_windows w join academic_years ay on ay.id = w.academic_year_id where w.id = ?""", windowId);
        String yearPart = ((String) w.get("year")).substring(0, 4);
        String prefix = TenantContext.require().code() + "/APP/" + yearPart + "/";
        String institution = jdbc.queryForObject("select name from tenants where id = cims_current_tenant()", String.class);
        int newAccounts = 0;
        List<String> notAtNecta = new ArrayList<>();
        for (Map<String, String> r : rows) {
            short examYear = Short.parseShort(r.get("examYear"));
            List<Long> existing = jdbc.queryForList("select id from applicants where necta_index_number = ? and necta_exam_year = ?", Long.class,
                    r.get("indexNumber"), examYear);
            Long applicant;
            if (!existing.isEmpty()) {
                applicant = existing.get(0);
                jdbc.update("update applicants set date_of_birth = coalesce(date_of_birth, ?::date), nationality = coalesce(nationality, ?) where id = ?",
                        r.get("dateOfBirth"), r.get("nationality"), applicant);
            } else {
                // as an unfinished sign-up: when the student signs up with the index number, the account is theirs (password and phone check)
                String name = String.join(" ", r.get("firstName"), Objects.toString(r.get("middleName"), ""), r.get("surname")).replaceAll("\\s+", " ").trim();
                Long user = jdbc.queryForObject("""
                        insert into user_accounts (tenant_id, username, phone, full_name, password_hash, user_type, status)
                        values (cims_current_tenant(), ?, ?, ?, ?, 'APPLICANT', 'PENDING_VERIFICATION') returning id""", Long.class,
                        r.get("phone"), r.get("phone"), name, encoder.encode(randomSecret()));
                jdbc.update("insert into user_roles (user_id, role) values (?, 'APPLICANT')", user);
                applicant = jdbc.queryForObject("""
                        insert into applicants (tenant_id, user_id, first_name, middle_name, surname, sex, date_of_birth, nationality, phone,
                          necta_index_number, necta_exam_year, names_from_necta, privacy_consent_at, privacy_notice_version)
                        values (cims_current_tenant(), ?, ?, ?, ?, ?, ?::date, ?, ?, ?, ?, false, now(), 'EXTERNAL') returning id""", Long.class,
                        user, r.get("firstName"), r.get("middleName"), r.get("surname"), r.get("sex"), r.get("dateOfBirth"), r.get("nationality"),
                        r.get("phone"), r.get("indexNumber"), examYear);
                newAccounts++;
            }
            // the Form Four results from NECTA, which NACTVET verification needs
            if (!qualifications.existsByApplicantIdAndTypeAndIndexNumber(applicant, tz.co.msingi.cims.admission.domain.ApplicantQualification.Type.CSEE, r.get("indexNumber"))) {
                var res = necta.fetchResults(tz.co.msingi.cims.integration.necta.NectaClient.ExamType.CSEE, r.get("indexNumber"), examYear);
                if (res.isPresent()) {
                    qualifications.save(tz.co.msingi.cims.admission.service.ApplicantProfileService.fromNecta(applicants.findById(applicant).orElseThrow(),
                            tz.co.msingi.cims.admission.domain.ApplicantQualification.Type.CSEE, res.get()));
                } else {
                    notAtNecta.add(r.get("indexNumber") + "/" + examYear);
                }
            }
            Map<String, Object> wp = jdbc.queryForMap("""
                    select wp.id, p.name from window_programmes wp join programmes p on p.id = wp.programme_id
                    where wp.window_id = ? and upper(p.code) = upper(?)""", windowId, r.get("programmeCode"));
            Long wpId = ((Number) wp.get("id")).longValue();
            String ref = NumberSequenceService.format(prefix, sequences.next("APPLICATION-" + yearPart), 6);
            Long app = jdbc.queryForObject("""
                    insert into applications (tenant_id, applicant_id, window_id, reference_no, status, fee_paid, submitted_at, selected_window_programme_id,
                      source, external_body, external_ref, import_batch_id)
                    values (cims_current_tenant(), ?, ?, ?, 'SELECTED', true, now(), ?, 'EXTERNAL', ?, ?, ?) returning id""", Long.class,
                    applicant, windowId, ref, wpId, body, r.get("reference"), batchId);
            jdbc.update("""
                    insert into application_choices (tenant_id, application_id, window_programme_id, choice_order, eligible, eligibility_detail)
                    values (cims_current_tenant(), ?, ?, 1, true, ?::jsonb)""", app, wpId,
                    "{\"eligible\":true,\"matchedPathway\":\"" + body + "\",\"summary\":\"Selected by " + body + "\",\"pathways\":[]}");
            messaging.event("EXTERNAL_SELECTION", r.get("phone"), Map.of("institution", institution, "organisation", body.replace('_', ' '),
                    "programme", (String) wp.get("name"), "referenceNo", ref));
        }
        return Map.of("applications", rows.size(), "newApplicants", newAccounts, "window", w.get("name"), "selectedBy", body,
                "notFoundAtNecta", notAtNecta);
    }

    // ---------------------------------------------------------------- helpers

    private Long id(String sql, Object arg) {
        return jdbc.queryForObject(sql, Long.class, arg);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private String randomSecret() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return Base64.getUrlEncoder().encodeToString(b);
    }
}
