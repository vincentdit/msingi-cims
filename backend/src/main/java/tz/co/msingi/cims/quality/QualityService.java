package tz.co.msingi.cims.quality;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.common.web.SimpleXlsx;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.Role;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Quality assurance (FR-QA-01..03): each semester students evaluate every module they are registered for — the module and its
 * teaching — on the institution's questionnaire. Answers carry no student: who has answered is kept apart, only to stop a second answer.
 * A module's results are shown only when at least the round's minimum number of students answered, so nobody can be identified;
 * written comments are shown once the round has closed. The Quality Assurance Officer and management see every module, a Head of
 * Department those of the department, and lecturers their own modules after the round closes.
 */
@Service
@RequiredArgsConstructor
public class QualityService {

    static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");
    static final Role[] MANAGE = {Role.INSTITUTION_ADMIN, Role.QUALITY_ASSURANCE_OFFICER};
    static final Role[] READ_ALL = {Role.INSTITUTION_ADMIN, Role.QUALITY_ASSURANCE_OFFICER, Role.DEPUTY_PRINCIPAL_ACADEMIC, Role.MANAGEMENT_VIEWER, Role.AUDITOR};
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ExamAccess access;
    private final Messaging messaging;
    private final AuditService audit;

    public record Question(Long id, String section, String kind, String text, int sortOrder, boolean active) {
    }

    public record QuestionRequest(String section, String kind, String text, Integer sortOrder, Boolean active) {
    }

    public record RoundRequest(Long semesterId, String name, LocalDate opensOn, LocalDate closesOn, Integer minResponses, Boolean tellStudents) {
    }

    public record RoundView(Long id, Long semesterId, String semester, String name, LocalDate opensOn, LocalDate closesOn, int minResponses, String state,
                            int modules, int responses, int expected, String createdByName, OffsetDateTime createdAt) {
    }

    public record ModuleSummary(Long offeringId, String code, String title, String department, Long departmentId, String lecturers, int registered,
                                int responses, BigDecimal responseRate, BigDecimal average, boolean enough) {
    }

    public record RoundDetail(RoundView round, List<ModuleSummary> modules, List<Question> questions) {
    }

    public record QuestionResult(Long questionId, String section, String kind, String text, BigDecimal average, int[] counts, List<String> comments) {
    }

    public record ModuleReport(RoundView round, ModuleSummary module, List<QuestionResult> questions, String note) {
    }

    public record MyEvaluationModule(Long offeringId, String code, String title, String lecturers, boolean done) {
    }

    public record MyEvaluation(RoundView round, List<Question> questions, List<MyEvaluationModule> modules) {
    }

    public record AnswerRequest(Map<String, Integer> ratings, Map<String, String> comments) {
    }

    public record LecturerRound(RoundView round, List<ModuleSummary> modules) {
    }

    // ------------------------------------------------------------------ questionnaire

    @Transactional
    public List<Question> questions() {
        seed();
        return jdbc.query("select id, section, kind, text, sort_order, active from evaluation_questions order by sort_order, id", (rs, n) -> question(rs));
    }

    @Transactional
    public List<Question> saveQuestion(Long id, QuestionRequest r) {
        access.require("change the questionnaire", MANAGE);
        String section = oneOf(r.section(), Set.of("MODULE", "TEACHING", "GENERAL"), "the section");
        String text = r.text() == null || r.text().isBlank() ? null : r.text().trim();
        if (text == null || text.length() > 300) throw new BusinessRuleException("FIELD_REQUIRED", "Write the question (at most 300 characters)");
        if (id == null) {
            String kind = oneOf(r.kind(), Set.of("RATING", "TEXT"), "the kind of answer");
            jdbc.update("insert into evaluation_questions (tenant_id, section, kind, text, sort_order) values (cims_current_tenant(), ?, ?, ?, ?)",
                    section, kind, text, r.sortOrder() == null ? 100 : r.sortOrder());
        } else if (jdbc.update("update evaluation_questions set section = ?, text = ?, sort_order = coalesce(?, sort_order), active = ? where id = ?",
                section, text, r.sortOrder(), !Boolean.FALSE.equals(r.active()), id) == 0) {
            throw new NotFoundException("Question not found");
        }
        audit.record("EVALUATION_QUESTION_SAVED", "EvaluationQuestion", id, Map.of("text", text));
        return questions();
    }

    private void seed() {
        Integer n = jdbc.queryForObject("select count(*) from evaluation_questions", Integer.class);
        if (n != null && n > 0) return;
        String[][] q = {
                {"MODULE", "RATING", "The module's objectives and content were made clear at the start."},
                {"MODULE", "RATING", "The content was relevant to my programme and to future work."},
                {"MODULE", "RATING", "Notes, books and other learning materials were enough."},
                {"MODULE", "RATING", "Practical sessions (laboratory, workshop, clinical or field work) were well organised."},
                {"TEACHING", "RATING", "The lecturer came to class prepared and on time."},
                {"TEACHING", "RATING", "The lecturer explained clearly and answered questions."},
                {"TEACHING", "RATING", "Assignments and tests were marked and returned with feedback in good time."},
                {"TEACHING", "RATING", "The lecturer treated students fairly and with respect."},
                {"GENERAL", "RATING", "Overall, I am satisfied with this module."},
                {"GENERAL", "TEXT", "What was good about this module?"},
                {"GENERAL", "TEXT", "What should be improved?"}};
        for (int i = 0; i < q.length; i++) {
            jdbc.update("insert into evaluation_questions (tenant_id, section, kind, text, sort_order) values (cims_current_tenant(), ?, ?, ?, ?)",
                    q[i][0], q[i][1], q[i][2], (i + 1) * 10);
        }
    }

    // ------------------------------------------------------------------ rounds

    @Transactional(readOnly = true)
    public List<RoundView> rounds() {
        return jdbc.query(ROUND + " order by r.opens_on desc", (rs, n) -> round(rs));
    }

    @Transactional
    public RoundDetail openRound(RoundRequest r) {
        access.require("open an evaluation", MANAGE);
        if (r.semesterId() == null) throw new BusinessRuleException("SEMESTER_REQUIRED", "Choose the semester");
        if (r.opensOn() == null || r.closesOn() == null || r.closesOn().isBefore(r.opensOn())) {
            throw new BusinessRuleException("DATES_INVALID", "Give the opening date and a closing date on or after it");
        }
        int min = r.minResponses() == null ? 5 : r.minResponses();
        if (min < 3) throw new BusinessRuleException("MINIMUM_TOO_LOW", "Show results only when at least 3 students answered, so nobody is identified");
        Map<String, Object> sem = jdbc.queryForList("select name from semesters where id = ?", r.semesterId()).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Semester not found"));
        Integer taken = jdbc.queryForObject("select count(*) from evaluation_rounds where semester_id = ?", Integer.class, r.semesterId());
        if (taken != null && taken > 0) throw new BusinessRuleException("ROUND_EXISTS", "There is already an evaluation for " + sem.get("name"));
        List<Long> ids = questions().stream().filter(Question::active).map(Question::id).toList();
        if (ids.isEmpty()) throw new BusinessRuleException("NO_QUESTIONS", "The questionnaire has no questions");
        String name = r.name() == null || r.name().isBlank() ? "Module evaluation, " + sem.get("name") : r.name().trim();
        Long id = jdbc.queryForObject("""
                insert into evaluation_rounds (tenant_id, semester_id, name, opens_on, closes_on, min_responses, question_ids, created_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?::jsonb, ?) returning id""", Long.class, r.semesterId(), cut(name, 120), r.opensOn(),
                r.closesOn(), min, json(ids), CurrentUser.name());
        audit.record("EVALUATION_OPENED", "EvaluationRound", id, Map.of("semester", r.semesterId()));
        if (Boolean.TRUE.equals(r.tellStudents()) && !r.opensOn().isAfter(today())) tell(id);
        return round(id);
    }

    @Transactional
    public RoundDetail changeRound(Long id, RoundRequest r) {
        access.require("change an evaluation", MANAGE);
        RoundView v = one(id);
        LocalDate opens = r.opensOn() == null ? v.opensOn() : r.opensOn(), closes = r.closesOn() == null ? v.closesOn() : r.closesOn();
        if (closes.isBefore(opens)) throw new BusinessRuleException("DATES_INVALID", "The closing date is on or after the opening date");
        if (v.responses() > 0 && !opens.equals(v.opensOn())) throw new BusinessRuleException("ROUND_STARTED", "Students have answered; only the closing date can change");
        int min = r.minResponses() == null ? v.minResponses() : r.minResponses();
        if (min < 3) throw new BusinessRuleException("MINIMUM_TOO_LOW", "Show results only when at least 3 students answered, so nobody is identified");
        jdbc.update("update evaluation_rounds set name = coalesce(?, name), opens_on = ?, closes_on = ?, min_responses = ? where id = ?",
                cut(r.name(), 120), opens, closes, min, id);
        audit.record("EVALUATION_CHANGED", "EvaluationRound", id, Map.of("closes", closes.toString()));
        if (Boolean.TRUE.equals(r.tellStudents()) && !opens.isAfter(today()) && !closes.isBefore(today())) tell(id);
        return round(id);
    }

    @Transactional(readOnly = true)
    public RoundDetail round(Long id) {
        RoundView v = one(id);
        boolean all = access.has(READ_ALL);
        if (!all && !access.has(Role.HEAD_OF_DEPARTMENT)) throw new AccessDeniedException("Not allowed to read the evaluation results");
        List<ModuleSummary> mods = summaries(v, null).stream().filter(m -> all || access.headsDepartment(m.departmentId())).toList();
        return new RoundDetail(v, mods, roundQuestions(id));
    }

    @Transactional
    public ModuleReport moduleReport(Long roundId, Long offeringId) {
        RoundView v = one(roundId);
        ModuleSummary m = summaries(v, offeringId).stream().findFirst().orElseThrow(() -> new NotFoundException("Module not in this evaluation"));
        boolean lecturer = Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from offering_staff where offering_id = ? and user_id = ?)",
                Boolean.class, offeringId, CurrentUser.id().orElse(-1L)));
        boolean closed = "CLOSED".equals(v.state());
        if (!access.has(READ_ALL) && !access.headsDepartment(m.departmentId()) && !(lecturer && closed)) {
            throw new AccessDeniedException(lecturer ? "Lecturers see their results when the evaluation closes" : "Not allowed to read these results");
        }
        if (!m.enough()) {
            return new ModuleReport(v, m, List.of(), "Fewer than " + v.minResponses() + " students answered, so the results are not shown (they could identify someone).");
        }
        List<Question> qs = roundQuestions(roundId);
        Map<Long, int[]> counts = new HashMap<>();
        Map<Long, List<String>> comments = new LinkedHashMap<>();
        for (Question q : qs) {
            counts.put(q.id(), new int[5]);
            comments.put(q.id(), new ArrayList<>());
        }
        jdbc.query("select ratings::text, comments::text from evaluation_responses where round_id = ? and offering_id = ?", rs -> {
            Map<String, Integer> ratings = mapper.readValue(rs.getString(1), new TypeReference<Map<String, Integer>>() {
            });
            Map<String, String> texts = mapper.readValue(rs.getString(2), new TypeReference<Map<String, String>>() {
            });
            ratings.forEach((k, val) -> {
                int[] c = counts.get(Long.valueOf(k));
                if (c != null && val != null && val >= 1 && val <= 5) c[val - 1]++;
            });
            texts.forEach((k, t) -> {
                List<String> l = comments.get(Long.valueOf(k));
                if (l != null && t != null && !t.isBlank()) l.add(t);
            });
        }, roundId, offeringId);
        List<QuestionResult> out = new ArrayList<>();
        for (Question q : qs) {
            int[] c = counts.get(q.id());
            List<String> texts = new ArrayList<>(comments.get(q.id()));
            java.util.Collections.shuffle(texts);       // in no particular order: the order answers came in says something about who wrote them
            out.add(new QuestionResult(q.id(), q.section(), q.kind(), q.text(), "RATING".equals(q.kind()) ? average(c) : null, "RATING".equals(q.kind()) ? c : null,
                    "TEXT".equals(q.kind()) && closed ? texts : List.of()));
        }
        audit.record("EVALUATION_RESULTS_READ", "EvaluationRound", roundId, Map.of("offering", offeringId));
        return new ModuleReport(v, m, out, closed ? null : "Written comments are shown when the evaluation closes on " + DAY.format(v.closesOn()) + ".");
    }

    @Transactional
    public byte[] workbook(Long roundId) {
        RoundDetail d = round(roundId);
        List<Question> rating = d.questions().stream().filter(q -> "RATING".equals(q.kind())).toList();
        List<String> header = new ArrayList<>(List.of("Code", "Module", "Department", "Lecturers", "Registered", "Answered", "Response rate (%)", "Average (1-5)"));
        for (int i = 0; i < rating.size(); i++) header.add("Q" + (i + 1));
        List<List<Object>> rows = new ArrayList<>();
        for (ModuleSummary m : d.modules()) {
            List<Object> row = new ArrayList<>(Arrays.asList(m.code(), m.title(), m.department(), m.lecturers(), m.registered(), m.responses(), m.responseRate(),
                    m.enough() ? m.average() : "Too few answers"));
            if (m.enough()) {
                Map<Long, BigDecimal> avg = new HashMap<>();
                moduleReport(roundId, m.offeringId()).questions().forEach(q -> avg.put(q.questionId(), q.average()));
                for (Question q : rating) row.add(avg.get(q.id()));
            }
            rows.add(row);
        }
        List<List<Object>> qrows = new ArrayList<>();
        for (int i = 0; i < rating.size(); i++) qrows.add(Arrays.asList("Q" + (i + 1), rating.get(i).section(), rating.get(i).text()));
        int[] widths = new int[header.size()];
        Arrays.fill(widths, 10);
        widths[1] = 32;
        widths[2] = 22;
        widths[3] = 28;
        audit.record("EVALUATION_REPORT_DOWNLOADED", "EvaluationRound", roundId, Map.of("modules", rows.size()));
        return new SimpleXlsx().sheet("Modules", header, rows, widths).sheet("Questions", List.of("No", "Section", "Question"), qrows, 6, 12, 80).bytes();
    }

    /** For a lecturer: their modules in each evaluation, with results once it has closed. */
    @Transactional(readOnly = true)
    public List<LecturerRound> lecturerResults() {
        Long me = CurrentUser.requireId();
        List<LecturerRound> out = new ArrayList<>();
        for (RoundView v : rounds()) {
            List<Long> mine = jdbc.queryForList("""
                    select o.id from module_offerings o join offering_staff os on os.offering_id = o.id and os.user_id = ? where o.semester_id = ?""",
                    Long.class, me, v.semesterId());
            if (mine.isEmpty()) continue;
            List<ModuleSummary> mods = summaries(v, null).stream().filter(m -> mine.contains(m.offeringId()))
                    .map(m -> "CLOSED".equals(v.state()) ? m : new ModuleSummary(m.offeringId(), m.code(), m.title(), m.department(), m.departmentId(),
                            m.lecturers(), m.registered(), m.responses(), m.responseRate(), null, m.enough())).toList();
            out.add(new LecturerRound(v, mods));
        }
        return out;
    }

    // ------------------------------------------------------------------ students

    @Transactional(readOnly = true)
    public List<MyEvaluation> mine() {
        Long me = myStudentId();
        List<MyEvaluation> out = new ArrayList<>();
        for (RoundView v : jdbc.query(ROUND + " where r.opens_on <= ? and r.closes_on >= ? order by r.closes_on", (rs, n) -> round(rs), today(), today())) {
            List<MyEvaluationModule> mods = jdbc.query("""
                    select o.id, m.code, m.title,
                           (select string_agg(u.full_name, ', ' order by u.full_name) from offering_staff os join user_accounts u on u.id = os.user_id
                            where os.offering_id = o.id),
                           exists (select 1 from evaluation_done d where d.round_id = ? and d.offering_id = o.id and d.student_id = ?)
                    from semester_registrations sr join module_registrations mr on mr.semester_registration_id = sr.id
                    join module_offerings o on o.module_id = mr.module_id and o.semester_id = sr.semester_id join modules m on m.id = o.module_id
                    where sr.student_id = ? and sr.status = 'APPROVED' and sr.semester_id = ? order by m.code""",
                    (rs, n) -> new MyEvaluationModule(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)),
                    v.id(), me, me, v.semesterId());
            if (!mods.isEmpty()) out.add(new MyEvaluation(v, roundQuestions(v.id()), mods));
        }
        return out;
    }

    @Transactional
    public List<MyEvaluation> answer(Long roundId, Long offeringId, AnswerRequest r) {
        Long me = myStudentId();
        RoundView v = one(roundId);
        if (!"OPEN".equals(v.state())) throw new BusinessRuleException("EVALUATION_CLOSED", "This evaluation is not open");
        Boolean registered = jdbc.queryForObject("""
                select exists (select 1 from semester_registrations sr join module_registrations mr on mr.semester_registration_id = sr.id
                  join module_offerings o on o.module_id = mr.module_id and o.semester_id = sr.semester_id
                  where sr.student_id = ? and sr.status = 'APPROVED' and o.id = ? and sr.semester_id = ?)""", Boolean.class, me, offeringId, v.semesterId());
        if (!Boolean.TRUE.equals(registered)) throw new NotFoundException("You are not registered for this module");
        List<Question> qs = roundQuestions(roundId);
        Map<String, Integer> ratings = new LinkedHashMap<>();
        Map<String, String> comments = new LinkedHashMap<>();
        Map<String, Integer> in = r.ratings() == null ? Map.of() : r.ratings();
        Map<String, String> texts = r.comments() == null ? Map.of() : r.comments();
        for (Question q : qs) {
            String k = String.valueOf(q.id());
            if ("RATING".equals(q.kind())) {
                Integer val = in.get(k);
                if (val == null || val < 1 || val > 5) throw new BusinessRuleException("ANSWER_ALL", "Answer every question from 1 to 5");
                ratings.put(k, val);
            } else {
                String t = texts.get(k);
                if (t != null && !t.isBlank()) comments.put(k, t.trim().length() > 1000 ? t.trim().substring(0, 1000) : t.trim());
            }
        }
        int done = jdbc.update("""
                insert into evaluation_done (tenant_id, round_id, offering_id, student_id) values (cims_current_tenant(), ?, ?, ?) on conflict do nothing""",
                roundId, offeringId, me);
        if (done == 0) throw new BusinessRuleException("ALREADY_ANSWERED", "You have already evaluated this module");
        jdbc.update("insert into evaluation_responses (tenant_id, round_id, offering_id, ratings, comments) values (cims_current_tenant(), ?, ?, ?::jsonb, ?::jsonb)",
                roundId, offeringId, json(ratings), json(comments));
        // no audit record naming the student and the module: that would undo the anonymity
        return mine();
    }

    // ------------------------------------------------------------------ helpers

    private static final String ROUND = """
            select r.id, r.semester_id, se.name as semester, r.name, r.opens_on, r.closes_on, r.min_responses, r.created_by_name, r.created_at,
                   (select count(*) from module_offerings o where o.semester_id = r.semester_id) as modules,
                   (select count(*) from evaluation_responses x where x.round_id = r.id) as responses,
                   (select count(*) from semester_registrations sr join module_registrations mr on mr.semester_registration_id = sr.id
                    join module_offerings o on o.module_id = mr.module_id and o.semester_id = sr.semester_id
                    where sr.semester_id = r.semester_id and sr.status = 'APPROVED') as expected
            from evaluation_rounds r join semesters se on se.id = r.semester_id""";

    private RoundView round(ResultSet rs) throws SQLException {
        LocalDate opens = rs.getObject("opens_on", LocalDate.class), closes = rs.getObject("closes_on", LocalDate.class);
        LocalDate t = today();
        String state = t.isBefore(opens) ? "NOT_YET_OPEN" : t.isAfter(closes) ? "CLOSED" : "OPEN";
        return new RoundView(rs.getLong("id"), rs.getLong("semester_id"), rs.getString("semester"), rs.getString("name"), opens, closes,
                rs.getInt("min_responses"), state, rs.getInt("modules"), rs.getInt("responses"), rs.getInt("expected"), rs.getString("created_by_name"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private RoundView one(Long id) {
        return jdbc.query(ROUND + " where r.id = ?", (rs, n) -> round(rs), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Evaluation not found"));
    }

    private List<ModuleSummary> summaries(RoundView v, Long onlyOffering) {
        List<Long> ratingIds = roundQuestions(v.id()).stream().filter(q -> "RATING".equals(q.kind())).map(Question::id).toList();
        Map<Long, BigDecimal[]> sums = new HashMap<>();     // offering → [sum, count]
        jdbc.query("select offering_id, ratings::text from evaluation_responses where round_id = ?", rs -> {
            Map<String, Integer> ratings = mapper.readValue(rs.getString(2), new TypeReference<Map<String, Integer>>() {
            });
            BigDecimal[] s = sums.computeIfAbsent(rs.getLong(1), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            ratings.forEach((k, val) -> {
                if (ratingIds.contains(Long.valueOf(k)) && val != null) {
                    s[0] = s[0].add(BigDecimal.valueOf(val));
                    s[1] = s[1].add(BigDecimal.ONE);
                }
            });
        }, v.id());
        return jdbc.query("""
                select o.id, m.code, m.title, d.name, m.department_id,
                       (select string_agg(u.full_name, ', ' order by u.full_name) from offering_staff os join user_accounts u on u.id = os.user_id
                        where os.offering_id = o.id),
                       (select count(*) from semester_registrations sr join module_registrations mr on mr.semester_registration_id = sr.id
                        where sr.semester_id = o.semester_id and sr.status = 'APPROVED' and mr.module_id = o.module_id),
                       (select count(*) from evaluation_responses x where x.round_id = ? and x.offering_id = o.id)
                from module_offerings o join modules m on m.id = o.module_id left join departments d on d.id = m.department_id
                where o.semester_id = ? and (?::bigint is null or o.id = ?) order by m.code""", (rs, n) -> {
            int registered = rs.getInt(7), responses = rs.getInt(8);
            boolean enough = responses >= v.minResponses();
            BigDecimal[] s = sums.get(rs.getLong(1));
            BigDecimal avg = enough && s != null && s[1].signum() > 0 ? s[0].divide(s[1], 2, RoundingMode.HALF_UP) : null;
            BigDecimal rate = registered == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(responses * 100L).divide(BigDecimal.valueOf(registered), 0, RoundingMode.HALF_UP);
            return new ModuleSummary(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5) == null ? null : rs.getLong(5),
                    rs.getString(6), registered, responses, rate, avg, enough);
        }, v.id(), v.semesterId(), onlyOffering, onlyOffering);
    }

    private List<Question> roundQuestions(Long roundId) {
        String ids = jdbc.queryForObject("select question_ids::text from evaluation_rounds where id = ?", String.class, roundId);
        List<Long> order = mapper.readValue(ids, new TypeReference<List<Long>>() {
        });
        Map<Long, Question> all = new HashMap<>();
        jdbc.query("select id, section, kind, text, sort_order, active from evaluation_questions", (rs, n) -> question(rs)).forEach(q -> all.put(q.id(), q));
        return order.stream().map(all::get).filter(java.util.Objects::nonNull).toList();
    }

    private void tell(Long roundId) {
        RoundView v = one(roundId);
        List<String> phones = jdbc.queryForList("""
                select distinct s.phone from semester_registrations sr join students s on s.id = sr.student_id
                where sr.semester_id = ? and sr.status = 'APPROVED'""", String.class, v.semesterId());
        for (String p : phones) messaging.event("EVALUATION_OPEN", p, Map.of("semester", v.semester(), "closes", DAY.format(v.closesOn())));
        audit.record("EVALUATION_STUDENTS_TOLD", "EvaluationRound", roundId, Map.of("students", phones.size()));
    }

    private static BigDecimal average(int[] c) {
        int n = 0, sum = 0;
        for (int i = 0; i < c.length; i++) {
            n += c[i];
            sum += c[i] * (i + 1);
        }
        return n == 0 ? null : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
    }

    private Long myStudentId() {
        return jdbc.query("select id from students where user_id = ?", (rs, n) -> rs.getLong(1), CurrentUser.requireId()).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
    }

    private static Question question(ResultSet rs) throws SQLException {
        return new Question(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getBoolean(6));
    }

    private static LocalDate today() {
        return LocalDate.now(TZ);
    }

    private String json(Object o) {
        return mapper.writeValueAsString(o);
    }

    private static String oneOf(String v, Set<String> allowed, String what) {
        String u = v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(u)) throw new BusinessRuleException("FIELD_INVALID", "Choose " + what);
        return u;
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
