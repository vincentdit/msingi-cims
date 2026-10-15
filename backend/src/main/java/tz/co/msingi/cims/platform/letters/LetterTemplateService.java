package tz.co.msingi.cims.platform.letters;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.document.LetterWording;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wording of the letters CIMS prints, edited by the institution (FR-ADM-S05). Each part of a letter has built-in wording;
 * the institution may replace it, using the placeholders listed for that part. Admission letters keep the wording they were
 * issued with, so a reprint never changes.
 */
@Service
@RequiredArgsConstructor
public class LetterTemplateService {

    public record Placeholder(String name, String meaning) {
    }

    public record Definition(String code, String letter, String part, String description, List<Placeholder> placeholders) {
    }

    public record TemplateView(String code, String letter, String part, String description, List<Placeholder> placeholders,
                               String body, String defaultBody, boolean customised, int revision, String updatedByName, OffsetDateTime updatedAt) {
    }

    public record Save(@NotBlank @Size(max = 6000) String body) {
    }

    public static final String ADMISSION_INTRO = "ADMISSION_LETTER_INTRO";
    public static final String ADMISSION_TERMS = "ADMISSION_LETTER_TERMS";
    public static final String ADMISSION_PAYMENT = "ADMISSION_PAYMENT_NOTICE";
    public static final String IPT_INTRO = "IPT_LETTER_INTRO";
    public static final String IPT_REQUESTS = "IPT_LETTER_REQUESTS";

    private static final List<Placeholder> ADMISSION_VALUES = List.of(
            new Placeholder("applicantName", "The applicant's full name"),
            new Placeholder("institutionName", "The institution's name"),
            new Placeholder("programmeName", "Programme admitted to"),
            new Placeholder("awardName", "Award, e.g. Ordinary Diploma (NTA Level 6)"),
            new Placeholder("duration", "Programme duration"),
            new Placeholder("academicYear", "Academic year, e.g. 2027/2028"),
            new Placeholder("intake", "Intake, e.g. September Intake"),
            new Placeholder("nactvetVerificationNo", "NACTVET verification number"),
            new Placeholder("acceptedDate", "Date the applicant accepted the offer"),
            new Placeholder("reportingLine", "Reporting date, time and venue"),
            new Placeholder("letterNo", "Letter reference number"),
            new Placeholder("contactLine", "Institution phone and email"));
    private static final List<Placeholder> IPT_VALUES = List.of(
            new Placeholder("studentName", "Student's full name"),
            new Placeholder("registrationNo", "Registration number"),
            new Placeholder("programme", "Programme"),
            new Placeholder("organisation", "Placement organisation"),
            new Placeholder("startsOn", "First day of the placement"),
            new Placeholder("endsOn", "Last day of the placement"),
            new Placeholder("institutionName", "The institution's name"),
            new Placeholder("contactLine", "Institution phone and email"));

    public static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();

    static {
        DEFINITIONS.put(ADMISSION_INTRO, new Definition(ADMISSION_INTRO, "Admission letter", "Opening (before the programme details)",
                "Printed after \"Dear …\" and before the table of programme details.", ADMISSION_VALUES));
        DEFINITIONS.put(ADMISSION_TERMS, new Definition(ADMISSION_TERMS, "Admission letter", "Conditions and closing (after the programme details)",
                "Printed after the table of programme details and before the signature.", ADMISSION_VALUES));
        DEFINITIONS.put(ADMISSION_PAYMENT, new Definition(ADMISSION_PAYMENT, "Admission letter", "Payment notice (joining instructions)",
                "The boxed warning at the end of the joining instructions on how fees are paid.", ADMISSION_VALUES));
        DEFINITIONS.put(IPT_INTRO, new Definition(IPT_INTRO, "IPT placement letter", "Opening (before the placement details)",
                "Printed after \"Dear …\" and before the table of placement details.", IPT_VALUES));
        DEFINITIONS.put(IPT_REQUESTS, new Definition(IPT_REQUESTS, "IPT placement letter", "Requests and closing (after the placement details)",
                "Printed after the table of placement details and before the signature.", IPT_VALUES));
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TenantRepository tenants;

    @Transactional(readOnly = true)
    public List<TemplateView> list() {
        return DEFINITIONS.keySet().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public TemplateView view(String code) {
        Definition d = definition(code);
        String def = defaultBody(code);
        List<Map<String, Object>> r = jdbc.queryForList("select body, revision, updated_by_name, updated_at from letter_templates where code = ?", code);
        if (r.isEmpty()) return new TemplateView(code, d.letter(), d.part(), d.description(), d.placeholders(), def, def, false, 0, null, null);
        Map<String, Object> row = r.get(0);
        return new TemplateView(code, d.letter(), d.part(), d.description(), d.placeholders(), (String) row.get("body"), def, true,
                ((Number) row.get("revision")).intValue(), (String) row.get("updated_by_name"),
                row.get("updated_at") instanceof java.sql.Timestamp ts ? ts.toInstant().atOffset(java.time.ZoneOffset.UTC) : (OffsetDateTime) row.get("updated_at"));
    }

    /** The wording to print now: the institution's, or the built-in one. */
    @Transactional(readOnly = true)
    public String body(String code) {
        definition(code);
        List<String> r = jdbc.queryForList("select body from letter_templates where code = ?", String.class, code);
        return r.isEmpty() ? defaultBody(code) : r.get(0);
    }

    @Transactional
    public TemplateView save(String code, Save s) {
        Definition d = definition(code);
        var unknown = LetterWording.unknownPlaceholders(s.body(), d.placeholders().stream().map(Placeholder::name).collect(java.util.stream.Collectors.toSet()));
        if (!unknown.isEmpty()) {
            throw new BusinessRuleException("UNKNOWN_PLACEHOLDER", "Not a placeholder of this letter: " + String.join(", ", unknown.stream().map(u -> "{" + u + "}").toList()));
        }
        String before = body(code);
        int n = jdbc.update("update letter_templates set body = ?, revision = revision + 1, updated_by_name = ?, updated_at = now() where code = ?",
                s.body().strip(), CurrentUser.name(), code);
        if (n == 0) {
            jdbc.update("insert into letter_templates (tenant_id, code, body, updated_by_name) values (cims_current_tenant(), ?, ?, ?)",
                    code, s.body().strip(), CurrentUser.name());
        }
        audit.record("LETTER_TEMPLATE_UPDATED", "LetterTemplate", null, Map.of("code", code, "before", before, "after", s.body().strip()));
        return view(code);
    }

    @Transactional
    public TemplateView reset(String code) {
        definition(code);
        if (jdbc.update("delete from letter_templates where code = ?", code) > 0) {
            audit.record("LETTER_TEMPLATE_RESET", "LetterTemplate", null, Map.of("code", code));
        }
        return view(code);
    }

    private Definition definition(String code) {
        Definition d = DEFINITIONS.get(code);
        if (d == null) throw new NotFoundException("Letter template", code);
        return d;
    }

    private String defaultBody(String code) {
        boolean gepg = tenants.findById(TenantContext.requireTenantId()).map(t -> t.usesGepg()).orElse(true);
        return defaultBody(code, gepg);
    }

    public static String defaultBody(String code, boolean gepg) {
        return switch (code) {
            case ADMISSION_INTRO -> """
                    We are pleased to inform you that you have been admitted to **{institutionName}** to study the programme below. \
                    Your admission has been verified by the National Council for Technical and Vocational Education and Training (NACTVET) \
                    under verification number **{nactvetVerificationNo}**, and you accepted the offer on {acceptedDate}.""";
            case ADMISSION_TERMS -> """
                    This admission is subject to the following conditions:

                    1. You present your original academic certificates and birth certificate for verification at registration.
                    2. The admission will be cancelled at any time if any information you gave is found to be false or your certificates are not genuine.
                    3. You register within the period given in the joining instructions; a place not taken up by then may be given to another applicant.
                    4. You abide by the rules and regulations of the institution and of NACTVET.

                    The joining instructions attached to this letter give the reporting arrangements, what to bring and the fees. We look forward to welcoming you.""";
            case ADMISSION_PAYMENT -> gepg
                    ? "Fees are paid only through a GePG control number issued by the institution. Do not pay money to any individual or into any account not shown on a control number."
                    : "Fees are paid only into the institution's accounts shown on your invoice, quoting your payment reference. Do not pay money to any individual or into any other account.";
            case IPT_INTRO -> """
                    Thank you for accepting our student for industrial practical training (field attachment). This letter introduces the student and \
                    confirms the arrangements below.""";
            case IPT_REQUESTS -> """
                    We kindly ask the workplace supervisor to:

                    1. guide the student in work related to the programme and check the weekly logbook, which the student keeps online;
                    2. receive the institution supervisor for a supervision visit during the period;
                    3. complete the workplace assessment at the end of the attachment, online through the link sent by the institution or on the form.

                    The student is expected to follow the organisation's rules, working hours and safety requirements. Please contact us on {contactLine} \
                    about any matter concerning the student.""";
            default -> throw new IllegalArgumentException(code);
        };
    }
}
