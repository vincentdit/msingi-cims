package tz.co.msingi.cims.helpdesk;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.notification.email.EmailMessage;
import tz.co.msingi.cims.notification.email.EmailService;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tells people what happened to a helpdesk ticket. Requesters get an SMS (and the same by email to a verified address), or an
 * email when they are staff without a phone; guests' messages carry their access code, kept masked in the message log. Staff are
 * told by email: their office of a new ticket, the assignee of a reply, the office and then the administrators when targets are
 * missed. Emails are written in the institution's message language.
 */
@Component
@RequiredArgsConstructor
public class HelpdeskNotifier {

    private static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");

    private final JdbcTemplate jdbc;
    private final Messaging messaging;
    private final EmailService email;

    /** The facts about a ticket a message needs. */
    public record Ticket(Long id, String ticketNo, String subject, String requesterType, Long requesterUserId, String requesterName,
                         String requesterPhone, String requesterEmail, String officeRole, Long assigneeUserId, String categoryName,
                         OffsetDateTime responseDueAt) {
    }

    // ---------------------------------------------------------------- the requester

    public void received(Ticket t, String guestCode) {
        Map<String, Object> v = values(t);
        v.put("due", when(t.responseDueAt()));
        if (guestCode != null) {
            v.put("code", guestCode);
            if (t.requesterPhone() != null) messaging.eventWithLink("HELPDESK_RECEIVED_GUEST", t.requesterPhone(), v, "code");
            if (t.requesterEmail() != null && !t.requesterEmail().isBlank()) {
                String subject = sw() ? "Tumepokea ombi lako " + t.ticketNo() : "We have received your request " + t.ticketNo();
                String body = sw() ? "Tumepokea ombi lako " + t.ticketNo() + " (" + t.subject() + "). Tunalenga kukujibu kabla ya " + v.get("due")
                        + ".\n\nKulifuatilia, fungua ukurasa wa msaada wa tovuti ya udahili na uweke namba ya ombi pamoja na nambari ya siri @@CODE@@."
                        : "We have received your request " + t.ticketNo() + " (" + t.subject() + "). We aim to reply by " + v.get("due")
                        + ".\n\nTo follow it, open the help page of the admission portal and enter the request number with the code @@CODE@@.";
                // the code is sent but kept masked in the outbox
                email.queueSecret(t.requesterEmail(), t.requesterName(), subject, body.replace("@@CODE@@", guestCode), body.replace("@@CODE@@", "********"));
            }
            return;
        }
        requester(t, "HELPDESK_RECEIVED", v);
    }

    public void replied(Ticket t) {
        if ("GUEST".equals(t.requesterType())) {
            if (t.requesterPhone() != null) messaging.event("HELPDESK_REPLY_GUEST", t.requesterPhone(), values(t));
            guestEmail(t, (sw() ? "Jibu la ombi lako " : "A reply to your request ") + t.ticketNo(),
                    sw() ? "Kuna jibu la ombi lako " + t.ticketNo() + " (" + t.subject() + "). Lisome kwenye ukurasa wa msaada kwa namba ya ombi na nambari yako ya siri."
                            : "There is a reply to your request " + t.ticketNo() + " (" + t.subject() + "). Read it on the help page with the request number and your code.");
            return;
        }
        requester(t, "HELPDESK_REPLY", values(t));
    }

    public void resolved(Ticket t) {
        if ("GUEST".equals(t.requesterType())) {
            if (t.requesterPhone() != null) messaging.event("HELPDESK_RESOLVED", t.requesterPhone(), values(t));
            return;
        }
        requester(t, "HELPDESK_RESOLVED", values(t));
    }

    private void requester(Ticket t, String code, Map<String, Object> v) {
        if (t.requesterPhone() != null && !t.requesterPhone().isBlank()) {
            messaging.event(code, t.requesterPhone(), v);
        } else if (t.requesterEmail() != null) {
            // staff without a phone on record: the same news by email
            String text = switch (code) {
                case "HELPDESK_RECEIVED" -> sw() ? "Tumepokea ombi lako " + t.ticketNo() + " (" + t.subject() + "). Tunalenga kukujibu kabla ya " + v.get("due") + "."
                        : "We have received your request " + t.ticketNo() + " (" + t.subject() + "). We aim to reply by " + v.get("due") + ".";
                case "HELPDESK_REPLY" -> sw() ? "Kuna jibu la ombi lako " + t.ticketNo() + " (" + t.subject() + "). Lisome kwenye Msaada katika CIMS."
                        : "There is a reply to your request " + t.ticketNo() + " (" + t.subject() + "). Read it under Help in CIMS.";
                default -> sw() ? "Ombi lako " + t.ticketNo() + " limejibiwa na kuwekwa kuwa limetatuliwa. Kama bado unahitaji msaada, jibu ndani ya siku 7."
                        : "Your request " + t.ticketNo() + " has been answered and marked resolved. If you still need help, reply within 7 days.";
            };
            email.queue(t.requesterEmail(), t.requesterName(), EmailMessage.Kind.EVENT, code, (sw() ? "Ombi " : "Request ") + t.ticketNo(), text, null);
        }
    }

    private void guestEmail(Ticket t, String subject, String body) {
        if (t.requesterEmail() != null && !t.requesterEmail().isBlank()) {
            email.queue(t.requesterEmail(), t.requesterName(), EmailMessage.Kind.EVENT, "HELPDESK_GUEST", subject, body, null);
        }
    }

    // ---------------------------------------------------------------- staff

    /** A new ticket: the office that answers its category. */
    public void newTicket(Ticket t) {
        staff(office(t.officeRole()), "HELPDESK_NEW",
                (sw() ? "Ombi jipya la msaada " : "New helpdesk request ") + t.ticketNo(),
                sw() ? t.requesterName() + " ameleta ombi " + t.ticketNo() + " (" + t.categoryName() + "): " + t.subject()
                        + ".\nJibu la kwanza linatakiwa kabla ya " + when(t.responseDueAt()) + ". Lifungue kwenye Msaada katika CIMS."
                        : t.requesterName() + " raised " + t.ticketNo() + " (" + t.categoryName() + "): " + t.subject()
                        + ".\nA first reply is due by " + when(t.responseDueAt()) + ". Open it under Helpdesk in CIMS.");
    }

    /** The requester answered: the assignee, or the office when nobody has it yet. */
    public void requesterReplied(Ticket t) {
        List<Recipient> to = t.assigneeUserId() != null ? user(t.assigneeUserId()) : office(t.officeRole());
        staff(to, "HELPDESK_REQUESTER_REPLY", (sw() ? "Jibu jipya kwenye " : "New reply on ") + t.ticketNo(),
                sw() ? t.requesterName() + " amejibu kwenye ombi " + t.ticketNo() + " (" + t.subject() + ")."
                        : t.requesterName() + " replied on " + t.ticketNo() + " (" + t.subject() + ").");
    }

    public void assigned(Ticket t, Long userId, String byName) {
        staff(user(userId), "HELPDESK_ASSIGNED", (sw() ? "Umepewa ombi " : "Assigned to you: ") + t.ticketNo(),
                sw() ? byName + " amekupa ombi " + t.ticketNo() + " (" + t.subject() + ") kutoka kwa " + t.requesterName() + "."
                        : byName + " assigned " + t.ticketNo() + " (" + t.subject() + ") from " + t.requesterName() + " to you.");
    }

    /** Targets missed: level 1 (no first reply in time) the office and assignee; level 2 (not resolved in time) the administrators. */
    public void escalated(Ticket t, int level) {
        List<Recipient> to = level == 1 ? office(t.officeRole()) : office("INSTITUTION_ADMIN");
        if (level == 1 && t.assigneeUserId() != null) to = merge(to, user(t.assigneeUserId()));
        staff(to, "HELPDESK_ESCALATED", (sw() ? "Ombi limechelewa: " : "Overdue: ") + t.ticketNo(),
                level == 1
                        ? (sw() ? "Ombi " + t.ticketNo() + " (" + t.subject() + ") kutoka kwa " + t.requesterName() + " halijajibiwa kwa wakati. Tafadhali lijibu sasa."
                        : t.ticketNo() + " (" + t.subject() + ") from " + t.requesterName() + " has not had a reply in time. Please answer it now.")
                        : (sw() ? "Ombi " + t.ticketNo() + " (" + t.subject() + ", " + t.categoryName() + ") halijatatuliwa kwa wakati uliowekwa. Limepelekwa kwa wasimamizi."
                        : t.ticketNo() + " (" + t.subject() + ", " + t.categoryName() + ") was not resolved in the time set. It has been escalated to the administrators."));
    }

    // ---------------------------------------------------------------- helpers

    record Recipient(String email, String name) {
    }

    /** Active staff with the office's role, or the administrators when nobody holds it. */
    List<Recipient> office(String role) {
        List<Recipient> r = byRole(role);
        return r.isEmpty() && !"INSTITUTION_ADMIN".equals(role) ? byRole("INSTITUTION_ADMIN") : r;
    }

    private List<Recipient> byRole(String role) {
        return jdbc.query("""
                select distinct u.email, u.full_name from user_accounts u join user_roles r on r.user_id = u.id
                where r.role = ? and u.user_type = 'STAFF' and u.status = 'ACTIVE' and u.email is not null""",
                (rs, i) -> new Recipient(rs.getString(1), rs.getString(2)), role);
    }

    private List<Recipient> user(Long id) {
        return jdbc.query("select email, full_name from user_accounts where id = ? and email is not null and status = 'ACTIVE'",
                (rs, i) -> new Recipient(rs.getString(1), rs.getString(2)), id);
    }

    private static List<Recipient> merge(List<Recipient> a, List<Recipient> b) {
        Map<String, Recipient> m = new LinkedHashMap<>();
        a.forEach(x -> m.put(x.email().toLowerCase(Locale.ROOT), x));
        b.forEach(x -> m.put(x.email().toLowerCase(Locale.ROOT), x));
        return List.copyOf(m.values());
    }

    private void staff(List<Recipient> to, String code, String subject, String body) {
        String foot = sw() ? "\n\n— Msaada wa CIMS (usijibu barua pepe hii; jibu ndani ya CIMS)" : "\n\n— CIMS Helpdesk (please do not reply to this email; answer in CIMS)";
        for (Recipient r : to) email.queue(r.email(), r.name(), EmailMessage.Kind.EVENT, code, subject, body + foot, null);
    }

    private Map<String, Object> values(Ticket t) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ticketNo", t.ticketNo());
        v.put("subject", t.subject().length() > 40 ? t.subject().substring(0, 39) + "…" : t.subject());
        return v;
    }

    private boolean sw() {
        List<String> l = jdbc.queryForList("select default_language from messaging_settings", String.class);
        return !l.isEmpty() && "SW".equals(l.get(0));
    }

    static String when(OffsetDateTime t) {
        return t == null ? "" : t.atZoneSameInstant(TZ).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK));
    }
}
