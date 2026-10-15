package tz.co.msingi.cims.graduation.alumni;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.Csv;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.links.PortalLinks;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/** Alumni contacts and graduate tracer surveys (BR-31 / FR-GRD-05). */
@Service
@RequiredArgsConstructor
public class AlumniService {

    public static final List<String> EMPLOYMENT = List.of("EMPLOYED", "SELF_EMPLOYED", "FURTHER_STUDIES", "SEEKING", "NOT_SEEKING");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final PortalLinks links;
    private final Messaging messaging;

    public record Profile(@Size(max = 20) String phone, @Email @Size(max = 120) String email,
                          @Pattern(regexp = "EMPLOYED|SELF_EMPLOYED|FURTHER_STUDIES|SEEKING|NOT_SEEKING") String employmentStatus,
                          @Size(max = 150) String employer, @Size(max = 120) String jobTitle, @Size(max = 60) String sector,
                          @Size(max = 60) String region, boolean consentContact) {
    }

    public record AlumnusView(Long studentId, String registrationNo, String name, String programmeCode, String programmeName, String graduation,
                              String phone, String email, String employmentStatus, String employer, String jobTitle, String sector, String region,
                              boolean consentContact, String updatedVia, OffsetDateTime updatedAt) {
    }

    public record Question(String code, String text, String type, List<String> options, boolean required) {
    }

    public record SurveyRequest(@NotBlank @Size(max = 200) String title, @Size(max = 1000) String intro, Long graduationId, Long programmeId, LocalDate closesOn) {
    }

    public record SurveyView(Long id, String title, String intro, Long graduationId, String graduation, Long programmeId, String programme,
                             String status, LocalDate closesOn, int cohort, int invited, int responded, List<Question> questions,
                             Map<String, Map<String, Long>> distributions, Double employmentRate, String createdByName, OffsetDateTime openedAt) {
    }

    public record PublicSurvey(String institution, String title, String intro, String graduateName, String programme, List<Question> questions,
                               boolean submitted, boolean open) {
    }

    public static final List<Question> STANDARD = List.of(
            new Question("employment", "What are you doing now?", "CHOICE", EMPLOYMENT, true),
            new Question("monthsToJob", "How long after completing did you find your first job or start your business?", "CHOICE",
                    List.of("Before completing", "Within 3 months", "3 to 6 months", "6 to 12 months", "More than 12 months", "Not yet"), false),
            new Question("employer", "Employer or business name", "TEXT", List.of(), false),
            new Question("jobTitle", "Job title", "TEXT", List.of(), false),
            new Question("sector", "Sector", "CHOICE", List.of("Government / public service", "Private company", "NGO / faith-based", "Own business", "Other"), false),
            new Question("relevance", "How relevant is your training to your work? (1 = not at all, 5 = very)", "RATING", List.of("1", "2", "3", "4", "5"), false),
            new Question("preparation", "How well did the programme prepare you for work? (1 = poorly, 5 = very well)", "RATING", List.of("1", "2", "3", "4", "5"), false),
            new Question("recommend", "Would you recommend the programme to others?", "CHOICE", List.of("Yes", "No"), false),
            new Question("suggestions", "What should the institution improve?", "TEXT", List.of(), false));

    // ---------------------------------------------------------------- alumni

    @Transactional(readOnly = true)
    public List<AlumnusView> alumni(Long programmeId, Long graduationId, String status, String q) {
        StringBuilder where = new StringBuilder(" where st.status = 'GRADUATED'");
        List<Object> args = new ArrayList<>();
        if (programmeId != null) { where.append(" and st.programme_id = ?"); args.add(programmeId); }
        if (graduationId != null) { where.append(" and exists (select 1 from graduands g where g.student_id = st.id and g.graduation_id = ?)"); args.add(graduationId); }
        if (status != null && !status.isBlank()) { where.append(" and a.employment_status = ?"); args.add(status); }
        if (q != null && !q.isBlank()) {
            where.append(" and (upper(st.registration_no) like ? or upper(st.first_name || ' ' || st.surname) like ? or upper(coalesce(a.employer, '')) like ?)");
            String like = "%" + q.trim().toUpperCase() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        return jdbc.query(SELECT + where + " order by st.registration_no limit 2000", (rs, i) -> view(rs), args.toArray());
    }

    @Transactional
    public AlumnusView save(Long studentId, Profile p, String via) {
        Integer n = jdbc.queryForObject("select count(*) from students where id = ? and status = 'GRADUATED'", Integer.class, studentId);
        if (n == null || n == 0) throw new NotFoundException("Graduate", studentId);
        String phone = p.phone() == null || p.phone().isBlank() ? null : tz.co.msingi.cims.admission.service.PhoneNumbers.normalise(p.phone());
        int u = jdbc.update("""
                update alumni_profiles set phone = ?, email = ?, employment_status = ?, employer = ?, job_title = ?, sector = ?, region = ?,
                  consent_contact = ?, updated_via = ?, updated_by_name = ?, updated_at = now() where student_id = ?""",
                phone, blank(p.email()), blank(p.employmentStatus()), blank(p.employer()), blank(p.jobTitle()), blank(p.sector()), blank(p.region()),
                p.consentContact(), via, CurrentUser.name(), studentId);
        if (u == 0) {
            jdbc.update("""
                    insert into alumni_profiles (tenant_id, student_id, phone, email, employment_status, employer, job_title, sector, region, consent_contact,
                      updated_via, updated_by_name) values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    studentId, phone, blank(p.email()), blank(p.employmentStatus()), blank(p.employer()), blank(p.jobTitle()), blank(p.sector()),
                    blank(p.region()), p.consentContact(), via, CurrentUser.name());
        }
        audit.record("ALUMNI_PROFILE_UPDATED", "Student", studentId, Map.of("via", via));
        return one(studentId);
    }

    /** The signed-in graduate's own record (student portal). */
    @Transactional(readOnly = true)
    public AlumnusView mine() {
        return one(myId());
    }

    @Transactional
    public AlumnusView saveMine(Profile p) {
        return save(myId(), p, "PORTAL");
    }

    @Transactional
    public String csv(Long programmeId, Long graduationId, String status) {
        StringBuilder sb = new StringBuilder(Csv.line("Registration no", "Name", "Programme", "Graduation", "Phone", "Email", "Doing now", "Employer",
                "Job title", "Sector", "Region", "May be contacted", "Updated")).append("\n");
        alumni(programmeId, graduationId, status, null).forEach(a -> sb.append(Csv.line(a.registrationNo(), a.name(), a.programmeCode(), a.graduation(),
                a.phone(), a.email(), a.employmentStatus(), a.employer(), a.jobTitle(), a.sector(), a.region(), a.consentContact() ? "Yes" : "No",
                a.updatedAt())).append("\n"));
        audit.record("ALUMNI_EXPORTED", "Alumni", null, Map.of());
        return sb.toString();
    }

    // ---------------------------------------------------------------- tracer surveys

    @Transactional(readOnly = true)
    public List<SurveyView> surveys() {
        return jdbc.queryForList("select id from tracer_surveys order by created_at desc", Long.class).stream().map(this::survey).toList();
    }

    @Transactional
    public SurveyView create(SurveyRequest r) {
        Long id = jdbc.queryForObject("""
                insert into tracer_surveys (tenant_id, title, intro, graduation_id, programme_id, questions, closes_on, created_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?::jsonb, ?, ?) returning id""", Long.class,
                r.title().trim(), blank(r.intro()), r.graduationId(), r.programmeId(), json(STANDARD), r.closesOn(), CurrentUser.name());
        audit.record("TRACER_SURVEY_CREATED", "TracerSurvey", id, Map.of("title", r.title()));
        return survey(id);
    }

    /** Opens the survey and invites every graduate of the cohort who may be contacted and has a phone number. */
    @Transactional
    public SurveyView open(Long id) {
        Map<String, Object> s = row(id);
        if (!"DRAFT".equals(s.get("status"))) throw new BusinessRuleException("SURVEY_NOT_DRAFT", "The survey has already been opened");
        List<Map<String, Object>> cohort = cohort(s);
        if (cohort.isEmpty()) throw new BusinessRuleException("NO_GRADUATES", "No graduates match this survey's cohort");
        int invited = 0;
        for (Map<String, Object> g : cohort) {
            if (Boolean.FALSE.equals(g.get("consent")) || g.get("phone") == null) continue;
            PortalLinks.Token t = links.newToken();
            jdbc.update("insert into tracer_invitations (tenant_id, survey_id, student_id, token_hash) values (cims_current_tenant(), ?, ?, ?)",
                    id, g.get("id"), t.hash());
            messaging.eventWithLink("TRACER_SURVEY", (String) g.get("phone"), Map.of("title", (String) s.get("title"), "link", links.url("/survey/" + t.token())), "link");
            invited++;
        }
        jdbc.update("update tracer_surveys set status = 'OPEN', opened_at = now() where id = ?", id);
        audit.record("TRACER_SURVEY_OPENED", "TracerSurvey", id, Map.of("invited", invited));
        return survey(id);
    }

    @Transactional
    public SurveyView close(Long id) {
        jdbc.update("update tracer_surveys set status = 'CLOSED', closed_at = now() where id = ? and status = 'OPEN'", id);
        audit.record("TRACER_SURVEY_CLOSED", "TracerSurvey", id, Map.of());
        return survey(id);
    }

    @Transactional(readOnly = true)
    public SurveyView survey(Long id) {
        Map<String, Object> s = row(id);
        List<Question> questions = parse((String) s.get("questions"), new TypeReference<>() {});
        List<Map<String, Object>> answers = jdbc.queryForList("select answers::text as a from tracer_responses where survey_id = ?", id);
        Map<String, Map<String, Long>> dist = new LinkedHashMap<>();
        long working = 0;
        for (Map<String, Object> a : answers) {
            Map<String, Object> ans = parse((String) a.get("a"), new TypeReference<>() {});
            for (Question q : questions) {
                if ("TEXT".equals(q.type())) continue;
                Object v = ans.get(q.code());
                if (v == null || v.toString().isBlank()) continue;
                dist.computeIfAbsent(q.code(), k -> new LinkedHashMap<>()).merge(v.toString(), 1L, Long::sum);
            }
            Object e = ans.get("employment");
            if ("EMPLOYED".equals(e) || "SELF_EMPLOYED".equals(e)) working++;
        }
        Integer invited = jdbc.queryForObject("select count(*) from tracer_invitations where survey_id = ?", Integer.class, id);
        Long gradId = s.get("graduation_id") == null ? null : ((Number) s.get("graduation_id")).longValue();
        Long progId = s.get("programme_id") == null ? null : ((Number) s.get("programme_id")).longValue();
        return new SurveyView(id, (String) s.get("title"), (String) s.get("intro"), gradId,
                gradId == null ? null : jdbc.queryForObject("select name from graduations where id = ?", String.class, gradId),
                progId, progId == null ? null : jdbc.queryForObject("select code || ' · ' || name from programmes where id = ?", String.class, progId),
                (String) s.get("status"), s.get("closes_on") == null ? null : ((java.sql.Date) s.get("closes_on")).toLocalDate(),
                cohort(s).size(), invited == null ? 0 : invited, answers.size(), questions, dist,
                answers.isEmpty() ? null : Math.round(working * 1000.0 / answers.size()) / 10.0, (String) s.get("created_by_name"),
                s.get("opened_at") == null ? null : ((java.sql.Timestamp) s.get("opened_at")).toInstant().atOffset(java.time.ZoneOffset.UTC));
    }

    @Transactional(readOnly = true)
    public PublicSurvey publicSurvey(String token) {
        Map<String, Object> inv = invitation(token);
        Map<String, Object> s = row(((Number) inv.get("survey_id")).longValue());
        Map<String, Object> st = jdbc.queryForMap("""
                select trim(st.first_name || ' ' || st.surname) as name, p.name as programme from students st join programmes p on p.id = st.programme_id where st.id = ?""",
                inv.get("student_id"));
        String institution = jdbc.queryForObject("select name from tenants where id = cims_current_tenant()", String.class);
        boolean open = "OPEN".equals(s.get("status")) && (s.get("closes_on") == null || !((java.sql.Date) s.get("closes_on")).toLocalDate().isBefore(LocalDate.now()));
        return new PublicSurvey(institution, (String) s.get("title"), (String) s.get("intro"), (String) st.get("name"), (String) st.get("programme"),
                parse((String) s.get("questions"), new TypeReference<>() {}), inv.get("responded_at") != null, open);
    }

    @Transactional
    public PublicSurvey answer(String token, Map<String, String> answers) {
        Map<String, Object> inv = invitation(token);
        PublicSurvey ps = publicSurvey(token);
        if (!ps.open()) throw new BusinessRuleException("SURVEY_CLOSED", "This survey is closed. Thank you.");
        if (ps.submitted()) throw new BusinessRuleException("ALREADY_ANSWERED", "You have already answered. Thank you.");
        Map<String, String> clean = new LinkedHashMap<>();
        for (Question q : ps.questions()) {
            String v = answers.get(q.code());
            v = v == null ? null : v.trim();
            if (v == null || v.isEmpty()) {
                if (q.required()) throw new BusinessRuleException("ANSWER_REQUIRED", "Please answer: " + q.text());
                continue;
            }
            if (!"TEXT".equals(q.type()) && !q.options().contains(v)) throw new BusinessRuleException("INVALID_ANSWER", "Choose one of the answers for: " + q.text());
            clean.put(q.code(), v.length() > 1000 ? v.substring(0, 1000) : v);
        }
        Long survey = ((Number) inv.get("survey_id")).longValue();
        Long student = ((Number) inv.get("student_id")).longValue();
        jdbc.update("insert into tracer_responses (tenant_id, survey_id, student_id, answers) values (cims_current_tenant(), ?, ?, ?::jsonb)",
                survey, student, json(clean));
        jdbc.update("update tracer_invitations set responded_at = now() where id = ?", inv.get("id"));
        // keep the alumni record current with what the graduate said
        jdbc.update("""
                insert into alumni_profiles (tenant_id, student_id, employment_status, employer, job_title, sector, updated_via, updated_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, 'SURVEY', 'Tracer survey')
                on conflict (student_id) do update set employment_status = excluded.employment_status,
                  employer = coalesce(excluded.employer, alumni_profiles.employer), job_title = coalesce(excluded.job_title, alumni_profiles.job_title),
                  sector = coalesce(excluded.sector, alumni_profiles.sector), updated_via = 'SURVEY', updated_by_name = 'Tracer survey', updated_at = now()""",
                student, clean.get("employment"), clean.get("employer"), clean.get("jobTitle"), clean.get("sector"));
        return publicSurvey(token);
    }

    // ---------------------------------------------------------------- helpers

    private static final String SELECT = """
            select st.id, st.registration_no, trim(st.first_name || ' ' || coalesce(st.middle_name || ' ', '') || st.surname), p.code, p.name,
                   (select g.name from graduands gd join graduations g on g.id = gd.graduation_id where gd.student_id = st.id order by g.id desc limit 1),
                   coalesce(a.phone, st.phone), coalesce(a.email, st.email), a.employment_status, a.employer, a.job_title, a.sector, a.region,
                   coalesce(a.consent_contact, true), a.updated_via, a.updated_at
            from students st join programmes p on p.id = st.programme_id left join alumni_profiles a on a.student_id = st.id""";

    private AlumnusView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AlumnusView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getBoolean(14), rs.getString(15),
                rs.getObject(16, OffsetDateTime.class));
    }

    private AlumnusView one(Long studentId) {
        List<AlumnusView> r = jdbc.query(SELECT + " where st.id = ?", (rs, i) -> view(rs), studentId);
        if (r.isEmpty()) throw new NotFoundException("Graduate", studentId);
        return r.get(0);
    }

    private Long myId() {
        List<Long> r = jdbc.queryForList("select id from students where user_id = ? and status = 'GRADUATED'", Long.class, CurrentUser.requireId());
        if (r.isEmpty()) throw new BusinessRuleException("NOT_A_GRADUATE", "This is for graduates of the institution");
        return r.get(0);
    }

    private List<Map<String, Object>> cohort(Map<String, Object> s) {
        StringBuilder where = new StringBuilder(" where st.status = 'GRADUATED'");
        List<Object> args = new ArrayList<>();
        if (s.get("programme_id") != null) { where.append(" and st.programme_id = ?"); args.add(s.get("programme_id")); }
        if (s.get("graduation_id") != null) { where.append(" and exists (select 1 from graduands g where g.student_id = st.id and g.graduation_id = ?)"); args.add(s.get("graduation_id")); }
        return jdbc.queryForList("""
                select st.id, coalesce(a.phone, st.phone) as phone, coalesce(a.consent_contact, true) as consent
                from students st left join alumni_profiles a on a.student_id = st.id""" + where, args.toArray());
    }

    private Map<String, Object> row(Long id) {
        List<Map<String, Object>> r = jdbc.queryForList("select id, title, intro, graduation_id, programme_id, questions::text as questions, status, closes_on, created_by_name, opened_at from tracer_surveys where id = ?", id);
        if (r.isEmpty()) throw new NotFoundException("Survey", id);
        return r.get(0);
    }

    private Map<String, Object> invitation(String token) {
        if (token == null || token.length() < 20 || token.length() > 64) throw new NotFoundException("This link is not valid");
        List<Map<String, Object>> r = jdbc.queryForList("select id, survey_id, student_id, responded_at from tracer_invitations where token_hash = ?", PortalLinks.hash(token));
        if (r.isEmpty()) throw new NotFoundException("This link is not valid");
        return r.get(0);
    }

    private <T> T parse(String s, TypeReference<T> t) {
        try {
            return mapper.readValue(s, t);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
