package tz.co.msingi.cims.platform.imports;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.admission.service.NectaIndex;
import tz.co.msingi.cims.admission.service.PhoneNumbers;
import tz.co.msingi.cims.platform.imports.ImportService.Checked;
import tz.co.msingi.cims.platform.imports.ImportService.Kind;
import tz.co.msingi.cims.platform.imports.ImportService.Problem;
import tz.co.msingi.cims.registry.Student;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Row-by-row checks of an import file against the rules and the data already in CIMS. */
@Component
@RequiredArgsConstructor
class ImportValidator {

    static final Map<Kind, List<String>> COLUMNS = Map.of(
            Kind.STUDENTS, List.of("registration_no", "first_name", "middle_name", "surname", "sex", "date_of_birth", "phone", "email",
                    "nationality", "programme_code", "entry_year", "intake_code", "year_of_study", "status", "campus_code"),
            Kind.RESULTS, List.of("registration_no", "academic_year", "semester", "year_of_study", "module_code", "module_title", "credits",
                    "marks", "grade", "points", "gpa", "cgpa", "decision"),
            Kind.BALANCES, List.of("registration_no", "amount", "as_at", "description"),
            Kind.SELECTIONS, List.of("index_number", "exam_year", "first_name", "middle_name", "surname", "sex", "date_of_birth", "nationality", "phone",
                    "programme_code", "reference"));

    static final Map<Kind, List<String>> EXAMPLES = Map.of(
            Kind.STUDENTS, List.of("DEMO/NTA4/24/0001", "Asha", "Juma", "Mwinyi", "F", "2005-03-14", "0712345678", "", "Tanzanian",
                    "ODIT", "2024/2025", "SEPTEMBER", "2", "ACTIVE", ""),
            Kind.RESULTS, List.of("DEMO/NTA4/24/0001", "2024/2025", "1", "1", "ITU 04101", "Computer Applications", "12", "68", "B", "3", "3.4", "3.4", "PASS"),
            Kind.BALANCES, List.of("DEMO/NTA4/24/0001", "350000", "2026-06-30", "Fees balance from the old system"),
            Kind.SELECTIONS, List.of("S0101/0001", "2025", "Asha", "Juma", "Mwinyi", "F", "2008-05-20", "Tanzanian", "0712345678", "ODIT", "TAMISEMI/2026/00123"));

    private static final Pattern YEAR = Pattern.compile("^(\\d{4})(?:\\s*[/-]\\s*(\\d{2}|\\d{4}))?$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"), DateTimeFormatter.ofPattern("d.M.yyyy"));

    private final JdbcTemplate jdbc;

    Checked check(Kind kind, List<Map<String, String>> rows, Long windowId) {
        Ctx c = new Ctx();
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> r : rows) {
            int line = Integer.parseInt(r.getOrDefault("_row", "0"));
            Map<String, String> clean = new LinkedHashMap<>();
            clean.put("_row", String.valueOf(line));
            switch (kind) {
                case STUDENTS -> student(c, line, r, clean);
                case RESULTS -> result(c, line, r, clean);
                case BALANCES -> balance(c, line, r, clean);
                case SELECTIONS -> selection(c, line, r, clean, windowId);
            }
            out.add(clean);
        }
        if (kind == Kind.RESULTS) consistentSemesters(c, out);
        return new Checked(out, c.errors, new ArrayList<>(c.notes));
    }

    // ---------------------------------------------------------------- students

    private void student(Ctx c, int line, Map<String, String> r, Map<String, String> out) {
        String reg = text(c, line, r, "registrationno", "registration_no", 30, true);
        if (reg != null) {
            if (!c.seen.add("reg:" + reg.toUpperCase())) c.err(line, "registration_no", "Appears twice in the file");
            else if (count("select count(*) from students where upper(registration_no) = upper(?)", reg) > 0) c.err(line, "registration_no", "Already in CIMS");
            out.put("registrationNo", reg);
        }
        out.put("firstName", text(c, line, r, "firstname", "first_name", 60, true));
        out.put("middleName", text(c, line, r, "middlename", "middle_name", 60, false));
        out.put("surname", text(c, line, r, "surname", "surname", 60, true));
        out.put("sex", sex(c, line, r));
        LocalDate dob = date(c, line, r, "dateofbirth", "date_of_birth", true);
        if (dob != null && (dob.isAfter(LocalDate.now().minusYears(10)) || dob.isBefore(LocalDate.now().minusYears(90)))) {
            c.err(line, "date_of_birth", "Not a likely date of birth");
        }
        out.put("dateOfBirth", dob == null ? null : dob.toString());
        out.put("phone", phone(c, line, r, true));
        String email = text(c, line, r, "email", "email", 120, false);
        if (email != null && !EMAIL.matcher(email).matches()) c.err(line, "email", "Not an email address");
        out.put("email", email);
        String nat = text(c, line, r, "nationality", "nationality", 60, false);
        out.put("nationality", nat == null ? "Tanzanian" : nat);
        out.put("programmeCode", lookup(c, line, r, "programmecode", "programme_code", "select count(*) from programmes where upper(code) = upper(?)", "No programme with this code"));
        String year = academicYear(c, line, r, "entryyear", "entry_year");
        if (year != null && count("select count(*) from academic_years where code = ?", year) == 0) c.notes.add("Academic year " + year + " will be added to CIMS for the migrated students");
        out.put("entryYear", year);
        out.put("intakeCode", lookup(c, line, r, "intakecode", "intake_code", "select count(*) from intakes where upper(code) = upper(?)", "No intake with this code"));
        Integer yos = integer(c, line, r, "yearofstudy", "year_of_study", 1, 8, true);
        out.put("yearOfStudy", yos == null ? null : yos.toString());
        String status = r.get("status") == null ? "ACTIVE" : r.get("status").trim().toUpperCase();
        try {
            Student.Status.valueOf(status);
        } catch (IllegalArgumentException e) {
            c.err(line, "status", "Use one of " + Arrays.toString(Student.Status.values()));
        }
        out.put("status", status);
        out.put("campusCode", r.get("campuscode") == null ? null
                : lookup(c, line, r, "campuscode", "campus_code", "select count(*) from campuses where upper(code) = upper(?)", "No campus with this code"));
    }

    // ---------------------------------------------------------------- results

    private void result(Ctx c, int line, Map<String, String> r, Map<String, String> out) {
        String reg = lookup(c, line, r, "registrationno", "registration_no", "select count(*) from students where upper(registration_no) = upper(?)", "No student with this registration number");
        String year = academicYear(c, line, r, "academicyear", "academic_year");
        Integer sem = integer(c, line, r, "semester", "semester", 1, 3, true);
        Integer yos = integer(c, line, r, "yearofstudy", "year_of_study", 1, 8, true);
        String module = text(c, line, r, "modulecode", "module_code", 20, true);
        out.put("registrationNo", reg);
        out.put("academicYear", year);
        out.put("semester", sem == null ? null : sem.toString());
        out.put("yearOfStudy", yos == null ? null : yos.toString());
        out.put("moduleCode", module);
        out.put("moduleTitle", text(c, line, r, "moduletitle", "module_title", 200, true));
        out.put("credits", number(c, line, r, "credits", "credits", BigDecimal.valueOf(0.5), BigDecimal.valueOf(60), true));
        out.put("marks", number(c, line, r, "marks", "marks", BigDecimal.ZERO, BigDecimal.valueOf(100), false));
        out.put("grade", text(c, line, r, "grade", "grade", 3, true));
        out.put("points", number(c, line, r, "points", "points", BigDecimal.ZERO, BigDecimal.valueOf(5), false));
        out.put("gpa", number(c, line, r, "gpa", "gpa", BigDecimal.ZERO, BigDecimal.valueOf(5), false));
        out.put("cgpa", number(c, line, r, "cgpa", "cgpa", BigDecimal.ZERO, BigDecimal.valueOf(5), false));
        out.put("decision", text(c, line, r, "decision", "decision", 40, false));
        if (reg != null && year != null && sem != null && module != null) {
            if (!c.seen.add("res:" + reg.toUpperCase() + "|" + year + "|" + sem + "|" + module.toUpperCase())) c.err(line, "module_code", "This module appears twice for the semester");
            if (count("""
                    select count(*) from legacy_semester_results l join students s on s.id = l.student_id
                    where upper(s.registration_no) = upper(?) and l.academic_year = ? and l.semester_no = ?""", reg, year, sem) > 0) {
                c.err(line, "semester", "Results of this semester were already imported for the student");
            }
        }
    }

    /** The semester figures (year of study, GPA, decision) must agree on every row of the same semester. */
    private void consistentSemesters(Ctx c, List<Map<String, String>> rows) {
        Map<String, Map<String, String>> first = new HashMap<>();
        for (Map<String, String> r : rows) {
            if (r.get("registrationNo") == null) continue;
            String k = r.get("registrationNo").toUpperCase() + "|" + r.get("academicYear") + "|" + r.get("semester");
            Map<String, String> f = first.putIfAbsent(k, r);
            if (f == null) continue;
            for (String field : List.of("yearOfStudy", "gpa", "cgpa", "decision")) {
                if (!Objects.equals(f.get(field), r.get(field))) {
                    c.err(Integer.parseInt(r.get("_row")), field, "Differs from row " + f.get("_row") + " of the same semester");
                }
            }
        }
    }

    // ---------------------------------------------------------------- balances

    private void balance(Ctx c, int line, Map<String, String> r, Map<String, String> out) {
        String reg = lookup(c, line, r, "registrationno", "registration_no", "select count(*) from students where upper(registration_no) = upper(?)", "No student with this registration number");
        if (reg != null) {
            if (!c.seen.add("bal:" + reg.toUpperCase())) c.err(line, "registration_no", "One balance per student: this one appears twice");
            if (count("""
                    select count(*) from invoices i join students s on s.id = i.payer_id and i.payer_type = 'STUDENT'
                    where upper(s.registration_no) = upper(?) and i.purpose = 'OPENING_BALANCE' and i.status <> 'CANCELLED'""", reg) > 0) {
                c.err(line, "registration_no", "An opening balance was already brought in for this student");
            }
        }
        out.put("registrationNo", reg);
        String amount = number(c, line, r, "amount", "amount", BigDecimal.valueOf(-100_000_000), BigDecimal.valueOf(100_000_000), true);
        if (amount != null && new BigDecimal(amount).signum() == 0) c.err(line, "amount", "A zero balance needs no row");
        out.put("amount", amount);
        LocalDate asAt = date(c, line, r, "asat", "as_at", true);
        if (asAt != null && asAt.isAfter(LocalDate.now())) c.err(line, "as_at", "Cannot be in the future");
        out.put("asAt", asAt == null ? null : asAt.toString());
        out.put("description", text(c, line, r, "description", "description", 200, false));
    }

    // ---------------------------------------------------------------- selections

    private void selection(Ctx c, int line, Map<String, String> r, Map<String, String> out, Long windowId) {
        String index = null;
        Integer year = integer(c, line, r, "examyear", "exam_year", NectaIndex.FIRST_YEAR, LocalDate.now().getYear(), false);
        try {
            if (r.get("indexnumber") == null) c.err(line, "index_number", "Required");
            else {
                NectaIndex idx = NectaIndex.parse(r.get("indexnumber"), year);
                index = idx.indexNumber();
                year = idx.year();
            }
        } catch (RuntimeException e) {
            c.err(line, "index_number", e.getMessage());
        }
        out.put("indexNumber", index);
        out.put("examYear", year == null ? null : year.toString());
        out.put("firstName", text(c, line, r, "firstname", "first_name", 60, true));
        out.put("middleName", text(c, line, r, "middlename", "middle_name", 60, false));
        out.put("surname", text(c, line, r, "surname", "surname", 60, true));
        out.put("sex", sex(c, line, r));
        LocalDate dob = date(c, line, r, "dateofbirth", "date_of_birth", true);
        out.put("dateOfBirth", dob == null ? null : dob.toString());
        String nat = text(c, line, r, "nationality", "nationality", 60, false);
        out.put("nationality", nat == null ? "Tanzanian" : nat);
        String phone = phone(c, line, r, true);
        out.put("phone", phone);
        out.put("programmeCode", lookup(c, line, r, "programmecode", "programme_code", """
                select count(*) from window_programmes wp join programmes p on p.id = wp.programme_id
                where wp.window_id = """ + (windowId == null ? "null" : windowId) + " and upper(p.code) = upper(?)", "This programme is not offered in the window"));
        out.put("reference", text(c, line, r, "reference", "reference", 60, false));
        if (index != null && year != null) {
            if (!c.seen.add("idx:" + index + "/" + year)) c.err(line, "index_number", "Appears twice in the file");
            if (count("""
                    select count(*) from applications ap join applicants a on a.id = ap.applicant_id
                    where ap.window_id = ? and a.necta_index_number = ? and a.necta_exam_year = ? and ap.status <> 'WITHDRAWN'""", windowId, index, year) > 0) {
                c.err(line, "index_number", "Already has an application in this window");
            }
            if (count("select count(*) from students s join applicants a on a.id = s.applicant_id where a.necta_index_number = ? and a.necta_exam_year = ?", index, year) > 0) {
                c.err(line, "index_number", "Already a student of the institution");
            }
            if (phone != null && count("""
                    select count(*) from user_accounts u where u.phone = ? and not exists
                      (select 1 from applicants a where a.user_id = u.id and a.necta_index_number = ? and a.necta_exam_year = ?)""", phone, index, year) > 0) {
                c.err(line, "phone", "This phone number belongs to another account");
            }
            if (phone != null && !c.seen.add("phone:" + phone)) c.err(line, "phone", "Two students in the file have this phone number");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static final class Ctx {
        final List<Problem> errors = new ArrayList<>();
        final Set<String> notes = new LinkedHashSet<>();
        final Set<String> seen = new HashSet<>();

        void err(int row, String field, String message) {
            errors.add(new Problem(row, field, message));
        }
    }

    private String text(Ctx c, int line, Map<String, String> r, String key, String col, int max, boolean required) {
        String v = r.get(key);
        if (v == null || v.isBlank()) {
            if (required) c.err(line, col, "Required");
            return null;
        }
        v = v.trim().replaceAll("\\s+", " ");
        if (v.length() > max) c.err(line, col, "Longer than " + max + " characters");
        return v;
    }

    private String sex(Ctx c, int line, Map<String, String> r) {
        String v = r.get("sex");
        if (v == null) {
            c.err(line, "sex", "Required");
            return null;
        }
        String s = v.trim().toUpperCase();
        if (s.equals("MALE") || s.equals("ME") || s.equals("M")) return "M";
        if (s.equals("FEMALE") || s.equals("KE") || s.equals("F")) return "F";
        c.err(line, "sex", "Use M or F");
        return null;
    }

    private String phone(Ctx c, int line, Map<String, String> r, boolean required) {
        String v = r.get("phone");
        if (v == null) {
            if (required) c.err(line, "phone", "Required");
            return null;
        }
        try {
            return PhoneNumbers.normalise(v);
        } catch (RuntimeException e) {
            c.err(line, "phone", "Not a Tanzanian mobile number");
            return null;
        }
    }

    private LocalDate date(Ctx c, int line, Map<String, String> r, String key, String col, boolean required) {
        String v = r.get(key);
        if (v == null) {
            if (required) c.err(line, col, "Required");
            return null;
        }
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(v.trim(), f);
            } catch (DateTimeParseException ignored) {
                // try the next form
            }
        }
        c.err(line, col, "Not a date (use YYYY-MM-DD or DD/MM/YYYY)");
        return null;
    }

    private Integer integer(Ctx c, int line, Map<String, String> r, String key, String col, int min, int max, boolean required) {
        String v = r.get(key);
        if (v == null) {
            if (required) c.err(line, col, "Required");
            return null;
        }
        try {
            int n = new BigDecimal(v.trim()).intValueExact();
            if (n < min || n > max) {
                c.err(line, col, "Must be from " + min + " to " + max);
                return null;
            }
            return n;
        } catch (RuntimeException e) {
            c.err(line, col, "Not a whole number");
            return null;
        }
    }

    private String number(Ctx c, int line, Map<String, String> r, String key, String col, BigDecimal min, BigDecimal max, boolean required) {
        String v = r.get(key);
        if (v == null) {
            if (required) c.err(line, col, "Required");
            return null;
        }
        try {
            BigDecimal n = ImportService.decimal(v);
            if (n.compareTo(min) < 0 || n.compareTo(max) > 0) {
                c.err(line, col, "Must be from " + min.stripTrailingZeros().toPlainString() + " to " + max.stripTrailingZeros().toPlainString());
                return null;
            }
            return n.stripTrailingZeros().toPlainString();
        } catch (RuntimeException e) {
            c.err(line, col, "Not a number");
            return null;
        }
    }

    private String academicYear(Ctx c, int line, Map<String, String> r, String key, String col) {
        String v = r.get(key);
        if (v == null) {
            c.err(line, col, "Required");
            return null;
        }
        Matcher m = YEAR.matcher(v.trim());
        if (!m.matches()) {
            c.err(line, col, "Use the form 2024/2025");
            return null;
        }
        int a = Integer.parseInt(m.group(1));
        if (m.group(2) != null) {
            int b = Integer.parseInt(m.group(2).length() == 2 ? m.group(1).substring(0, 2) + m.group(2) : m.group(2));
            if (b != a + 1) {
                c.err(line, col, "The second year must follow the first, e.g. 2024/2025");
                return null;
            }
        }
        if (a < 1990 || a > LocalDate.now().getYear() + 1) {
            c.err(line, col, "Year out of range");
            return null;
        }
        return a + "/" + (a + 1);
    }

    private String lookup(Ctx c, int line, Map<String, String> r, String key, String col, String sql, String message) {
        String v = text(c, line, r, key, col, 60, true);
        if (v == null) return null;
        if (count(sql, v) == 0) c.err(line, col, message);
        return v;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
