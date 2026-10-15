package tz.co.msingi.cims.ipt;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.notification.email.EmailService;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.links.PortalLinks;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.registry.Student;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The workplace supervisor assesses the student online (FR-IPT-03): the IPT office sends a personal link by SMS and email, the
 * supervisor rates the student on five points without signing in, and the rating becomes the workplace mark.
 */
@Service
@RequiredArgsConstructor
public class WorkplaceAssessmentService {

    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    /** What the workplace supervisor rates, each from 1 (poor) to 5 (excellent). */
    public static final Map<String, String> CRITERIA = new LinkedHashMap<>();

    static {
        CRITERIA.put("ATTENDANCE", "Attendance and punctuality");
        CRITERIA.put("SKILLS", "Practical skills in the work given");
        CRITERIA.put("INITIATIVE", "Initiative and willingness to learn");
        CRITERIA.put("CONDUCT", "Conduct, teamwork and communication");
        CRITERIA.put("SAFETY", "Following workplace rules and safety");
    }

    private final IptService ipt;
    private final JdbcTemplate jdbc;
    private final PortalLinks links;
    private final Messaging messaging;
    private final EmailService email;
    private final AuditService audit;
    private final StudentRepository students;
    private final ObjectMapper mapper;

    public record LinkRequest(@Size(max = 20) String phone, @Email @Size(max = 150) String email) {
    }

    public record LinkSent(String url, OffsetDateTime expiresAt, String sentToPhone, String sentToEmail) {
    }

    public record FormView(String institution, String studentName, String registrationNo, String programme, String organisation, String unit,
                           LocalDate startsOn, LocalDate endsOn, String supervisorName, Map<String, String> criteria, boolean submitted,
                           OffsetDateTime expiresAt) {
    }

    public record Submission(@NotEmpty Map<String, Integer> ratings, @Size(max = 2000) String comments, @NotBlank @Size(max = 150) String supervisorName) {
    }

    @Transactional
    public LinkSent send(Long placementId, LinkRequest req) {
        IptPlacement pl = ipt.placement(placementId);
        ipt.requireSupervisor(pl);
        if (pl.getStatus() != IptPlacement.Status.CONFIRMED && pl.getStatus() != IptPlacement.Status.COMPLETED) {
            throw new BusinessRuleException("NOT_PLACED", "Only confirmed placements are assessed");
        }
        String phone = req.phone() != null && !req.phone().isBlank() ? req.phone() : pl.getWorkplaceSupervisorPhone();
        String to = req.email() != null && !req.email().isBlank() ? req.email() : pl.getWorkplaceSupervisorEmail();
        if ((phone == null || phone.isBlank()) && (to == null || to.isBlank())) {
            throw new BusinessRuleException("NO_CONTACT", "Give the workplace supervisor's mobile number or email address");
        }
        if (phone != null && !phone.isBlank()) phone = tz.co.msingi.cims.admission.service.PhoneNumbers.normalise(phone);
        jdbc.update("update ipt_assessment_links set revoked_at = now() where placement_id = ? and revoked_at is null and used_at is null", placementId);
        PortalLinks.Token t = links.newToken();
        LocalDate until = (pl.getEndsOn().isBefore(LocalDate.now(ZONE)) ? LocalDate.now(ZONE) : pl.getEndsOn()).plusDays(30);
        OffsetDateTime expires = until.atTime(23, 59).atZone(ZONE).toOffsetDateTime();
        jdbc.update("""
                insert into ipt_assessment_links (tenant_id, placement_id, token_hash, expires_at, sent_to_phone, sent_to_email, created_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?)""", placementId, t.hash(), expires, phone, to, CurrentUser.name());
        String url = links.url("/ipt-assessment/" + t.token());
        Student s = students.findById(pl.getStudentId()).orElseThrow();
        String institution = jdbc.queryForObject("select name from tenants where id = cims_current_tenant()", String.class);
        if (phone != null && !phone.isBlank()) {
            messaging.eventWithLink("IPT_WORKPLACE_ASSESSMENT", phone, Map.of("student", s.fullName(), "link", url, "deadline", DATE.format(until)), "link");
        }
        if (to != null && !to.isBlank()) {
            String body = """
                    Dear %s,

                    Thank you for supervising %s (%s) during industrial practical training at %s.
                    Please assess the student by %s using this personal link (no account is needed):

                    %s

                    — %s""";
            String name = pl.getWorkplaceSupervisorName() == null ? "Supervisor" : pl.getWorkplaceSupervisorName();
            email.queueSecret(to, name, "Assessment of " + s.fullName() + " — " + institution,
                    body.formatted(name, s.fullName(), s.getRegistrationNo(), pl.getOrganisation().getName(), DATE.format(until), url, institution),
                    body.formatted(name, s.fullName(), s.getRegistrationNo(), pl.getOrganisation().getName(), DATE.format(until), "[personal link]", institution));
        }
        audit.record("IPT_WORKPLACE_LINK_SENT", "IptPlacement", placementId, Map.of("phone", phone == null ? "" : phone, "email", to == null ? "" : to));
        return new LinkSent(url, expires, phone, to);
    }

    @Transactional(readOnly = true)
    public FormView form(String token) {
        Map<String, Object> l = link(token);
        IptPlacement pl = ipt.placement(((Number) l.get("placement_id")).longValue());
        Student s = students.findById(pl.getStudentId()).orElseThrow();
        String institution = jdbc.queryForObject("select name from tenants where id = cims_current_tenant()", String.class);
        return new FormView(institution, s.fullName(), s.getRegistrationNo(), s.getProgramme().getName(), pl.getOrganisation().getName(), pl.getUnit(),
                pl.getStartsOn(), pl.getEndsOn(), pl.getWorkplaceSupervisorName(), CRITERIA, l.get("used_at") != null,
                ((java.sql.Timestamp) l.get("expires_at")).toInstant().atZone(ZONE).toOffsetDateTime());
    }

    @Transactional
    public FormView submit(String token, Submission sub) {
        Map<String, Object> l = link(token);
        if (l.get("used_at") != null) throw new BusinessRuleException("ALREADY_SUBMITTED", "This assessment has already been sent. Thank you.");
        if (!sub.ratings().keySet().equals(CRITERIA.keySet())) throw new BusinessRuleException("RATINGS_INCOMPLETE", "Rate the student on every point");
        if (sub.ratings().values().stream().anyMatch(v -> v == null || v < 1 || v > 5)) throw new BusinessRuleException("RATINGS_RANGE", "Each rating is from 1 to 5");
        IptPlacement pl = ipt.placement(((Number) l.get("placement_id")).longValue());
        int total = sub.ratings().values().stream().mapToInt(Integer::intValue).sum();
        BigDecimal score = BigDecimal.valueOf(total * 100L).divide(BigDecimal.valueOf(CRITERIA.size() * 5L), 2, RoundingMode.HALF_UP);
        String by = sub.supervisorName().trim() + " (workplace supervisor)";
        ipt.recordMark(pl, IptAssessment.Kind.WORKPLACE, score, sub.comments(), by);
        try {
            jdbc.update("update ipt_assessments set criteria = ?::jsonb, source = 'LINK' where placement_id = ? and kind = 'WORKPLACE'",
                    mapper.writeValueAsString(sub.ratings()), pl.getId());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("update ipt_assessment_links set used_at = now() where id = ?", l.get("id"));
        return form(token);
    }

    private Map<String, Object> link(String token) {
        if (token == null || token.length() < 20 || token.length() > 64) throw new NotFoundException("This link is not valid");
        List<Map<String, Object>> r = jdbc.queryForList("select id, placement_id, expires_at, used_at, revoked_at from ipt_assessment_links where token_hash = ?",
                PortalLinks.hash(token));
        if (r.isEmpty() || r.get(0).get("revoked_at") != null) throw new NotFoundException("This link is not valid. Ask the institution for a new one.");
        if (r.get(0).get("used_at") == null && ((java.sql.Timestamp) r.get(0).get("expires_at")).toInstant().isBefore(java.time.Instant.now())) {
            throw new BusinessRuleException("LINK_EXPIRED", "This link has expired. Ask the institution for a new one.");
        }
        return r.get(0);
    }
}
