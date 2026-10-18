package tz.co.msingi.cims.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.Csv;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.domain.Invoice;
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
 * Students' health insurance with NHIF. Every student needs cover for the academic year, in one of two ways:
 * <ul>
 *   <li><b>Own card</b>: a student already covered (e.g. as a parent's dependant) gives the NHIF card number; CIMS checks it with
 *       NHIF (active, not expired, held by this student) and records the result.</li>
 *   <li><b>Through the institution</b>: a student without cover is billed NHIF's student contribution; once it is paid (or waived),
 *       the Registrar sends the student to NHIF with others in a batch, and NHIF's card number comes back.</li>
 * </ul>
 * The Bursar can bill every current student still without cover at once. When the institution requires it, semester registration
 * waits until the student is covered (or the contribution is paid and registration with NHIF is under way).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthCoverService {

    public static final String PURPOSE = "NHIF_CONTRIBUTION";
    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    /** Statuses as shown: the stored ones, EXPIRED for an active card past its date, NONE for no cover recorded. */
    static final List<String> STATUSES = List.of("ACTIVE", "EXPIRED", "INVALID", "AWAITING_PAYMENT", "READY", "SUBMITTED", "REJECTED", "NONE");

    private final JdbcTemplate jdbc;
    private final NhifClient nhif;
    private final IntegrationSettingsService settings;
    private final BillingService billing;
    private final SecretCipher cipher;
    private final Messaging messaging;
    private final AuditService audit;

    public record InvoiceInfo(Long id, String invoiceNo, BigDecimal amount, BigDecimal balance, String status, String controlNo) {
    }

    public record Cover(Long id, Long studentId, String registrationNo, String studentName, String programmeCode, int yearOfStudy,
                        String academicYear, String route, String status, String cardNo, String holderName, String scheme, LocalDate validTo,
                        String reason, OffsetDateTime checkedAt, String batchRef, InvoiceInfo invoice) {
    }

    /** A student's cover for the year, with what the student can do about it. */
    public record StudentCover(Long studentId, boolean enabled, boolean required, BigDecimal contribution, Long academicYearId, String academicYear,
                               Cover cover, boolean covered, String message) {
    }

    public record ProgrammeRow(String code, String name, long students, long active, long underWay, long problems, long none) {
    }

    public record Batch(Long id, String academicYear, String batchRef, String status, int students, int registered, int rejected,
                        String submittedByName, OffsetDateTime submittedAt, OffsetDateTime checkedAt) {
    }

    public record Overview(boolean enabled, boolean required, BigDecimal contribution, String institutionCode, String mode, Long academicYearId,
                           String academicYear, long students, Map<String, Long> counts, long expiringSoon, List<ProgrammeRow> programmes,
                           List<Batch> batches) {
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
     * health cover; otherwise when the cover is active, or the contribution is paid and registration with NHIF is under way.
     */
    @Transactional(readOnly = true)
    public boolean coveredForRegistration(Long studentId, Long academicYearId) {
        var s = settings.get();
        if (!s.nhifEnabled() || !s.nhifRequired()) return true;
        Integer n = jdbc.queryForObject("""
                select count(*) from health_covers c left join invoices i on i.id = c.invoice_id
                where c.student_id = ? and c.academic_year_id = ?
                  and (c.status in ('READY', 'SUBMITTED') or (c.status = 'ACTIVE' and (c.valid_to is null or c.valid_to >= ?))
                       or (c.status = 'AWAITING_PAYMENT' and i.status in ('PAID', 'CREDITED')))""", Integer.class, studentId, academicYearId, today());
        return n != null && n > 0;
    }

    private StudentCover recordCard(Person p, String raw) {
        String code = settings.nhifCode();
        String card = NhifClient.normalise(raw);
        if (card == null) throw new BusinessRuleException("INVALID_CARD", "Enter the NHIF card number as printed on the card (letters and digits).");
        Map<String, Object> y = year(null);
        Long yid = (Long) y.get("id");
        Integer other = jdbc.queryForObject("select count(*) from health_covers where card_no = ? and student_id <> ? and academic_year_id = ?",
                Integer.class, card, p.id(), yid);
        if (other != null && other > 0) {
            throw new BusinessRuleException("CARD_IN_USE", "This NHIF card is already recorded for another student. Contact the Registrar if it is yours.");
        }
        refresh();
        Cover existing = cover(p.id(), yid);
        if (existing != null && "INSTITUTION".equals(existing.route())) {
            if (List.of("READY", "SUBMITTED", "ACTIVE").contains(existing.status())) {
                throw new BusinessRuleException("REGISTRATION_UNDER_WAY", "ACTIVE".equals(existing.status())
                        ? "You are already covered through the institution (card " + existing.cardNo() + ")."
                        : "Your NHIF contribution is paid and your registration with NHIF is under way. Ask the Registrar if you also have your own card.");
            }
            if (existing.invoice() != null) billing.cancelIfUnpaid(existing.invoice().id(), "Student gave their own NHIF card instead");
        }
        NhifClient.Card c = nhif.verifyCard(card);
        String status;
        String reason = null;
        LocalDate today = LocalDate.now(ZONE);
        if ("NOT_FOUND".equals(c.status())) {
            status = "INVALID";
            reason = "NHIF has no member with this card number. Check the number on the card.";
        } else if (!"ACTIVE".equals(c.status()) || (c.validTo() != null && c.validTo().isBefore(today))) {
            status = "INVALID";
            reason = "NHIF says the cover on this card is not active" + (c.remarks() == null ? "" : " (" + c.remarks() + ")")
                    + ". Renew it, or choose to be registered through the institution.";
        } else if (c.fullName() != null && !sameSurname(c.fullName(), p.surname())) {
            status = "INVALID";
            reason = "NHIF holds this card for " + c.fullName() + ". Give your own card number.";
        } else {
            status = "ACTIVE";
        }
        Long id = upsert(p.id(), yid, "OWN_CARD", status, card, c.fullName(), c.scheme(), c.validTo(), null, reason);
        jdbc.update("update health_covers set checked_at = now() where id = ?", id);
        audit.record("NHIF_CARD_CHECKED", "Student", p.id(), Map.of("card", "…" + card.substring(Math.max(0, card.length() - 4)), "status", status));
        log.debug("NHIF card for student {} at {}: {}", p.id(), code, status);
        return studentCover(p, y, "ACTIVE".equals(status) ? "NHIF confirmed the card: you are covered" + (c.validTo() == null ? "." : " until "
                + DAY.format(c.validTo()) + ".") : reason);
    }

    private StudentCover chooseInstitution(Person p) {
        settings.nhifCode();
        Map<String, Object> y = year(null);
        Long yid = (Long) y.get("id");
        refresh();
        Cover existing = cover(p.id(), yid);
        if (existing != null && "INSTITUTION".equals(existing.route())) {
            if ("REJECTED".equals(existing.status())) {
                // sent again: what was paid still counts, so only an unpaid contribution waits for payment
                boolean paid = existing.invoice() == null || List.of("PAID", "CREDITED").contains(existing.invoice().status());
                jdbc.update("update health_covers set status = ?, reason = null, batch_id = null, updated_at = now() where id = ?",
                        paid ? "READY" : "AWAITING_PAYMENT", existing.id());
                audit.record("NHIF_REGISTRATION_RETRIED", "Student", p.id(), Map.of("year", y.get("code")));
                return studentCover(p, y, "The institution will send you to NHIF again.");
            }
            return studentCover(p, y, null);
        }
        if (existing != null && "OWN_CARD".equals(existing.route()) && covered(existing)) {
            throw new BusinessRuleException("ALREADY_COVERED", "You are already covered by NHIF card " + existing.cardNo() + ".");
        }
        BigDecimal amount = settings.get().nhifContribution();
        Long id = upsert(p.id(), yid, "INSTITUTION", amount.signum() > 0 ? "AWAITING_PAYMENT" : "READY", null, null, null, null, null, null);
        if (amount.signum() > 0) bill(p, id, yid, (String) y.get("code"), amount);
        audit.record("NHIF_INSTITUTION_CHOSEN", "Student", p.id(), Map.of("year", y.get("code"), "amount", amount));
        return studentCover(p, y, amount.signum() > 0
                ? "Pay the NHIF contribution with the payment number shown. The institution then registers you with NHIF."
                : "The institution will register you with NHIF.");
    }

    private void bill(Person p, Long coverId, Long yid, String yearCode, BigDecimal amount) {
        String what = "NHIF student health insurance, " + yearCode;
        Invoice inv = billing.createInvoice(new BillingService.NewInvoice(Invoice.PayerType.STUDENT, p.id(), p.name(), p.phone(), p.email(), PURPOSE,
                coverId, what, List.of(new BillingService.NewInvoice.Line("NHIF", what, amount)), yid, null));
        jdbc.update("update health_covers set invoice_id = ? where id = ?", inv.getId(), coverId);
        billing.ensureControlNumber(inv.getId());
    }

    // ------------------------------------------------------------------ the office

    @Transactional
    public Overview overview(Long academicYearId) {
        var s = settings.get();
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        refresh();
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
            switch (st) {
                case "ACTIVE" -> a[1] += n;
                case "AWAITING_PAYMENT", "READY", "SUBMITTED" -> a[2] += n;
                case "NONE" -> a[4] += n;
                default -> a[3] += n;
            }
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
        return new Overview(s.nhifEnabled(), s.nhifRequired(), s.nhifContribution(), code, settings.view().modes().get("NHIF"), yid,
                (String) y.get("code"), students, counts, soon == null ? 0 : soon, rows, batches);
    }

    /** Current students with their cover for the year; {@code status} as shown (NONE for no cover), at most 1,000 rows. */
    @Transactional
    public List<Cover> list(Long academicYearId, String status, Long programmeId, String q) {
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        refresh();
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
            where.append(" and (upper(s.registration_no) like ? or upper(concat_ws(' ', s.first_name, s.middle_name, s.surname)) like ? or c.card_no like ?)");
            String like = "%" + q.trim().toUpperCase() + "%";
            args.add(like);
            args.add(like);
            args.add(like.replaceAll("[^A-Z0-9%]", ""));
        }
        return jdbc.query(COVER_SQL + where + " order by p.code, s.year_of_study, s.surname, s.first_name limit 1000", (rs, i) -> cover(rs, (String) y.get("code")),
                args.toArray());
    }

    /** Checks every own card recorded for the year with NHIF again (cover ends, cards are renewed). */
    @Transactional
    public Result recheck(Long academicYearId) {
        settings.nhifCode();
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.id, c.card_no, c.status, s.surname, s.registration_no from health_covers c join students s on s.id = c.student_id
                where c.academic_year_id = ? and c.route = 'OWN_CARD' and c.card_no is not null and s.status in ('ADMITTED', 'ACTIVE') order by c.id""", yid);
        int checked = 0, changed = 0;
        List<String> problems = new ArrayList<>();
        LocalDate today = LocalDate.now(ZONE);
        for (Map<String, Object> r : rows) {
            NhifClient.Card c;
            try {
                c = nhif.verifyCard((String) r.get("card_no"));
            } catch (ExternalSystemUnavailableException e) {
                problems.add("NHIF stopped answering after " + checked + " card(s): " + e.getMessage());
                break;
            }
            checked++;
            boolean ok = "ACTIVE".equals(c.status()) && (c.validTo() == null || !c.validTo().isBefore(today))
                    && (c.fullName() == null || sameSurname(c.fullName(), (String) r.get("surname")));
            String status = ok ? "ACTIVE" : "INVALID";
            String reason = ok ? null : "NOT_FOUND".equals(c.status()) ? "NHIF has no member with this card number"
                    : !"ACTIVE".equals(c.status()) || (c.validTo() != null && c.validTo().isBefore(today)) ? "NHIF says the cover is not active"
                    : "NHIF holds this card for " + c.fullName();
            if (!status.equals(r.get("status"))) {
                changed++;
                if (!ok) problems.add(r.get("registration_no") + ": " + reason);
            }
            jdbc.update("""
                    update health_covers set status = ?, holder_name = ?, scheme = ?, valid_to = ?, reason = ?, checked_at = now(), updated_at = now()
                    where id = ?""", status, c.fullName(), c.scheme(), c.validTo(), reason, r.get("id"));
        }
        audit.record("NHIF_CARDS_RECHECKED", "HealthCover", null, Map.of("year", y.get("code"), "checked", checked, "changed", changed));
        return new Result(checked + " card(s) checked with NHIF, " + changed + " changed", checked, problems);
    }

    /** Bills NHIF's contribution to every current student with no cover recorded for the year (they are registered through the institution). */
    @Transactional
    public Result billUncovered(Long academicYearId) {
        settings.nhifCode();
        BigDecimal amount = settings.get().nhifContribution();
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        List<Person> people = jdbc.query(PERSON_SQL + " where s.status in ('ADMITTED', 'ACTIVE')"
                + " and not exists (select 1 from health_covers c where c.student_id = s.id and c.academic_year_id = ?) order by s.registration_no",
                (rs, i) -> person(rs), yid);
        for (Person p : people) {
            Long id = upsert(p.id(), yid, "INSTITUTION", amount.signum() > 0 ? "AWAITING_PAYMENT" : "READY", null, null, null, null, null, null);
            if (amount.signum() > 0) bill(p, id, yid, (String) y.get("code"), amount);
        }
        audit.record("NHIF_CONTRIBUTIONS_BILLED", "HealthCover", null, Map.of("year", y.get("code"), "students", people.size(), "amount", amount));
        return new Result(people.isEmpty() ? "Every current student already has cover recorded for " + y.get("code")
                : people.size() + " student(s) billed the NHIF contribution" + (amount.signum() > 0 ? " of TZS " + String.format("%,.0f", amount) : ""),
                people.size(), List.of());
    }

    /** Sends every student whose contribution is paid (or waived) to NHIF in one batch, then asks NHIF how it went. */
    @Transactional
    public Batch sendToNhif(Long academicYearId) {
        String code = settings.nhifCode();
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        refresh();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.id as cover_id, s.registration_no, s.first_name, s.middle_name, s.surname, s.sex, s.date_of_birth, s.nin_encrypted, s.phone,
                       s.email, p.name as programme, s.year_of_study
                from health_covers c join students s on s.id = c.student_id join programmes p on p.id = s.programme_id
                where c.academic_year_id = ? and c.status = 'READY' order by s.registration_no""", yid);
        if (rows.isEmpty()) {
            throw new BusinessRuleException("NOTHING_TO_SEND", "No student is waiting to be registered with NHIF for " + y.get("code")
                    + ": students are sent once their contribution is paid.");
        }
        List<NhifClient.Student> list = new ArrayList<>();
        Map<String, Long> coverByReg = new HashMap<>();
        for (Map<String, Object> r : rows) {
            String enc = (String) r.get("nin_encrypted");
            java.sql.Date dob = (java.sql.Date) r.get("date_of_birth");
            list.add(new NhifClient.Student((String) r.get("registration_no"), (String) r.get("first_name"), (String) r.get("middle_name"),
                    (String) r.get("surname"), (String) r.get("sex"), dob == null ? null : dob.toLocalDate(), enc == null ? null : cipher.decrypt(enc),
                    (String) r.get("phone"), (String) r.get("email"), (String) r.get("programme"), ((Number) r.get("year_of_study")).intValue()));
            coverByReg.put(((String) r.get("registration_no")).toUpperCase(), ((Number) r.get("cover_id")).longValue());
        }
        NhifClient.Submission sub = nhif.registerStudents(code, (String) y.get("code"), list);
        if (sub.batchRef() == null) throw new ExternalSystemUnavailableException("NHIF", "NHIF accepted the batch but gave no reference. Please try again.", null);
        Long batchId = jdbc.queryForObject("""
                insert into nhif_registration_batches (tenant_id, academic_year_id, batch_ref, students, rejected, submitted_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?) returning id""", Long.class, yid, sub.batchRef(), list.size(), sub.rejected().size(), CurrentUser.name());
        for (Long cid : coverByReg.values()) {
            jdbc.update("update health_covers set status = 'SUBMITTED', batch_id = ?, reason = null, updated_at = now() where id = ?", batchId, cid);
        }
        for (NhifClient.Rejected r : sub.rejected()) {
            Long cid = r.registrationNo() == null ? null : coverByReg.get(r.registrationNo().toUpperCase());
            if (cid != null) reject(cid, r.reason());
        }
        audit.record("NHIF_REGISTRATION_SENT", "NhifRegistrationBatch", batchId, Map.of("year", y.get("code"), "students", list.size(), "ref", sub.batchRef()));
        try {
            return check(batchId);
        } catch (ExternalSystemUnavailableException e) {
            log.warn("NHIF status of {} not available yet: {}", sub.batchRef(), e.getMessage());
            return batch(batchId);
        }
    }

    /** Asks NHIF how a batch went: issued cards make the cover active, refusals are recorded with NHIF's reason. */
    @Transactional
    public Batch check(Long batchId) {
        String code = settings.nhifCode();
        Batch b = batch(batchId);
        NhifClient.Status st = nhif.registrationStatus(code, b.batchRef());
        Map<String, Map<String, Object>> covers = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                select c.id, c.status, s.registration_no, s.phone, upper(concat_ws(' ', s.first_name, s.middle_name, s.surname)) as name
                from health_covers c join students s on s.id = c.student_id where c.batch_id = ?""", batchId)) {
            covers.put(((String) r.get("registration_no")).toUpperCase(), r);
        }
        for (NhifClient.Item i : st.items()) {
            Map<String, Object> c = i.registrationNo() == null ? null : covers.get(i.registrationNo().toUpperCase());
            if (c == null || !"SUBMITTED".equals(c.get("status"))) continue;   // unknown, or already decided
            Long cid = ((Number) c.get("id")).longValue();
            if ("REGISTERED".equals(i.status()) && i.cardNo() != null) {
                jdbc.update("""
                        update health_covers set status = 'ACTIVE', card_no = ?, valid_to = ?, holder_name = ?, scheme = 'Student package', reason = null,
                          checked_at = now(), updated_at = now() where id = ?""", NhifClient.normalise(i.cardNo()), i.validTo(), c.get("name"), cid);
                messaging.event("NHIF_CARD_ISSUED", (String) c.get("phone"), Map.of("cardNo", i.cardNo(),
                        "validTo", i.validTo() == null ? "the end of the year" : DAY.format(i.validTo())));
            } else if ("REJECTED".equals(i.status())) {
                reject(cid, i.reason());
                messaging.event("NHIF_REGISTRATION_REJECTED", (String) c.get("phone"), Map.of("reason", i.reason() == null ? "no reason given" : i.reason()));
            }
        }
        jdbc.update("""
                update nhif_registration_batches b set
                  registered = (select count(*) from health_covers c where c.batch_id = b.id and c.status = 'ACTIVE'),
                  rejected = (select count(*) from health_covers c where c.batch_id = b.id and c.status = 'REJECTED'),
                  status = case when not exists (select 1 from health_covers c where c.batch_id = b.id and c.status = 'SUBMITTED') then 'COMPLETED' else 'SUBMITTED' end,
                  checked_at = now()
                where b.id = ?""", batchId);
        Batch after = batch(batchId);
        audit.record("NHIF_REGISTRATION_CHECKED", "NhifRegistrationBatch", batchId, Map.of("registered", after.registered(), "rejected", after.rejected()));
        return after;
    }

    @Transactional(readOnly = true)
    public List<Cover> batchItems(Long batchId) {
        Batch b = batch(batchId);
        Long yid = jdbc.queryForObject("select academic_year_id from nhif_registration_batches where id = ?", Long.class, batchId);
        return jdbc.query(COVER_SQL + " where c.batch_id = ? order by s.registration_no", (rs, i) -> cover(rs, b.academicYear()), today(), yid, batchId);
    }

    /** Students waiting to be registered (contribution paid) and those sent, in a template for NHIF's portal when the API is not used. */
    @Transactional
    public String registrationFile(Long academicYearId) {
        Map<String, Object> y = year(academicYearId);
        Long yid = (Long) y.get("id");
        refresh();
        String code = settings.get().nhifCode() != null ? settings.get().nhifCode() : settings.institutionCode();
        StringBuilder sb = new StringBuilder(Csv.line("S/N", "Institution code", "Academic year", "Registration no.", "Surname", "First name", "Middle name",
                "Sex", "Date of birth", "NIN", "Phone", "Email", "Programme", "Year of study", "Contribution receipt", "Status"));
        int n = 0;
        for (Map<String, Object> r : jdbc.queryForList("""
                select s.registration_no, s.surname, s.first_name, s.middle_name, s.sex, s.date_of_birth, s.nin_encrypted, s.phone, s.email,
                       p.name as programme, s.year_of_study, i.invoice_no, c.status
                from health_covers c join students s on s.id = c.student_id join programmes p on p.id = s.programme_id
                left join invoices i on i.id = c.invoice_id
                where c.academic_year_id = ? and c.status in ('READY', 'SUBMITTED') order by s.registration_no""", yid)) {
            String enc = (String) r.get("nin_encrypted");
            sb.append(Csv.line(++n, code, y.get("code"), r.get("registration_no"), r.get("surname"), r.get("first_name"), r.get("middle_name"), r.get("sex"),
                    r.get("date_of_birth"), enc == null ? "" : cipher.decrypt(enc), r.get("phone"), r.get("email"), r.get("programme"), r.get("year_of_study"),
                    r.get("invoice_no"), r.get("status")));
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
             as shown, c.card_no, c.holder_name, c.scheme, c.valid_to, c.reason, c.checked_at, b.batch_ref,
                   i.id as invoice_id, i.invoice_no, i.total_amount, i.total_amount - i.paid_amount as balance, i.status as invoice_status
            from students s join programmes p on p.id = s.programme_id
            left join health_covers c on c.student_id = s.id and c.academic_year_id = ?
            left join nhif_registration_batches b on b.id = c.batch_id
            left join invoices i on i.id = c.invoice_id""";

    private static final String BATCH_SQL = """
            select b.id, y.code, b.batch_ref, b.status, b.students, b.registered, b.rejected, b.submitted_by_name, b.submitted_at, b.checked_at
            from nhif_registration_batches b join academic_years y on y.id = b.academic_year_id""";

    private static final String PERSON_SQL = """
            select s.id, s.registration_no, concat_ws(' ', s.first_name, s.middle_name, s.surname), s.surname, s.phone, s.email from students s""";

    private record Person(Long id, String registrationNo, String name, String surname, String phone, String email) {
    }

    /** Contributions paid, or written off by a waiver, make the student ready to be sent to NHIF. */
    private void refresh() {
        jdbc.update("""
                update health_covers c set status = 'READY', updated_at = now() from invoices i
                where c.invoice_id = i.id and c.status = 'AWAITING_PAYMENT' and i.status in ('PAID', 'CREDITED')""");
    }

    private static boolean covered(Cover c) {
        return c != null && List.of("ACTIVE", "READY", "SUBMITTED").contains(c.status());
    }

    private StudentCover studentCover(Person p, Map<String, Object> y, String message) {
        var s = settings.get();
        Map<String, Object> year = y != null ? y : year(null);
        refresh();
        Cover c = cover(p.id(), (Long) year.get("id"));
        if (c != null && c.invoice() != null && !"PAID".equals(c.invoice().status()) && c.invoice().balance().signum() > 0) {
            var cn = billing.currentControlNumber(c.invoice().id());
            InvoiceInfo inv = c.invoice();
            c = new Cover(c.id(), c.studentId(), c.registrationNo(), c.studentName(), c.programmeCode(), c.yearOfStudy(), c.academicYear(), c.route(),
                    c.status(), c.cardNo(), c.holderName(), c.scheme(), c.validTo(), c.reason(), c.checkedAt(), c.batchRef(),
                    new InvoiceInfo(inv.id(), inv.invoiceNo(), inv.amount(), inv.balance(), inv.status(), cn == null ? null : cn.getControlNo()));
        }
        return new StudentCover(p.id(), s.nhifEnabled(), s.nhifRequired(), s.nhifContribution(), (Long) year.get("id"), (String) year.get("code"), c,
                covered(c), message);
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
        return new Cover(id, rs.getLong("student_id"), rs.getString("registration_no"), rs.getString("name"), rs.getString("code"), rs.getInt("year_of_study"),
                year, rs.getString("route"), rs.getString("shown"), rs.getString("card_no"), rs.getString("holder_name"), rs.getString("scheme"),
                rs.getObject("valid_to", LocalDate.class), rs.getString("reason"), rs.getObject("checked_at", OffsetDateTime.class), rs.getString("batch_ref"), inv);
    }

    private Long upsert(Long studentId, Long yid, String route, String status, String card, String holder, String scheme, LocalDate validTo,
                        Long invoiceId, String reason) {
        return jdbc.queryForObject("""
                insert into health_covers (tenant_id, student_id, academic_year_id, route, status, card_no, holder_name, scheme, valid_to, invoice_id, reason,
                  recorded_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (student_id, academic_year_id) do update set route = excluded.route, status = excluded.status, card_no = excluded.card_no,
                  holder_name = excluded.holder_name, scheme = excluded.scheme, valid_to = excluded.valid_to, invoice_id = excluded.invoice_id,
                  reason = excluded.reason, batch_id = null, checked_at = null, recorded_by_name = excluded.recorded_by_name, updated_at = now()
                returning id""", Long.class, studentId, yid, route, status, card, holder, scheme, validTo, invoiceId, reason, CurrentUser.name());
    }

    private void reject(Long coverId, String reason) {
        jdbc.update("update health_covers set status = 'REJECTED', reason = ?, checked_at = now(), updated_at = now() where id = ?",
                reason == null ? "NHIF did not give a reason" : reason.length() > 300 ? reason.substring(0, 300) : reason, coverId);
    }

    private Batch batch(Long id) {
        List<Batch> l = jdbc.query(BATCH_SQL + " where b.id = ?", this::batchRow, id);
        if (l.isEmpty()) throw new NotFoundException("NHIF registration batch", id);
        return l.get(0);
    }

    private Batch batchRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Batch(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getString(8),
                rs.getObject(9, OffsetDateTime.class), rs.getObject(10, OffsetDateTime.class));
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
