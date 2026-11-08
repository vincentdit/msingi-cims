package tz.co.msingi.cims.admission.letter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The record of checks made on the public verification page (BP-16): which code, what it was, whether it was valid. The registry
 * sees how often a certificate or transcript is checked (employers, universities) and spots codes being guessed. The visitor's
 * address is kept only as a hash that changes every day, enough to count distinct visitors and never to trace one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationLog {

    private static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public record Entry(OffsetDateTime verifiedAt, String code, String documentType, String documentNo, String result) {
    }

    public record Summary(long checks, long valid, long notValid, long notFound, long visitors, List<Entry> recent) {
    }

    /** Records one check; never fails the visitor's request. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String code, Object found, String clientAddress) {
        try {
            Long tenant = TenantContext.currentTenantIdOrNull();
            if (tenant == null) return;
            String type = null, no = null, result = "NOT_FOUND";
            if (found != null) {
                Map<?, ?> m = json.convertValue(found, Map.class);
                type = str(m.get("documentType"), 30);
                no = str(m.get("letterNo") != null ? m.get("letterNo") : m.get("documentNo"), 60);
                result = "VALID".equals(m.get("status")) ? "VALID" : "NOT_VALID";
            }
            String c = code == null ? "" : code.trim().toUpperCase();
            jdbc.update("insert into document_verifications (tenant_id, code, document_type, document_no, result, client_hash) values (?, ?, ?, ?, ?, ?)",
                    tenant, c.length() > 30 ? c.substring(0, 30) : c, type, no, result, hash(clientAddress));
        } catch (RuntimeException e) {
            log.warn("Verification not logged: {}", e.getMessage());
        }
    }

    /** The last days' checks, for the Registrar, Examinations Officer, administrator and management. */
    @Transactional(readOnly = true)
    public Summary summary(int days) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(Math.max(1, Math.min(days, 366)));
        Map<String, Object> t = jdbc.queryForMap("""
                select count(*) as checks, count(*) filter (where result = 'VALID') as valid, count(*) filter (where result = 'NOT_VALID') as not_valid,
                       count(*) filter (where result = 'NOT_FOUND') as not_found, count(distinct client_hash) as visitors
                from document_verifications where verified_at >= ?""", since);
        List<Entry> recent = jdbc.query("""
                select verified_at, code, document_type, document_no, result from document_verifications where verified_at >= ?
                order by verified_at desc limit 200""", (rs, i) -> new Entry(rs.getObject(1, OffsetDateTime.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)), since);
        return new Summary(n(t.get("checks")), n(t.get("valid")), n(t.get("not_valid")), n(t.get("not_found")), n(t.get("visitors")), recent);
    }

    static String hash(String address) {
        if (address == null) return null;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((LocalDate.now(ZONE) + "|" + address).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String str(Object o, int max) {
        if (o == null) return null;
        String s = o.toString();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static long n(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
