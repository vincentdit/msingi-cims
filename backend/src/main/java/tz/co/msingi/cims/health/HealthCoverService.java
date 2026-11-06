package tz.co.msingi.cims.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.Csv;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.integration.http.ExternalSystemUnavailableException;
import tz.co.msingi.cims.integration.nhif.NhifClient;
import tz.co.msingi.cims.integration.settings.IntegrationSettingsService;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.mfa.SecretCipher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Students' health insurance with NHIF, through NHIF's Student Registration API. Every student needs cover for the academic year,
 * in one of two ways:
 * <ul>
 *   <li><b>Own card</b>: a student already covered (e.g. as a parent's dependant) gives the NHIF card number; CIMS checks it with
 *       NHIF (active, held by this student) and records the result.</li>
 *   <li><b>Through the institution</b>, the institution being NHIF's registration agent:
 *       READY → the Registrar registers the students with NHIF → REGISTERED → applies for their cards in a batch → APPLIED →
 *       NHIF gives each student a control number → AWAITING_PAYMENT → the student pays NHIF → PAID → NHIF issues the card →
 *       ACTIVE. NHIF reports each step by calling the institution back ({@link #callback}); the Registrar can also fetch the control
 *       numbers. A student who already had a card through the institution renews it (category 2) without registering again.</li>
 * </ul>
 * When the institution requires it, semester registration waits until the student is covered or has paid NHIF.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthCoverService {

    public static final String PURPOSE = "NHIF_CONTRIBUTION";
    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    /** Statuses as shown: the stored ones, EXPIRED for an active card past its date, NONE for no cover recorded. */
    static final List<String> STATUSES = List.of("ACTIVE", "EXPIRED", "INVALID", "READY", "REGISTERED", "APPLIED", "AWAITING_PAYMENT", "PAID",
            "SUBMITTED", "REJECTED", "NONE");
    /** Under way through the institution (counted together in the overview). */
    static final List<String> UNDER_WAY = List.of("READY", "REGISTERED", "APPLIED", "AWAITING_PAYMENT", "PAID", "SUBMITTED");

    private final JdbcTemplate jdbc;
    private final NhifClient nhif;
    private final NhifSetupService setup;
    private final IntegrationSettingsService settings;
    private final BillingService billing;
    private final SecretCipher cipher;
    private final Messaging messaging;
    private final AuditService audit;
    private final Environment env;

    public record InvoiceInfo(Long id, String invoiceNo, BigDecimal amount, BigDecimal balance, String status, String controlNo) {
    }

    /** NHIF's bill for the student's card: the control number to pay, the amount, and when NHIF confirmed the payment. */
    public record NhifBill(String controlNo, String invoiceNo, BigDecimal amount, LocalDate paidOn) {
    }

    public record Cover(Long id, Long studentId, String registrationNo, String studentName, String programmeCode, int yearOfStudy,
                        String academicYear, String route, String status, String cardNo, String holderName, String scheme, LocalDate validTo,
                        String reason, OffsetDateTime checkedAt, String batchRef, InvoiceInfo invoice, NhifBill nhifBill, Integer category) {
    }

    /** A student's cover for the year, with what the student can do about it. */
    public record StudentCover(Long studentId, boolean enabled, boolean required, BigDecimal contribution, Long academicYearId, String academicYear,
                               Cover cover, boolean covered, String message) {
    }

    public record ProgrammeRow(String code, String name, long students, long active, long underWay, long problems, long none) {
    }

    public record Batch(Long id, String kind, String academicYear, String batchRef, String status, int students, int registered, int rejected,
                        String submittedByName, OffsetDateTime submittedAt, OffsetDateTime checkedAt, String intakeCode) {
    }

    public record Overview(boolean enabled, boolean required, BigDecimal contribution, String institutionCode, String mode, boolean setUp,
                           Long academicYearId, String academicYear, long students, Map<String, Long> counts, long expiringSoon,
                           List<ProgrammeRow> programmes, List<Batch> batches) {
    }

    public record Result(String summary, int done, List<String> problems) {
    }

    // ------------------------------------------------------------------ the student's cover

    @Transactional
    public StudentCover mine() {
        return studentCover(me(), null, null);
    }

    @Transactional
    public StudentCover forStudent(Long studentId) {
        return studentCover(student(studentId), null, null);
    }

    @Transactional
    public StudentCover recordMyCard(String cardNo) {
        return recordCard(me(), cardNo);
    }

    @Transactional
    public StudentCover recordCard(Long studentId, String cardNo) {
        return recordCard(student(studentId), cardNo);
    }

    @Transactional
    public StudentCover chooseInstitutionForMe() {
        return chooseInstitution(me());
    }

    @Transactional
    public StudentCover chooseInstitution(Long studentId) {
        return chooseInstitution(student(studentId));
    }

    /**
     * Whether the student may register for a semester of the given academic year: always when the institution does not require
     * health cover; otherwise when the cover is active, or the student has paid NHIF (the card follows).
     */
    @Transactional(readOnly = true)
    public boolean coveredForRegistration(Long studentId, Long academicYearId) {
        var s = settings.get();
        if (!s.nhifEnabled() || !s.nhifRequired()) return true;
        Integer n = jdbc.queryForObject("""
                select count(*) from health_covers c left join invoices i on i.id = c.invoice_id
                where c.student_id = ? and c.academic_year_id = ?
                  and (c.status in ('PAID', 'SUBMITTED') or (c.status = 'ACTIVE' and (c.valid_to is null or c.valid_to >= ?))
                       or (c.status in ('READY', 'AWAITING_PAYMENT') and i.status in ('PAID', 'CREDITED')))""",
                Integer.class, studentId, academicYearId, today());
        return n != null && n > 0;
    }

    private StudentCover recordCard(Person p, String raw) {
        settings.nhifCode();
        String card = NhifClient.normalise(raw);
        if (card == null) throw new BusinessRuleException("INVALID_CARD", "Enter the NHIF card number as printed on the card (letters and digits).");
        Map<String, Object> y = year(null);
        Long yid = (Long) y.get("id");
        Integer other = jdbc.queryForObject("select count(*) from health_covers where card_no = ? and student_id <> ? and academic_year_id = ?",
                Integer.class, card, p.id(), yid);
        if (other != null && other > 0) {
            throw new BusinessRuleException("CARD_IN_USE", "This NHIF card is already recorded for another student. Contact the Registrar if it is yours.");
        }
        Cover existing = cover(p.id(), yid);
        if (existing != null && "INSTITUTION".equals(existing.route())) {
            if (List.of("APPLIED", "AWAITING_PAYMENT", "PAID", "SUBMITTED", "ACTIVE").contains(existing.status())) {
                throw new BusinessRuleException("REGISTRATION_UNDER_WAY", "ACTIVE".equals(existing.status())
                        ? "You are already covered through the institution (card " + existing.cardNo() + ")."
                        : "The institution has already applied to NHIF for your card. Ask the Registrar if you also have your own card.");
            }
            if (existing.invoice() != null) billing.cancelIfUnpaid(existing.invoice().id(), "Student gave their own NHIF card instead");
        }
        NhifClient.CardCheck c = nhif.verifyStudentCard(setup.agent(), card);
        String status;
        String reason = null;
        if (c.error() != null) {
            status = "INVALID";
            reason = "NHIF does not know this card number" + (c.error().message() == null ? "" : " (" + c.error().message() + ")") + ". Check the number on the card.";
        } else if (!c.active()) {
            status = "INVALID";
            reason = "NHIF says the cover on this card is not active" + (c.remarks() == null ? "" : " (" + c.remarks() + ")")
                    + ". Renew it, or choose to be registered through the institution.";
        } else if (c.fullName() != null && !sameSurname(c.fullName(), p.surname())) {
            status = "INVALID";
            reason = "NHIF holds this card for " + c.fullName() + ". Give your own card number.";
        } else {
            status = "ACTIVE";
        }
        Long id = upsert(p.id(), yid, "OWN_CARD", status, card, c.fullName(), "Student card", null, reason);
        jdbc.update("update health_covers set checked_at = now(), verification_code = ? where id = ?", c.verificationCode(), id);
        audit.record("NHIF_CARD_CHECKED", "Student", p.id(), Map.of("card", "…" + card.substring(Math.max(0, card.length() - 4)), "status", status));
        return studentCover(p, y, "ACTIVE".equals(status) ? "NHIF confirmed the card: you are covered." : reason);
    }

    private StudentCover chooseInstitution(Person p) {
        settings.nhifCode();
        Map<String, Object> y = year(null);
        Long yid = (Long) y.get("id");
        Cover existing = cover(p.id(), yid);
        if (existing != null && "INSTITUTION".equals(existing.route())) {
            if ("REJECTED".equals(existing.status())) {
                String renewal = previousInstitutionCard(p.id(), yid);
                jdbc.update("""
                        update health_covers set status = ?, card_no = ?, category = ?, reason = null, batch_id = null, application_batch_id = null,
                          correlation_id = null, nhif_control_no = null, nhif_invoice_no = null, nhif_amount = null, updated_at = now() where id = ?""",
                        renewal != null ? "REGISTERED" : "READY", renewal, renewal != null ? 2 : null, existing.id());
                audit.record("NHIF_REGISTRATION_RETRIED", "Student", p.id(), Map.of("year", y.get("code")));
                return studentCover(p, y, "The institution will send you to NHIF again.");
            }
            return studentCover(p, y, null);
        }
        if (existing != null && "OWN_CARD".equals(existing.route()) && "ACTIVE".equals(existing.status())) {
            throw new BusinessRuleException("ALREADY_COVERED", "You are already covered by NHIF card " + existing.cardNo() + ".");
        }
        enrol(p.id(), yid);
        audit.record("NHIF_INSTITUTION_CHOSEN", "Student", p.id(), Map.of("year", y.get("code")));
        return studentCover(p, y, "The institution will register you with NHIF and apply for your card. NHIF then sends you a control number to "
                + "pay the student contribution" + amountText() + ".");
    }

    /** Puts a student on the institution route: renewing a card the institution got them before, or registering them with NHIF. */
    private Long enrol(Long studentId, Long yid) {
        String renewal = previousInstitutionCard(studentId, yid);
        Long id = upsert(studentId, yid, "INSTITUTION", renewal != null ? "REGISTERED" : "READY", renewal, null, null, null, null);
        jdbc.update("update health_covers set category = ? where id = ?", renewal != null ? 2 : null, id);
        return id;
    }

    /** The student card NHIF issued through the institution in an earlier year, renewed with a category 2 application. */
    private String previousInstitutionCard(Long studentId, Long yid) {
        return jdbc.queryForList("""
                select c.card_no from health_covers c join academic_years y on y.id = c.academic_year_id
                where c.student_id = ? and c.academic_year_id <> ? and c.route = 'INSTITUTION' and c.status = 'ACTIVE' and c.card_no is not null
                order by y.start_date desc limit 1""", String.class, studentId, yid).stream().findFirst().orElse(null);
    }

    private String amountText() {
        BigDecimal a = settings.get().nhifContribution();
        return a == null || a.signum() <= 0 ? "" : " (about TZS " + String.format("%,.0f", a) + ")";
    }

    // ------------------------------------------------------------------ the office

    @Transactional(readOnly = true)
    public Overview overview(Long academicYearId) {
        var s = settings.get();
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        Map<String, Long> counts = new LinkedHashMap<>();
        STATUSES.forEach(k -> counts.put(k, 0L));
        long students = 0;
        Map<String, long[]> byProgramme = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                select p.code, p.name, """ + SHOWN + """
                 as shown, count(*) as n
                from students s join programmes p on p.id = s.programme_id
                left join health_covers c on c.student_id = s.id and c.academic_year_id = ?
                where s.status in ('ADMITTED', 'ACTIVE') group by p.code, p.name, 3 order by p.name""", today(), yid)) {
            String st = (String) r.get("shown");
            long n = ((Number) r.get("n")).longValue();
            counts.merge(st, n, Long::sum);
            students += n;
            String code = (String) r.get("code");
            names.put(code, (String) r.get("name"));
            long[] a = byProgramme.computeIfAbsent(code, k -> new long[5]);
            a[0] += n;
            if ("ACTIVE".equals(st)) a[1] += n;
            else if (UNDER_WAY.contains(st)) a[2] += n;
            else if ("NONE".equals(st)) a[4] += n;
            else a[3] += n;
        }
        List<ProgrammeRow> rows = byProgramme.entrySet().stream()
                .map(e -> new ProgrammeRow(e.getKey(), names.get(e.getKey()), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3], e.getValue()[4]))
                .toList();
        Long soon = jdbc.queryForObject("""
                select count(*) from health_covers c join students s on s.id = c.student_id
                where c.academic_year_id = ? and c.status = 'ACTIVE' and c.valid_to between ? and ? and s.status in ('ADMITTED', 'ACTIVE')""",
                Long.class, yid, LocalDate.now(ZONE), LocalDate.now(ZONE).plusDays(30));
        List<Batch> batches = jdbc.query(BATCH_SQL + " where b.academic_year_id = ? order by b.submitted_at desc, b.id desc limit 20", this::batchRow, yid);
        String code = s.nhifCode() != null ? s.nhifCode() : settings.institutionCode();
        var st = setup.view();
        return new Overview(s.nhifEnabled(), s.nhifRequired(), s.nhifContribution(), code, st.mode(), st.complete() || "SIMULATED".equals(st.mode()), yid,
                (String) y.get("code"), students, counts, soon == null ? 0 : soon, rows, batches);
    }

    /** Current students with their cover for the year; {@code status} as shown (NONE for no cover), at most 1,000 rows. */
    @Transactional(readOnly = true)
    public List<Cover> list(Long academicYearId, String status, Long programmeId, String q) {
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        List<Object> args = new ArrayList<>(List.of(today(), yid));
        StringBuilder where = new StringBuilder(" where s.status in ('ADMITTED', 'ACTIVE')");
        if (status != null && !status.isBlank()) {
            where.append(" and ").append(SHOWN).append(" = ?");
            args.add(today());
            args.add(status.trim().toUpperCase());
        }
        if (programmeId != null) {
            where.append(" and p.id = ?");
            args.add(programmeId);
        }
        if (q != null && !q.isBlank()) {
            where.append(" and (upper(s.registration_no) like ? or upper(concat_ws(' ', s.first_name, s.middle_name, s.surname)) like ? or c.card_no like ?"
                    + " or c.nhif_control_no like ?)");
            String like = "%" + q.trim().toUpperCase() + "%";
            args.add(like);
            args.add(like);
            args.add(like.replaceAll("[^A-Z0-9%]", ""));
            args.add(like.replaceAll("[^0-9%]", ""));
        }
        return jdbc.query(COVER_SQL + where + " order by p.code, s.year_of_study, s.surname, s.first_name limit 1000", (rs, i) -> cover(rs, (String) y.get("code")),
                args.toArray());
    }

    /** Checks every own card recorded for the year with NHIF again (cover ends, cards are renewed). */
    @Transactional
    public Result recheck(Long academicYearId) {
        settings.nhifCode();
        NhifClient.Agent agent = setup.agent();
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.id, c.card_no, c.status, s.surname, s.registration_no from health_covers c join students s on s.id = c.student_id
                where c.academic_year_id = ? and c.route = 'OWN_CARD' and c.card_no is not null and s.status in ('ADMITTED', 'ACTIVE') order by c.id""", yid);
        int checked = 0, changed = 0;
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            NhifClient.CardCheck c;
            try {
                c = nhif.verifyStudentCard(agent, (String) r.get("card_no"));
            } catch (ExternalSystemUnavailableException e) {
                problems.add("NHIF stopped answering after " + checked + " card(s): " + e.getMessage());
                break;
            }
            checked++;
            boolean ok = c.active() && (c.fullName() == null || sameSurname(c.fullName(), (String) r.get("surname")));
            String status = ok ? "ACTIVE" : "INVALID";
            String reason = ok ? null : c.error() != null ? "NHIF does not know this card number"
                    : !c.active() ? "NHIF says the cover is not active" + (c.remarks() == null ? "" : " (" + c.remarks() + ")")
                    : "NHIF holds this card for " + c.fullName();
            if (!status.equals(r.get("status"))) {
                changed++;
                if (!ok) problems.add(r.get("registration_no") + ": " + reason);
            }
            jdbc.update("""
                    update health_covers set status = ?, holder_name = coalesce(?, holder_name), verification_code = ?, reason = ?, checked_at = now(),
                      updated_at = now() where id = ?""", status, c.fullName(), c.verificationCode(), reason, r.get("id"));
        }
        audit.record("NHIF_CARDS_RECHECKED", "HealthCover", null, Map.of("year", y.get("code"), "checked", checked, "changed", changed));
        return new Result(checked + " card(s) checked with NHIF, " + changed + " changed", checked, problems);
    }

    /** Puts every current student with no cover recorded for the year on the institution route (registered by the institution). */
    @Transactional
    public Result enrolUncovered(Long academicYearId) {
        settings.nhifCode();
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        List<Long> ids = jdbc.queryForList("""
                select s.id from students s where s.status in ('ADMITTED', 'ACTIVE')
                  and not exists (select 1 from health_covers c where c.student_id = s.id and c.academic_year_id = ?) order by s.registration_no""",
                Long.class, yid);
        ids.forEach(id -> enrol(id, yid));
        audit.record("NHIF_STUDENTS_ENROLLED", "HealthCover", null, Map.of("year", y.get("code"), "students", ids.size()));
        return new Result(ids.isEmpty() ? "Every current student already has cover recorded for " + y.get("code")
                : ids.size() + " student(s) to be registered with NHIF through the institution", ids.size(), List.of());
    }

    /**
     * Step 1: registers with NHIF every student waiting (READY). Students NHIF takes become REGISTERED; those it refuses are REJECTED
     * with NHIF's reason. Students CIMS cannot send yet (no Form Four index number; neither NIDA number nor photo) are listed.
     */
    @Transactional
    public Result registerWithNhif(Long academicYearId) {
        settings.nhifCode();
        NhifClient.Agent agent = setup.agent();
        int level = setup.educationLevel();
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        String yearCode = (String) y.get("code");
        List<Map<String, Object>> rows = jdbc.queryForList(STUDENT_SQL + " where c.academic_year_id = ? and c.status = 'READY' order by s.registration_no", yid);
        if (rows.isEmpty()) throw new BusinessRuleException("NOTHING_TO_SEND", "No student is waiting to be registered with NHIF for " + yearCode + ".");
        List<NhifClient.Registration> list = new ArrayList<>();
        List<Long> covers = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String index = NhifClient.formFourIndex((String) r.get("necta_index_number"), r.get("necta_exam_year") == null ? null : ((Number) r.get("necta_exam_year")).intValue());
            String nin = nin(r);
            String photo = nin == null ? NhifPhoto.base64(r.get("photo") instanceof byte[] b ? b : null) : null;
            if (index == null) {
                problems.add(r.get("registration_no") + ": CIMS has no Form Four index number for this student (Students › edit)");
                continue;
            }
            if (nin == null && photo == null) {
                problems.add(r.get("registration_no") + ": NHIF needs the NIDA number or a passport photo (Students › edit)");
                continue;
            }
            java.sql.Date dob = (java.sql.Date) r.get("date_of_birth");
            list.add(new NhifClient.Registration((String) r.get("first_name"), (String) r.get("middle_name"), (String) r.get("surname"),
                    "F".equals(r.get("sex")) ? "Female" : "Male", dob == null ? null : dob.toLocalDate(), (String) r.get("email"), localPhone((String) r.get("phone")),
                    nin, index, (String) r.get("registration_no"), level, (String) r.get("programme"), (String) r.get("faculty"),
                    ((Number) r.get("year_of_study")).intValue(), yearCode, intake(r), photo));
            covers.add(((Number) r.get("cover_id")).longValue());
        }
        if (list.isEmpty()) return new Result("No student could be sent to NHIF yet", 0, problems);
        NhifClient.BulkResult res = nhif.registerStudents(agent, list);
        Long batchId = newBatch(yid, "REGISTRATION", "REG-" + yearCode.substring(0, 4) + "-" + System.currentTimeMillis() % 1_000_000, list.size(), null);
        java.util.Set<Integer> failed = new java.util.HashSet<>();
        for (NhifClient.Failure f : res.failed()) {
            int i = f.index() >= 0 && f.index() < list.size() ? f.index() : indexOf(list, f.identifier());
            if (i < 0) continue;
            failed.add(i);
            reject(covers.get(i), f.reason());
            problems.add(list.get(i).admissionNo() + ": " + f.reason());
        }
        for (int i = 0; i < covers.size(); i++) {
            if (failed.contains(i)) continue;
            jdbc.update("update health_covers set status = 'REGISTERED', batch_id = ?, reason = null, updated_at = now() where id = ?", batchId, covers.get(i));
        }
        finishBatch(batchId, list.size() - failed.size(), failed.size(), res.failed());
        audit.record("NHIF_STUDENTS_REGISTERED", "NhifRegistrationBatch", batchId, Map.of("year", yearCode, "sent", list.size(), "rejected", failed.size()));
        return new Result((list.size() - failed.size()) + " of " + list.size() + " student(s) registered with NHIF" + (failed.isEmpty() ? "" : "; "
                + failed.size() + " not accepted"), list.size() - failed.size(), problems);
    }

    /**
     * Step 2: applies to NHIF for the cards of every REGISTERED student, in one batch (category 1: new card by NIDA number;
     * category 2: renewal of a card the institution got the student before). NHIF takes the whole batch or none of it; when it
     * refuses, the students it named are REJECTED with its reason and the others stay REGISTERED, to be sent again.
     */
    @Transactional
    public Result applyForCards(Long academicYearId, String intakeCode) {
        String agentNo = settings.nhifCode();
        NhifClient.Agent agent = setup.agent();
        int level = setup.educationLevel();
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        String yearCode = (String) y.get("code");
        List<Map<String, Object>> rows = jdbc.queryForList(STUDENT_SQL + " where c.academic_year_id = ? and c.status = 'REGISTERED' order by s.registration_no", yid);
        if (rows.isEmpty()) throw new BusinessRuleException("NOTHING_TO_APPLY", "No student registered with NHIF is waiting for a card application for " + yearCode + ".");
        String batchIntake = intakeCode != null && intakeCode.matches("\\d{6}") ? intakeCode : intake(rows.get(0));
        Long batchId = newBatch(yid, "CARD_APPLICATION", "pending", rows.size(), batchIntake);
        String batchNo = NhifClient.batchNo(agentNo, yearCode, String.format("%05d", batchId) + "-" + settings.institutionCode().replaceAll("[^A-Za-z0-9]", ""));
        jdbc.update("update nhif_registration_batches set batch_ref = ? where id = ?", batchNo, batchId);
        List<NhifClient.CardApplicationItem> items = new ArrayList<>();
        List<Long> covers = new ArrayList<>();
        List<String> regNos = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Long coverId = ((Number) r.get("cover_id")).longValue();
            String card = (String) r.get("card_no");
            String nin = nin(r);
            int category = card != null ? 2 : 1;
            if (category == 1 && nin == null) {
                problems.add(r.get("registration_no") + ": NHIF needs the student's NIDA number for a new card (Students › edit)");
                continue;
            }
            items.add(new NhifClient.CardApplicationItem("HC" + coverId + "-" + batchId, category == 1 ? nin : null, localPhone((String) r.get("phone")),
                    intake(r), category == 2 ? card : null, level, ((Number) r.get("year_of_study")).intValue(), category));
            covers.add(coverId);
            regNos.add((String) r.get("registration_no"));
        }
        if (items.isEmpty()) {
            jdbc.update("delete from nhif_registration_batches where id = ?", batchId);
            return new Result("No card application could be sent to NHIF yet", 0, problems);
        }
        jdbc.update("update nhif_registration_batches set students = ? where id = ?", items.size(), batchId);
        NhifClient.BulkResult res = nhif.applyForCards(agent, batchNo, batchIntake, "CIMS card application, " + yearCode + ", " + items.size() + " student(s)", items);
        if (!res.fullSuccess() || res.succeeded() == 0) {
            int named = 0;
            for (NhifClient.Failure f : res.failed()) {
                int i = f.index() >= 0 && f.index() < items.size() ? f.index() : -1;
                for (int k = 0; k < items.size() && i < 0; k++) if (items.get(k).correlationId().equals(f.identifier())) i = k;
                if (i < 0) {
                    problems.add("NHIF: " + f.reason());
                    continue;
                }
                named++;
                reject(covers.get(i), f.reason());
                problems.add(regNos.get(i) + ": " + f.reason());
            }
            finishBatch(batchId, 0, named, res.failed());
            audit.record("NHIF_CARD_APPLICATION_REFUSED", "NhifRegistrationBatch", batchId, Map.of("batchNo", batchNo, "items", items.size()));
            return new Result("NHIF did not take the card application (it takes all students or none): " + named + " student(s) need correcting; the others "
                    + "stay ready to be sent again", 0, problems);
        }
        for (int i = 0; i < covers.size(); i++) {
            jdbc.update("""
                    update health_covers set status = 'APPLIED', application_batch_id = ?, correlation_id = ?, category = ?, reason = null, updated_at = now()
                    where id = ?""", batchId, items.get(i).correlationId(), items.get(i).category(), covers.get(i));
        }
        finishBatch(batchId, items.size(), 0, List.of());
        audit.record("NHIF_CARDS_APPLIED", "NhifRegistrationBatch", batchId, Map.of("batchNo", batchNo, "students", items.size()));
        int numbered = 0;
        try {
            numbered = fetchControlNumbers(batchId);
        } catch (ExternalSystemUnavailableException e) {
            log.warn("NHIF control numbers for {} not available yet: {}", batchNo, e.getMessage());
        }
        return new Result("Card application " + batchNo + " sent for " + items.size() + " student(s)" + (numbered > 0 ? "; " + numbered
                + " control number(s) received" : "; NHIF sends the control numbers shortly"), items.size(), problems);
    }

    /** Asks NHIF for the control numbers of a card application (when its callbacks have not all arrived). */
    @Transactional
    public Batch check(Long batchId) {
        Batch b = batch(batchId);
        if ("CARD_APPLICATION".equals(b.kind())) fetchControlNumbers(batchId);
        jdbc.update("update nhif_registration_batches set checked_at = now() where id = ?", batchId);
        return batch(batchId);
    }

    private int fetchControlNumbers(Long batchId) {
        Batch b = batch(batchId);
        int n = 0;
        for (NhifClient.ControlNumber cn : nhif.controlNumbers(setup.agent(), b.batchRef())) {
            if (cn.controlNo() == null) continue;
            List<Long> ids = jdbc.queryForList("select id from health_covers where correlation_id = ? and application_batch_id = ?", Long.class,
                    cn.correlationId(), batchId);
            if (ids.isEmpty()) continue;
            if (controlNumber(ids.get(0), cn.controlNo(), cn.invoiceNo(), cn.amount())) n++;
        }
        return n;
    }

    /** NHIF's control number for a cover; the student is told by SMS. False when the cover already had it (or is further on). */
    boolean controlNumber(Long coverId, String controlNo, String invoiceNo, BigDecimal amount) {
        int changed = jdbc.update("""
                update health_covers set status = 'AWAITING_PAYMENT', nhif_control_no = ?, nhif_invoice_no = coalesce(?, nhif_invoice_no), nhif_amount = ?,
                  checked_at = now(), updated_at = now() where id = ? and status in ('APPLIED', 'REGISTERED')""", controlNo, invoiceNo, amount, coverId);
        if (changed == 0) return false;
        String phone = jdbc.queryForObject("select s.phone from health_covers c join students s on s.id = c.student_id where c.id = ?", String.class, coverId);
        messaging.event("NHIF_CONTROL_NUMBER", phone, Map.of("controlNo", controlNo, "amount", amount == null ? "the student contribution" : String.format("%,.0f", amount)));
        return true;
    }

    /** NHIF confirms the student paid. */
    boolean paid(Long coverId, String controlNo, LocalDate on) {
        int changed = jdbc.update("""
                update health_covers set status = 'PAID', nhif_paid_on = ?, checked_at = now(), updated_at = now()
                where id = ? and status in ('AWAITING_PAYMENT', 'APPLIED')""", on == null ? today() : on, coverId);
        if (changed == 0) return false;
        String phone = jdbc.queryForObject("select s.phone from health_covers c join students s on s.id = c.student_id where c.id = ?", String.class, coverId);
        messaging.event("NHIF_PAYMENT_CONFIRMED", phone, Map.of("controlNo", controlNo == null ? "" : controlNo));
        return true;
    }

    /** NHIF issued the card: the cover is active until the end of the academic year. */
    boolean cardIssued(Long coverId, String cardNo) {
        Map<String, Object> c = jdbc.queryForMap("""
                select c.status, s.phone, upper(concat_ws(' ', s.first_name, s.middle_name, s.surname)) as name, y.end_date
                from health_covers c join students s on s.id = c.student_id join academic_years y on y.id = c.academic_year_id where c.id = ?""", coverId);
        if ("ACTIVE".equals(c.get("status"))) return false;
        LocalDate validTo = ((java.sql.Date) c.get("end_date")).toLocalDate();
        jdbc.update("""
                update health_covers set status = 'ACTIVE', card_no = ?, valid_to = ?, holder_name = ?, scheme = 'Student package', reason = null,
                  checked_at = now(), updated_at = now() where id = ?""", NhifClient.normalise(cardNo) != null ? NhifClient.normalise(cardNo) : cardNo,
                validTo, c.get("name"), coverId);
        messaging.event("NHIF_CARD_ISSUED", (String) c.get("phone"), Map.of("cardNo", cardNo, "validTo", DAY.format(validTo)));
        return true;
    }

    void rejected(Long coverId, String reason) {
        reject(coverId, reason);
        String phone = jdbc.queryForObject("select s.phone from health_covers c join students s on s.id = c.student_id where c.id = ?", String.class, coverId);
        messaging.event("NHIF_REGISTRATION_REJECTED", phone, Map.of("reason", reason == null ? "no reason given" : reason));
    }

    /**
     * Rehearsal only (NHIF simulated): what NHIF's payment and card callbacks would do for every student awaiting payment, as if each
     * had paid NHIF, so the whole flow can be tried before the institution has NHIF credentials.
     */
    @Transactional
    public Result simulatePaymentsAndCards(Long academicYearId) {
        if ("live".equalsIgnoreCase(env.getProperty("cims.nhif.mode", "simulated"))) {
            throw new BusinessRuleException("NHIF_LIVE", "NHIF is live: payments and cards come from NHIF itself.");
        }
        Long yid = (Long) year(academicYearId).get("id");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.id, c.nhif_control_no from health_covers c where c.academic_year_id = ? and c.status in ('AWAITING_PAYMENT', 'PAID') order by c.id""", yid);
        int n = 0;
        for (Map<String, Object> r : rows) {
            Long id = ((Number) r.get("id")).longValue();
            paid(id, (String) r.get("nhif_control_no"), today());
            cardIssued(id, String.format("SN%09d", Math.floorMod(("card" + id).hashCode(), 1_000_000_000)));
            n++;
        }
        audit.record("NHIF_SIMULATED_PAYMENTS", "HealthCover", null, Map.of("students", n));
        return new Result(n == 0 ? "No student is waiting to pay NHIF" : n + " student(s) marked paid and given a card (simulated)", n, List.of());
    }

    @Transactional(readOnly = true)
    public List<Cover> batchItems(Long batchId) {
        Batch b = batch(batchId);
        Long yid = jdbc.queryForObject("select academic_year_id from nhif_registration_batches where id = ?", Long.class, batchId);
        String col = "CARD_APPLICATION".equals(b.kind()) ? "c.application_batch_id" : "c.batch_id";
        return jdbc.query(COVER_SQL + " where " + col + " = ? order by s.registration_no", (rs, i) -> cover(rs, b.academicYear()), today(), yid, batchId);
    }

    /** NHIF's intakes for the institution (to choose the intake of a card application). */
    @Transactional(readOnly = true)
    public List<NhifClient.Intake> intakes() {
        settings.nhifCode();
        return nhif.intakes(setup.agent());
    }

    /**
     * Students waiting to be registered and those under way, in the columns of NHIF's CreateStudentDto, for NHIF's portal when the
     * API is not used.
     */
    @Transactional
    public String registrationFile(Long academicYearId) {
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        int level = setup.educationLevel();
        StringBuilder sb = new StringBuilder(Csv.line("FirstName", "MiddleName", "Surname", "Gender", "DateOfBirth", "Email", "MobileNo", "NationalId",
                "FormFourIndexNo", "AdmissionNo", "EducationLevelID", "ProgrammeOfStudy", "CollageFaculty", "YearOfStudy", "AcademicYear", "IntakeCode",
                "CardNo", "ControlNo", "Status"));
        int n = 0;
        for (Map<String, Object> r : jdbc.queryForList(STUDENT_SQL + " where c.academic_year_id = ? and c.status in ('READY', 'REGISTERED', 'APPLIED', "
                + "'AWAITING_PAYMENT', 'PAID', 'SUBMITTED') order by s.registration_no", yid)) {
            n++;
            sb.append(Csv.line(r.get("first_name"), r.get("middle_name"), r.get("surname"), "F".equals(r.get("sex")) ? "Female" : "Male", r.get("date_of_birth"),
                    r.get("email"), localPhone((String) r.get("phone")), nin(r), NhifClient.formFourIndex((String) r.get("necta_index_number"),
                            r.get("necta_exam_year") == null ? null : ((Number) r.get("necta_exam_year")).intValue()), r.get("registration_no"), level,
                    r.get("programme"), r.get("faculty"), r.get("year_of_study"), y.get("code"), intake(r), r.get("card_no"), r.get("nhif_control_no"), r.get("status")));
        }
        audit.record("NHIF_FILE_DOWNLOADED", "HealthCover", null, Map.of("year", y.get("code"), "students", n));
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The status as shown, from the student row {@code s} and the cover {@code c}: NONE without a cover, EXPIRED for an active
     * cover past its date. Takes one argument: today's date.
     */
    private static final String SHOWN = "(case when c.id is null then 'NONE' when c.status = 'ACTIVE' and c.valid_to < ? then 'EXPIRED' else c.status end)";

    private static final String COVER_SQL = """
            select c.id, s.id as student_id, s.registration_no, concat_ws(' ', s.first_name, s.middle_name, s.surname) as name, p.code, s.year_of_study,
                   c.route,\s""" + SHOWN + """
             as shown, c.card_no, c.holder_name, c.scheme, c.valid_to, c.reason, c.checked_at, coalesce(ab.batch_ref, b.batch_ref) as batch_ref,
                   i.id as invoice_id, i.invoice_no, i.total_amount, i.total_amount - i.paid_amount as balance, i.status as invoice_status,
                   c.nhif_control_no, c.nhif_invoice_no, c.nhif_amount, c.nhif_paid_on, c.category
            from students s join programmes p on p.id = s.programme_id
            left join health_covers c on c.student_id = s.id and c.academic_year_id = ?
            left join nhif_registration_batches b on b.id = c.batch_id
            left join nhif_registration_batches ab on ab.id = c.application_batch_id
            left join invoices i on i.id = c.invoice_id""";

    /** A cover with what NHIF needs about the student (Form Four index from the application, faculty, intake, photo). */
    private static final String STUDENT_SQL = """
            select c.id as cover_id, c.status, c.card_no, c.nhif_control_no, s.registration_no, s.first_name, s.middle_name, s.surname, s.sex,
                   s.date_of_birth, s.nin_encrypted, s.phone, s.email, s.year_of_study, p.name as programme,
                   coalesce(f.name, d.name) as faculty, a.necta_index_number, a.necta_exam_year, ey.start_date as entry_start, i.start_month,
                   ph.data as photo
            from health_covers c join students s on s.id = c.student_id join programmes p on p.id = s.programme_id
            left join departments d on d.id = p.department_id left join faculties f on f.id = d.faculty_id
            left join applicants a on a.id = s.applicant_id
            join academic_years ey on ey.id = s.entry_academic_year_id join intakes i on i.id = s.intake_id
            left join student_photos ph on ph.student_id = s.id""";

    private static final String BATCH_SQL = """
            select b.id, b.kind, y.code, b.batch_ref, b.status, b.students, b.registered, b.rejected, b.submitted_by_name, b.submitted_at, b.checked_at,
                   b.intake_code
            from nhif_registration_batches b join academic_years y on y.id = b.academic_year_id""";

    private static final String PERSON_SQL = """
            select s.id, s.registration_no, concat_ws(' ', s.first_name, s.middle_name, s.surname), s.surname, s.phone, s.email from students s""";

    private record Person(Long id, String registrationNo, String name, String surname, String phone, String email) {
    }

    private Long newBatch(Long yid, String kind, String ref, int students, String intake) {
        return jdbc.queryForObject("""
                insert into nhif_registration_batches (tenant_id, academic_year_id, kind, batch_ref, students, intake_code, submitted_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?) returning id""", Long.class, yid, kind, ref, students, intake, CurrentUser.name());
    }

    private void finishBatch(Long batchId, int registered, int rejected, List<NhifClient.Failure> failures) {
        String text = failures.isEmpty() ? null : String.join("\n", failures.stream()
                .map(f -> (f.identifier() == null ? "#" + f.index() : f.identifier()) + ": " + f.reason()).toList());
        jdbc.update("update nhif_registration_batches set registered = ?, rejected = ?, status = 'COMPLETED', failures = ?, checked_at = now() where id = ?",
                registered, rejected, text, batchId);
    }

    private String nin(Map<String, Object> r) {
        String enc = (String) r.get("nin_encrypted");
        if (enc == null) return null;
        String n = cipher.decrypt(enc).replaceAll("\\D", "");
        return n.length() == 20 ? n : null;
    }

    /** The intake the student joined in, as NHIF's YYYYMM (the entry year, and the month the intake starts). */
    private static String intake(Map<String, Object> r) {
        LocalDate start = ((java.sql.Date) r.get("entry_start")).toLocalDate();
        int month = r.get("start_month") == null ? start.getMonthValue() : ((Number) r.get("start_month")).intValue();
        int year = month < start.getMonthValue() ? start.getYear() + 1 : start.getYear();
        return NhifClient.intakeCode(year, month);
    }

    /** 255712345678 → 0712345678, as NHIF writes mobile numbers. */
    static String localPhone(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("\\D", "");
        if (d.startsWith("255") && d.length() == 12) return "0" + d.substring(3);
        return d.length() == 9 ? "0" + d : d;
    }

    private static int indexOf(List<NhifClient.Registration> list, String identifier) {
        if (identifier == null) return -1;
        for (int i = 0; i < list.size(); i++) {
            var r = list.get(i);
            if (identifier.equalsIgnoreCase(r.formFourIndexNo()) || identifier.equalsIgnoreCase(r.admissionNo())) return i;
        }
        return -1;
    }

    private StudentCover studentCover(Person p, Map<String, Object> y, String message) {
        var s = settings.get();
        Map<String, Object> year = y != null ? y : year(null);
        Cover c = cover(p.id(), (Long) year.get("id"));
        if (c != null && c.invoice() != null && !"PAID".equals(c.invoice().status()) && c.invoice().balance().signum() > 0) {
            var cn = billing.currentControlNumber(c.invoice().id());
            InvoiceInfo inv = c.invoice();
            c = new Cover(c.id(), c.studentId(), c.registrationNo(), c.studentName(), c.programmeCode(), c.yearOfStudy(), c.academicYear(), c.route(),
                    c.status(), c.cardNo(), c.holderName(), c.scheme(), c.validTo(), c.reason(), c.checkedAt(), c.batchRef(),
                    new InvoiceInfo(inv.id(), inv.invoiceNo(), inv.amount(), inv.balance(), inv.status(), cn == null ? null : cn.getControlNo()), c.nhifBill(),
                    c.category());
        }
        return new StudentCover(p.id(), s.nhifEnabled(), s.nhifRequired(), s.nhifContribution(), (Long) year.get("id"), (String) year.get("code"), c,
                c != null && List.of("ACTIVE", "PAID", "SUBMITTED").contains(c.status()), message);
    }

    private Cover cover(Long studentId, Long yearId) {
        Map<String, Object> y = year(yearId);
        List<Cover> l = jdbc.query(COVER_SQL + " where s.id = ?", (rs, i) -> cover(rs, (String) y.get("code")), today(), y.get("id"), studentId);
        return l.isEmpty() || l.get(0).id() == null ? null : l.get(0);
    }

    private Cover cover(java.sql.ResultSet rs, String year) throws java.sql.SQLException {
        Long id = rs.getObject("id") == null ? null : rs.getLong("id");
        InvoiceInfo inv = rs.getObject("invoice_id") == null ? null : new InvoiceInfo(rs.getLong("invoice_id"), rs.getString("invoice_no"),
                rs.getBigDecimal("total_amount"), rs.getBigDecimal("balance"), rs.getString("invoice_status"), null);
        NhifBill bill = rs.getString("nhif_control_no") == null ? null : new NhifBill(rs.getString("nhif_control_no"), rs.getString("nhif_invoice_no"),
                rs.getBigDecimal("nhif_amount"), rs.getObject("nhif_paid_on", LocalDate.class));
        return new Cover(id, rs.getLong("student_id"), rs.getString("registration_no"), rs.getString("name"), rs.getString("code"), rs.getInt("year_of_study"),
                year, rs.getString("route"), rs.getString("shown"), rs.getString("card_no"), rs.getString("holder_name"), rs.getString("scheme"),
                rs.getObject("valid_to", LocalDate.class), rs.getString("reason"), rs.getObject("checked_at", OffsetDateTime.class), rs.getString("batch_ref"), inv,
                bill, rs.getObject("category") == null ? null : rs.getInt("category"));
    }

    private Long upsert(Long studentId, Long yid, String route, String status, String card, String holder, String scheme, LocalDate validTo, String reason) {
        return jdbc.queryForObject("""
                insert into health_covers (tenant_id, student_id, academic_year_id, route, status, card_no, holder_name, scheme, valid_to, reason, recorded_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (student_id, academic_year_id) do update set route = excluded.route, status = excluded.status, card_no = excluded.card_no,
                  holder_name = excluded.holder_name, scheme = excluded.scheme, valid_to = excluded.valid_to, invoice_id = null,
                  reason = excluded.reason, batch_id = null, application_batch_id = null, correlation_id = null, category = null, nhif_control_no = null,
                  nhif_invoice_no = null, nhif_amount = null, nhif_paid_on = null, checked_at = null, recorded_by_name = excluded.recorded_by_name, updated_at = now()
                returning id""", Long.class, studentId, yid, route, status, card, holder, scheme, validTo, reason, CurrentUser.name());
    }

    private void reject(Long coverId, String reason) {
        jdbc.update("update health_covers set status = 'REJECTED', reason = ?, checked_at = now(), updated_at = now() where id = ?",
                reason == null ? "NHIF did not give a reason" : reason.length() > 300 ? reason.substring(0, 300) : reason, coverId);
    }

    private Batch batch(Long id) {
        List<Batch> l = jdbc.query(BATCH_SQL + " where b.id = ?", this::batchRow, id);
        if (l.isEmpty()) throw new NotFoundException("NHIF batch", id);
        return l.get(0);
    }

    private Batch batchRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Batch(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
                rs.getString(9), rs.getObject(10, OffsetDateTime.class), rs.getObject(11, OffsetDateTime.class), rs.getString(12));
    }

    /** The given academic year, or the current one. */
    private Map<String, Object> year(Long id) {
        List<Map<String, Object>> l = id != null ? jdbc.queryForList("select id, code from academic_years where id = ?", id)
                : jdbc.queryForList("select id, code from academic_years order by (current_date between start_date and end_date) desc, start_date desc limit 1");
        if (l.isEmpty()) throw new NotFoundException("Academic year", id);
        Map<String, Object> m = new HashMap<>(l.get(0));
        m.put("id", ((Number) m.get("id")).longValue());
        return m;
    }

    private static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** NHIF writes names in capitals, often with the middle name: the student's surname must be one of the names on the card. */
    static boolean sameSurname(String fullName, String surname) {
        if (surname == null || surname.isBlank()) return true;
        String want = surname.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        for (String part : fullName.toUpperCase(Locale.ROOT).split("[^A-Z']+")) {
            if (part.replaceAll("[^A-Z]", "").equals(want)) return true;
        }
        return false;
    }

    private Person person(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Person(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6));
    }

    private Person me() {
        List<Person> l = jdbc.query(PERSON_SQL + " where s.user_id = ?", (rs, i) -> person(rs), CurrentUser.requireId());
        if (l.isEmpty()) throw new NotFoundException("No student record is linked to this account");
        return l.get(0);
    }

    private Person student(Long id) {
        List<Person> l = jdbc.query(PERSON_SQL + " where s.id = ?", (rs, i) -> person(rs), id);
        if (l.isEmpty()) throw new NotFoundException("Student", id);
        return l.get(0);
    }
}
