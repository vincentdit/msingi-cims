package tz.co.msingi.cims.graduation.alumni;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
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

    public record SurveyRequest(@NotBlank @Size(max = 200) String title, @Size(max = 1000) String intro, Long graduationId, Long programmeId, LocalDate closesOn,
                                @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(60) Integer remindAfterDays) {
    }

    public record SurveyView(Long id, String title, String intro, Long graduationId, String graduation, Long programmeId, String programme,
                             String status, LocalDate closesOn, int cohort, int invited, int responded, List<Question> questions,
                             Map<String, Map<String, Long>> distributions, Double employmentRate, String createdByName, OffsetDateTime openedAt,
                             int reminded, int remindAfterDays) {
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
                insert into tracer_surveys (tenant_id, title, intro, graduation_id, programme_id, questions, closes_on, created_by_name, remind_after_days)
                values (cims_current_tenant(), ?, ?, ?, ?, ?::jsonb, ?, ?, ?) returning id""", Long.class,
                r.title().trim(), blank(r.intro()), r.graduationId(), r.programmeId(), json(STANDARD), r.closesOn(), CurrentUser.name(),
                r.remindAfterDays() == null ? 7 : r.remindAfterDays());
        audit.record("TRACER_SURVEY_CREATED", "TracerSurvey", id, Map.of("title", r.title()));
        return survey(id);
    }

    /** Opens the survey and invites every graduate of the cohort who may be contacted, by SMS and by email where there is an address. */
    @Transactional
    public SurveyView open(Long id) {
        Map<String, Object> s = row(id);
        if (!"DRAFT".equals(s.get("status"))) throw new BusinessRuleException("SURVEY_NOT_DRAFT", "The survey has already been opened");
        List<Map<String, Object>> cohort = cohort(s);
        if (cohort.isEmpty()) throw new BusinessRuleException("NO_GRADUATES", "No graduates match this survey's cohort");
        int invited = 0;
        for (Map<String, Object> g : cohort) {
            if (Boolean.FALSE.equals(g.get("consent"))) continue;
            String phone = (String) g.get("phone"), email = (String) g.get("email");
            if (phone == null && email == null) continue;
            PortalLinks.Token t = links.newToken();
            // by SMS, and also by email where the graduate has an address (a graduate's phone number is often out of date)
            jdbc.update("insert into tracer_invitations (tenant_id, survey_id, student_id, token_hash, channel) values (cims_current_tenant(), ?, ?, ?, ?)",
                    id, g.get("id"), t.hash(), phone != null ? "SMS" : "EMAIL");
            send("TRACER_SURVEY", phone, email, (String) s.get("title"), t.token());
            invited++;
        }
        jdbc.update("update tracer_surveys set status = 'OPEN', opened_at = now() where id = ?", id);
        audit.record("TRACER_SURVEY_OPENED", "TracerSurvey", id, Map.of("invited", invited));
        return survey(id);
    }

    /**
     * Reminds the graduates who have not answered. Each gets a new personal link (only a hash of the old one was kept, so it cannot be
     * sent again; the old link stops working). With onlyDue, as the daily job does, only those never reminded whose invitation is older
     * than the survey's remind_after_days; by hand, anyone not reminded in the last three days. Returns how many were reminded.
     */
    @Transactional
    public int remind(Long id, boolean onlyDue) {
        Map<String, Object> s = row(id);
        if (!"OPEN".equals(s.get("status"))) throw new BusinessRuleException("SURVEY_NOT_OPEN", "Only an open survey can send reminders");
        List<Map<String, Object>> due = jdbc.queryForList("""
                select i.id, coalesce(a.phone, st.phone) as phone, coalesce(a.email, st.email) as email
                from tracer_invitations i join tracer_surveys ts on ts.id = i.survey_id
                join students st on st.id = i.student_id left join alumni_profiles a on a.student_id = st.id
                where i.survey_id = ? and i.responded_at is null and coalesce(a.consent_contact, true)
                  and (i.reminded_at is null or i.reminded_at < now() - interval '3 days')
                  and (not ? or (i.reminded_at is null and i.sent_at < now() - make_interval(days => ts.remind_after_days)))""", id, onlyDue);
        int n = 0;
        for (Map<String, Object> d : due) {
            String phone = (String) d.get("phone"), email = (String) d.get("email");
            if (phone == null && email == null) continue;
            PortalLinks.Token t = links.newToken();
            jdbc.update("update tracer_invitations set token_hash = ?, reminded_at = now(), channel = ? where id = ?", t.hash(), phone != null ? "SMS" : "EMAIL", d.get("id"));
            send("TRACER_REMINDER", phone, email, (String) s.get("title"), t.token());
            n++;
        }
        if (n > 0 || !onlyDue) audit.record("TRACER_SURVEY_REMINDED", "TracerSurvey", id, Map.of("reminded", n, "automatic", onlyDue));
        return n;
    }

    private void send(String template, String phone, String email, String title, String token) {
        Map<String, String> v = Map.of("title", title, "link", links.url("/survey/" + token));
        if (phone != null) messaging.eventWithLink(template, phone, v, "link");
        if (email != null && !email.isBlank()) messaging.emailEvent(template, email, v);
    }

    /** Every morning for each institution: closes surveys past their closing date and sends the one reminder that is due. */
    @Transactional
    public int daily() {
        for (Long id : jdbc.queryForList("select id from tracer_surveys where status = 'OPEN' and closes_on < current_date", Long.class)) close(id);
        int n = 0;
        for (Long id : jdbc.queryForList("select id from tracer_surveys where status = 'OPEN'", Long.class)) n += remind(id, true);
        return n;
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
        Integer reminded = jdbc.queryForObject("select count(*) from tracer_invitations where survey_id = ? and reminded_at is not null", Integer.class, id);
        Long gradId = s.get("graduation_id") == null ? null : ((Number) s.get("graduation_id")).longValue();
        Long progId = s.get("programme_id") == null ? null : ((Number) s.get("programme_id")).longValue();
        return new SurveyView(id, (String) s.get("title"), (String) s.get("intro"), gradId,
                gradId == null ? null : jdbc.queryForObject("select name from graduations where id = ?", String.class, gradId),
                progId, progId == null ? null : jdbc.queryForObject("select code || ' · ' || name from programmes where id = ?", String.class, progId),
                (String) s.get("status"), s.get("closes_on") == null ? null : ((java.sql.Date) s.get("closes_on")).toLocalDate(),
                cohort(s).size(), invited == null ? 0 : invited, answers.size(), questions, dist,
                answers.isEmpty() ? null : Math.round(working * 1000.0 / answers.size()) / 10.0, (String) s.get("created_by_name"),
                s.get("opened_at") == null ? null : ((java.sql.Timestamp) s.get("opened_at")).toInstant().atOffset(java.time.ZoneOffset.UTC),
                reminded == null ? 0 : reminded, ((Number) s.get("remind_after_days")).intValue());
    }

    public record Download(String filename, byte[] content) {
    }

    /**
     * The survey report for NACTVET and the Board: a summary with the response and employment rates overall and by programme, how the
     * answers are spread, and every response without names or contact details (a sequence number and the programme only).
     */
    @Transactional
    public Download report(Long id) {
        SurveyView v = survey(id);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select p.code as programme, r.answers::text as a, r.submitted_at
                from tracer_responses r join students st on st.id = r.student_id join programmes p on p.id = st.programme_id
                where r.survey_id = ? order by r.submitted_at""", id);
        List<Map<String, Object>> byProg = jdbc.queryForList("""
                select p.code, p.name, count(i.id) as invited, count(r.id) as responded,
                       count(r.id) filter (where r.answers->>'employment' in ('EMPLOYED', 'SELF_EMPLOYED')) as working
                from tracer_invitations i join students st on st.id = i.student_id join programmes p on p.id = st.programme_id
                left join tracer_responses r on r.survey_id = i.survey_id and r.student_id = i.student_id
                where i.survey_id = ? group by p.code, p.name order by p.code""", id);
        try (org.apache.poi.ss.usermodel.Workbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sum = wb.createSheet("Summary");
            int[] r = {0};
            java.util.function.BiConsumer<String, Object> line = (k, val) -> {
                org.apache.poi.ss.usermodel.Row x = sum.createRow(r[0]++);
                x.createCell(0).setCellValue(k);
                if (val instanceof Number n) x.createCell(1).setCellValue(n.doubleValue());
                else if (val != null) x.createCell(1).setCellValue(val.toString());
            };
            line.accept("Survey", v.title());
            line.accept("Graduation", v.graduation() == null ? "All graduates" : v.graduation());
            line.accept("Programme", v.programme() == null ? "All programmes" : v.programme());
            line.accept("Status", v.status());
            line.accept("Graduates in the cohort", v.cohort());
            line.accept("Invited", v.invited());
            line.accept("Responded", v.responded());
            line.accept("Response rate (%)", v.invited() == 0 ? null : Math.round(v.responded() * 1000.0 / v.invited()) / 10.0);
            line.accept("Employed or self-employed (% of responses)", v.employmentRate());
            r[0]++;
            org.apache.poi.ss.usermodel.Row h = sum.createRow(r[0]++);
            String[] head = {"Programme", "Name", "Invited", "Responded", "Response rate (%)", "Working (% of responses)"};
            for (int i = 0; i < head.length; i++) h.createCell(i).setCellValue(head[i]);
            for (Map<String, Object> p : byProg) {
                org.apache.poi.ss.usermodel.Row x = sum.createRow(r[0]++);
                long inv = ((Number) p.get("invited")).longValue(), resp = ((Number) p.get("responded")).longValue(), work = ((Number) p.get("working")).longValue();
                x.createCell(0).setCellValue((String) p.get("code"));
                x.createCell(1).setCellValue((String) p.get("name"));
                x.createCell(2).setCellValue(inv);
                x.createCell(3).setCellValue(resp);
                if (inv > 0) x.createCell(4).setCellValue(Math.round(resp * 1000.0 / inv) / 10.0);
                if (resp > 0) x.createCell(5).setCellValue(Math.round(work * 1000.0 / resp) / 10.0);
            }
            sum.setColumnWidth(0, 44 * 256);
            sum.setColumnWidth(1, 40 * 256);
            for (int i = 2; i < 6; i++) sum.setColumnWidth(i, 20 * 256);

            org.apache.poi.ss.usermodel.Sheet dist = wb.createSheet("Answers");
            int d = 0;
            org.apache.poi.ss.usermodel.Row dh = dist.createRow(d++);
            dh.createCell(0).setCellValue("Question");
            dh.createCell(1).setCellValue("Answer");
            dh.createCell(2).setCellValue("Responses");
            dh.createCell(3).setCellValue("% of responses");
            for (Question q : v.questions()) {
                Map<String, Long> m = v.distributions().get(q.code());
                if (m == null) continue;
                for (Map.Entry<String, Long> e : m.entrySet()) {
                    org.apache.poi.ss.usermodel.Row x = dist.createRow(d++);
                    x.createCell(0).setCellValue(q.text());
                    x.createCell(1).setCellValue(e.getKey());
                    x.createCell(2).setCellValue(e.getValue());
                    x.createCell(3).setCellValue(Math.round(e.getValue() * 1000.0 / Math.max(1, v.responded())) / 10.0);
                }
            }
            dist.setColumnWidth(0, 44 * 256);
            dist.setColumnWidth(1, 36 * 256);
            dist.createFreezePane(0, 1);

            org.apache.poi.ss.usermodel.Sheet resp = wb.createSheet("Responses");
            org.apache.poi.ss.usermodel.Row rh = resp.createRow(0);
            rh.createCell(0).setCellValue("No.");
            rh.createCell(1).setCellValue("Programme");
            rh.createCell(2).setCellValue("Answered on");
            for (int i = 0; i < v.questions().size(); i++) rh.createCell(3 + i).setCellValue(v.questions().get(i).text());
            int n = 1;
            for (Map<String, Object> row : rows) {
                Map<String, Object> ans = parse((String) row.get("a"), new TypeReference<>() {});
                org.apache.poi.ss.usermodel.Row x = resp.createRow(n);
                x.createCell(0).setCellValue(n++);
                x.createCell(1).setCellValue((String) row.get("programme"));
                x.createCell(2).setCellValue(((java.sql.Timestamp) row.get("submitted_at")).toLocalDateTime().toLocalDate().toString());
                for (int i = 0; i < v.questions().size(); i++) {
                    Object a = ans.get(v.questions().get(i).code());
                    if (a != null) x.createCell(3 + i).setCellValue(a.toString());
                }
            }
            for (int i = 0; i < 3 + v.questions().size(); i++) resp.setColumnWidth(i, (i == 0 ? 8 : 24) * 256);
            resp.createFreezePane(0, 1);
            wb.write(out);
            audit.record("TRACER_SURVEY_REPORT", "TracerSurvey", id, Map.of("responses", rows.size()));
            return new Download("tracer-survey-" + id + ".xlsx", out.toByteArray());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
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
                select st.id, coalesce(a.phone, st.phone) as phone, coalesce(a.email, st.email) as email, coalesce(a.consent_contact, true) as consent
                from students st left join alumni_profiles a on a.student_id = st.id""" + where, args.toArray());
    }

    private Map<String, Object> row(Long id) {
        List<Map<String, Object>> r = jdbc.queryForList("select id, title, intro, graduation_id, programme_id, questions::text as questions, status, closes_on, created_by_name, opened_at, remind_after_days from tracer_surveys where id = ?", id);
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
