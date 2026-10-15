package tz.co.msingi.cims.registry.nin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.integration.http.ExternalSystemUnavailableException;
import tz.co.msingi.cims.integration.nida.NidaClient;
import tz.co.msingi.cims.integration.settings.IntegrationSettingsService;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.security.mfa.SecretCipher;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * A student's National Identification Number (FR-INT-06, NFR-SEC-03): kept encrypted (AES-256-GCM), shown only as its last four
 * digits, with a keyed hash so the same NIN cannot be recorded for two students. When the institution uses NIDA, the number is
 * checked against the student's names and date of birth as it is entered; otherwise it is kept as given (UNVERIFIED).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NinService {

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final NidaClient nida;
    private final IntegrationSettingsService settings;
    private final AuditService audit;

    public record NinView(Long studentId, String last4, String status, OffsetDateTime checkedAt, boolean nidaEnabled, String message) {
    }

    private record Person(Long id, String registrationNo, String firstName, String surname, LocalDate dob) {
    }

    @Transactional(readOnly = true)
    public NinView mine() {
        return view(me().id(), null);
    }

    @Transactional
    public NinView saveMine(String nin) {
        return save(me(), nin);
    }

    @Transactional(readOnly = true)
    public NinView get(Long studentId) {
        return view(person(studentId).id(), null);
    }

    @Transactional
    public NinView set(Long studentId, String nin) {
        return save(person(studentId), nin);
    }

    /** Checks the stored NIN with NIDA again (e.g. after NIDA was unreachable, or names were corrected). */
    @Transactional
    public NinView verifyAgain(Long studentId) {
        Person p = person(studentId);
        String enc = jdbc.queryForObject("select nin_encrypted from students where id = ?", String.class, p.id());
        if (enc == null) throw new BusinessRuleException("NO_NIN", "No NIN is recorded for " + p.registrationNo());
        return check(p, cipher.decrypt(enc));
    }

    private NinView save(Person p, String raw) {
        String nin = NidaClient.normalise(raw);
        if (nin == null) throw new BusinessRuleException("INVALID_NIN", "A National Identification Number has 20 digits, e.g. 19990101-12345-00001-12.");
        String hash = cipher.sign("nin:" + nin);
        Integer other = jdbc.queryForObject("select count(*) from students where nin_hash = ? and id <> ?", Integer.class, hash, p.id());
        if (other != null && other > 0) {
            throw new BusinessRuleException("NIN_IN_USE", "This NIN is already recorded for another student. Contact the Registrar if it is yours.");
        }
        jdbc.update("""
                update students set nin_encrypted = ?, nin_last4 = ?, nin_hash = ?, nin_status = 'UNVERIFIED', nin_checked_at = null, updated_at = now()
                where id = ?""", cipher.encrypt(nin), nin.substring(16), hash, p.id());
        audit.record("NIN_RECORDED", "Student", p.id(), Map.of("last4", nin.substring(16)));
        return check(p, nin);
    }

    private NinView check(Person p, String nin) {
        if (!settings.nidaEnabled()) return view(p.id(), null);
        NidaClient.Result r;
        try {
            r = nida.verify(nin, p.firstName(), p.surname(), p.dob());
        } catch (ExternalSystemUnavailableException e) {
            log.warn("NIDA unavailable for student {}: {}", p.id(), e.getMessage());
            return view(p.id(), "NIDA could not be reached; the NIN is saved and will be checked later.");
        }
        jdbc.update("update students set nin_status = ?, nin_checked_at = now() where id = ?", r.status(), p.id());
        audit.record("NIN_VERIFIED", "Student", p.id(), Map.of("last4", nin.substring(16), "status", r.status()));
        String msg = switch (r.status()) {
            case "VALID" -> "NIDA confirmed the NIN.";
            case "MISMATCH" -> "NIDA holds this NIN for different details. Check the number, or ask the Registrar to correct your name or date of birth.";
            case "NOT_FOUND" -> "NIDA does not know this NIN. Check the number on your ID card.";
            default -> "NIDA says this is not a valid NIN.";
        };
        return view(p.id(), msg);
    }

    private NinView view(Long id, String message) {
        return jdbc.queryForObject("select nin_last4, nin_status, nin_checked_at from students where id = ?", (rs, i) -> new NinView(id,
                rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class), settings.nidaEnabled(), message), id);
    }

    private Person me() {
        List<Person> l = jdbc.query("select id, registration_no, first_name, surname, date_of_birth from students where user_id = ?",
                (rs, i) -> new Person(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, LocalDate.class)), CurrentUser.requireId());
        if (l.isEmpty()) throw new NotFoundException("No student record is linked to this account");
        return l.get(0);
    }

    private Person person(Long id) {
        List<Person> l = jdbc.query("select id, registration_no, first_name, surname, date_of_birth from students where id = ?",
                (rs, i) -> new Person(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, LocalDate.class)), id);
        if (l.isEmpty()) throw new NotFoundException("Student", id);
        return l.get(0);
    }
}
