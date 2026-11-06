package tz.co.msingi.cims.health;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.integration.log.IntegrationCallRecorder;
import tz.co.msingi.cims.platform.security.mfa.SecretCipher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NHIF's callbacks to the institution (Student Registration API, sections 6–10), each answered with {@code statusCode} and
 * {@code message} as NHIF expects. NHIF uses one callback address per agent, so the kind is read from the payload when the
 * address does not say it:
 * <ul>
 *   <li>registration — {@code formFourIndexNo}, {@code statusCode}, {@code remarks};</li>
 *   <li>card application — {@code batchNo}, {@code controlNo}, {@code invoiceAmount} (and the student's identifiers);</li>
 *   <li>payment — {@code controlNo}, {@code status}, {@code paymentDate};</li>
 *   <li>card issued — {@code controlNo}, {@code cardno} (and the batch and identifiers);</li>
 *   <li>change request — {@code cardNo}/{@code formFourIndex}, {@code academicYear}, {@code status}, {@code remarks}.</li>
 * </ul>
 * Every callback is kept as received, with what CIMS did with it. A callback repeated by NHIF changes nothing the second time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NhifCallbackService {

    public enum Kind { REGISTRATION, CARD_APPLICATION, PAYMENT, CARD_ISSUED, CHANGE_REQUEST, UNKNOWN }

    /** NHIF's CallbackAcknowledgementDto. */
    public record Ack(int statusCode, String message) {
    }

    private record Outcome(String result, String reference, String note) {
    }

    private final JdbcTemplate jdbc;
    private final HealthCoverService covers;
    private final SecretCipher cipher;
    private final IntegrationCallRecorder recorder;
    private final Environment env;

    @Transactional
    public Ack handle(Kind declared, JsonNode body) {
        Kind kind = declared != null && declared != Kind.UNKNOWN ? declared : detect(body);
        Outcome o;
        try {
            o = switch (kind) {
                case REGISTRATION -> registration(body);
                case CARD_APPLICATION -> cardApplication(body);
                case PAYMENT -> payment(body);
                case CARD_ISSUED -> cardIssued(body);
                case CHANGE_REQUEST -> changeRequest(body);
                case UNKNOWN -> new Outcome("UNMATCHED", null, "Not a callback CIMS knows (no formFourIndexNo, batchNo, controlNo or cardNo)");
            };
        } catch (RuntimeException e) {
            log.warn("NHIF {} callback could not be applied: {}", kind, e.getMessage());
            o = new Outcome("UNMATCHED", null, "Could not be applied: " + e.getMessage());
        }
        String payload = body == null ? "" : body.toString();
        jdbc.update("insert into nhif_callbacks (tenant_id, kind, reference, payload, outcome, note) values (cims_current_tenant(), ?, ?, ?, ?, ?)",
                kind.name(), cut(o.reference(), 80), payload.length() > 4000 ? payload.substring(0, 4000) : payload, o.result(), cut(o.note(), 300));
        recorder.record(IntegrationCallRecorder.ExternalSystem.NHIF, "callback " + kind.name().toLowerCase(Locale.ROOT), true,
                "live".equalsIgnoreCase(env.getProperty("cims.nhif.mode", "simulated")),
                "UNMATCHED".equals(o.result()) ? IntegrationCallRecorder.Outcome.FAILURE : IntegrationCallRecorder.Outcome.SUCCESS, null, o.reference(), o.note());
        return "UNMATCHED".equals(o.result()) ? new Ack(400, o.note()) : new Ack(200, o.note());
    }

    static Kind detect(JsonNode b) {
        if (b == null || !b.isObject()) return Kind.UNKNOWN;
        if (has(b, "cardno") && has(b, "controlNo")) return Kind.CARD_ISSUED;
        if (b.has("invoiceAmount")) return Kind.CARD_APPLICATION;
        if (has(b, "paymentDate") || (has(b, "controlNo") && has(b, "status"))) return Kind.PAYMENT;
        if (has(b, "formFourIndexNo") && b.has("statusCode")) return Kind.REGISTRATION;
        if (has(b, "status") && (has(b, "formFourIndex") || has(b, "cardNo"))) return Kind.CHANGE_REQUEST;
        return Kind.UNKNOWN;
    }

    // ------------------------------------------------------------------ each kind

    private Outcome registration(JsonNode b) {
        String index = text(b, "formFourIndexNo");
        Long id = byIndex(index, List.of("READY", "REGISTERED"));
        if (id == null) return new Outcome("UNMATCHED", index, "Unrecognized FormFourIndexNo: no student waiting for NHIF registration");
        String code = text(b, "statusCode");
        String remarks = text(b, "remarks");
        if ("200".equals(code)) {
            int n = jdbc.update("update health_covers set status = 'REGISTERED', reason = null, updated_at = now() where id = ? and status = 'READY'", id);
            return new Outcome(n > 0 ? "APPLIED" : "IGNORED", index, "Registration confirmed");
        }
        covers.rejected(id, "NHIF registration " + code + (remarks == null ? "" : ": " + remarks));
        return new Outcome("APPLIED", index, "Registration refused (" + code + ")");
    }

    private Outcome cardApplication(JsonNode b) {
        String batchNo = text(b, "batchNo");
        String controlNo = text(b, "controlNo");
        Long id = inBatch(batchNo, text(b, "formFourIndexNo"), text(b, "identificationNo"));
        if (id == null) return new Outcome("UNMATCHED", batchNo, "Unrecognized BatchNo or student");
        if (controlNo == null) return new Outcome("UNMATCHED", batchNo, "No controlNo");
        BigDecimal amount = b.hasNonNull("invoiceAmount") ? new BigDecimal(b.get("invoiceAmount").asText()) : null;
        boolean applied = covers.controlNumber(id, controlNo, null, amount);
        return new Outcome(applied ? "APPLIED" : "IGNORED", controlNo, applied ? "Control number received." : "Control number already known.");
    }

    private Outcome payment(JsonNode b) {
        String controlNo = text(b, "controlNo");
        Long id = byControlNo(controlNo);
        if (id == null) return new Outcome("UNMATCHED", controlNo, "Unrecognized controlNo");
        String status = text(b, "status");
        if (status == null || !status.equalsIgnoreCase("paid")) return new Outcome("IGNORED", controlNo, "Payment status " + status + " noted");
        String date = text(b, "paymentDate");
        boolean applied = covers.paid(id, controlNo, date == null || date.length() < 10 ? null : LocalDate.parse(date.substring(0, 10)));
        return new Outcome(applied ? "APPLIED" : "IGNORED", controlNo, applied ? "Payment status received." : "Payment already recorded.");
    }

    private Outcome cardIssued(JsonNode b) {
        String controlNo = text(b, "controlNo");
        String card = text(b, "cardno");
        Long id = byControlNo(controlNo);
        if (id == null) id = inBatch(text(b, "batchNo"), text(b, "formFourIndexNo"), text(b, "identificationNo"));
        if (id == null) return new Outcome("UNMATCHED", controlNo, "Unrecognized controlNo or BatchNo");
        boolean applied = covers.cardIssued(id, card);
        return new Outcome(applied ? "APPLIED" : "IGNORED", card, applied ? "Card number received." : "Card already recorded.");
    }

    private Outcome changeRequest(JsonNode b) {
        String card = text(b, "cardNo");
        String index = text(b, "formFourIndex");
        Long id = card == null ? null : jdbc.queryForList("select id from health_covers where card_no = ? order by id desc limit 1", Long.class,
                card.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "")).stream().findFirst().orElse(null);
        if (id == null && index != null) id = byIndex(index, null);
        String note = "Change request " + text(b, "status") + (text(b, "remarks") == null ? "" : ": " + text(b, "remarks"));
        return new Outcome(id == null ? "UNMATCHED" : "APPLIED", card != null ? card : index, id == null ? "Unrecognized CardNo" : note);
    }

    // ------------------------------------------------------------------ finding the student

    /** The cover of the student with this Form Four index (NHIF's S0101-0001-2023), optionally in one of the given statuses. */
    private Long byIndex(String formFourIndex, List<String> statuses) {
        if (formFourIndex == null) return null;
        String i = formFourIndex.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (!i.matches("[SP]\\d{12}")) return null;
        String index = i.substring(0, 5) + "/" + i.substring(5, 9);
        int year = Integer.parseInt(i.substring(9));
        String filter = statuses == null ? "" : " and c.status in (" + String.join(",", statuses.stream().map(x -> "'" + x + "'").toList()) + ")";
        return jdbc.queryForList("""
                select c.id from health_covers c join students s on s.id = c.student_id join applicants a on a.id = s.applicant_id
                join academic_years y on y.id = c.academic_year_id
                where c.route = 'INSTITUTION' and upper(a.necta_index_number) = ? and a.necta_exam_year = ?""" + filter + """
                 order by y.start_date desc limit 1""", Long.class, index, year).stream().findFirst().orElse(null);
    }

    private Long byControlNo(String controlNo) {
        if (controlNo == null) return null;
        return jdbc.queryForList("select id from health_covers where nhif_control_no = ? order by id desc limit 1", Long.class, controlNo.trim())
                .stream().findFirst().orElse(null);
    }

    /** A cover in a card application, by the Form Four index or the NIDA number or card number NHIF names. */
    private Long inBatch(String batchNo, String formFourIndex, String identificationNo) {
        if (batchNo == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.id, c.card_no, s.nin_encrypted, a.necta_index_number, a.necta_exam_year from health_covers c
                join nhif_registration_batches b on b.id = c.application_batch_id join students s on s.id = c.student_id
                left join applicants a on a.id = s.applicant_id where b.batch_ref = ?""", batchNo.trim());
        String idf = identificationNo == null ? null : identificationNo.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        String fi = formFourIndex == null ? null : formFourIndex.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        for (Map<String, Object> r : rows) {
            Long id = ((Number) r.get("id")).longValue();
            if (idf != null && idf.equals(r.get("card_no"))) return id;
            String enc = (String) r.get("nin_encrypted");
            if (idf != null && enc != null && idf.equals(cipher.decrypt(enc).replaceAll("\\D", ""))) return id;
            String idx = (String) r.get("necta_index_number");
            if (fi != null && idx != null && r.get("necta_exam_year") != null
                    && fi.equals(idx.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "") + r.get("necta_exam_year"))) return id;
        }
        return rows.size() == 1 && idf == null && fi == null ? ((Number) rows.get(0).get("id")).longValue() : null;
    }

    private static boolean has(JsonNode b, String key) {
        return text(b, key) != null;
    }

    /** A field, also when NHIF writes it with a stray space (" controlNo"). */
    static String text(JsonNode b, String key) {
        JsonNode v = b.get(key);
        if (v == null) {
            var it = b.fieldNames();
            while (it.hasNext()) {
                String f = it.next();
                if (f.trim().equalsIgnoreCase(key)) {
                    v = b.get(f);
                    break;
                }
            }
        }
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
