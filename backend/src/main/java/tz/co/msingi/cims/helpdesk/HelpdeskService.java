package tz.co.msingi.cims.helpdesk;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.admission.service.PhoneNumbers;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.helpdesk.HelpdeskCategoryService.CategoryView;
import tz.co.msingi.cims.helpdesk.HelpdeskFiles.AttachmentView;
import tz.co.msingi.cims.helpdesk.HelpdeskFiles.FileContent;
import tz.co.msingi.cims.helpdesk.HelpdeskFiles.Upload;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.numbering.NumberSequenceService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The institution's helpdesk (FR-HLP-01…08). Applicants, students and staff raise tickets from their portals and people without
 * an account from the public help page; each ticket goes to the office that answers its category, with a first-reply and a
 * resolution target. Staff reply, add notes only staff see, assign, move a ticket to another office and mark it waiting or
 * resolved; the requester can reply, reopen within {@value #REOPEN_DAYS} days or close it. Resolved tickets close by themselves
 * after {@value #REOPEN_DAYS} days.
 *
 * Who sees a ticket at the helpdesk: the staff of its office, the person it is assigned to, the Institution Administrator (all),
 * and the Auditor and management (all, read only).
 */
@Service
@RequiredArgsConstructor
public class HelpdeskService {

    public static final int REOPEN_DAYS = 7;
    static final int MAX_OPEN_PER_PERSON = 5;
    static final int MAX_OPEN_PER_GUEST_PHONE = 3;
    private static final Set<String> OPEN = Set.of("OPEN", "IN_PROGRESS", "WAITING");
    private static final Set<String> READERS = Set.of("AUDITOR", "MANAGEMENT_VIEWER");
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final HelpdeskCategoryService categories;
    private final HelpdeskNotifier notifier;
    private final NumberSequenceService numbers;
    private final AuditService audit;
    private final tz.co.msingi.cims.helpdesk.assistant.HelpdeskAssistant assistant;

    // ================================================================= views

    public record TicketRow(Long id, String ticketNo, String subject, Long categoryId, String categoryName, String officeRole, String status,
                            String priority, String requesterType, String requesterName, Long assigneeUserId, String assigneeName,
                            OffsetDateTime responseDueAt, OffsetDateTime resolutionDueAt, OffsetDateTime firstResponseAt,
                            OffsetDateTime resolvedAt, OffsetDateTime closedAt, String late, int escalationLevel, Boolean satisfied,
                            OffsetDateTime createdAt, OffsetDateTime updatedAt, int messages) {
    }

    public record MessageView(Long id, String authorType, String authorName, String body, boolean internal, OffsetDateTime createdAt,
                              List<AttachmentView> attachments) {
    }

    /** One ticket with its conversation. What a requester sees leaves out staff notes and contact details. */
    public record TicketView(TicketRow ticket, String requesterPhone, String requesterEmail, List<MessageView> messages,
                             boolean canReply, boolean canClose, boolean canManage, String msingiTicketNo, Long msingiTicketId,
                             String suggestionOutcome) {
    }

    public record DeskPage(List<TicketRow> rows, long total, Map<String, Long> counts) {
    }

    public record GuestCreated(String ticketNo, String code, TicketView ticket) {
    }

    public record Agent(Long id, String name, List<String> roles) {
    }

    public record CategoryStats(Long categoryId, String name, long received, long resolved, long open, long late, Double averageFirstReplyHours,
                                Double repliedInTimePercent, Double resolvedInTimePercent, Double satisfiedPercent) {
    }

    /** What the assistant did in the period: questions answered before asking, answers given at once, categories guessed. */
    public record AssistantStats(long solvedBeforeAsking, long instantAnswers, long instantAccepted, long instantRejected, long categoryGuessed,
                                 long categoryRight, long learnedReplies) {
    }

    public record Performance(int days, CategoryStats overall, List<CategoryStats> categories, AssistantStats assistant) {
    }

    // ================================================================= requesters with an account

    /** APPLICANT, STUDENT or STAFF: which categories and articles the signed-in person sees. */
    @Transactional(readOnly = true)
    public String requesterType() {
        return typeOf(CurrentUser.requireId());
    }

    /** Staff, a student (an applicant's account handed over to Registry carries the STUDENT role) or an applicant. */
    private String typeOf(Long uid) {
        return jdbc.queryForObject("""
                select case when u.user_type = 'STAFF' then 'STAFF'
                            when exists (select 1 from user_roles r where r.user_id = u.id and r.role = 'STUDENT') then 'STUDENT'
                            else 'APPLICANT' end
                from user_accounts u where u.id = ?""", String.class, uid);
    }

    @Transactional(readOnly = true)
    public List<TicketRow> mine() {
        return jdbc.query(SELECT + " where t.requester_user_id = ? order by t.updated_at desc", ROW, CurrentUser.requireId());
    }

    @Transactional
    public TicketView create(Long categoryId, String subject, String body, List<MultipartFile> files) {
        Long uid = CurrentUser.requireId();
        Map<String, Object> u = jdbc.queryForMap("select full_name, phone, email from user_accounts where id = ?", uid);
        String type = typeOf(uid);
        Integer open = jdbc.queryForObject("select count(*) from helpdesk_tickets where requester_user_id = ? and status in ('OPEN', 'IN_PROGRESS', 'WAITING')",
                Integer.class, uid);
        if (open != null && open >= MAX_OPEN_PER_PERSON) {
            throw new BusinessRuleException("TOO_MANY_OPEN", "You already have " + open + " open requests. Wait for them to be answered, or add to one of them.");
        }
        CategoryView c = allowedCategory(categoryId, type);
        List<Upload> uploads = HelpdeskFiles.read(files);
        Long id = insert(c, type, uid, (String) u.get("full_name"), (String) u.get("phone"), (String) u.get("email"), null, subject, body, uploads);
        predict(id, type, subject, body);
        Ticket t = load(id);
        notifier.received(t.facts(c.name()), null);
        notifier.newTicket(t.facts(c.name()));
        return requesterView(id);
    }

    @Transactional(readOnly = true)
    public TicketView myTicket(Long id) {
        own(id);
        return requesterView(id);
    }

    @Transactional
    public TicketView requesterReply(Long id, String body, List<MultipartFile> files) {
        Ticket t = own(id);
        reply(t, CurrentUser.requireId(), t.requesterName, body, HelpdeskFiles.read(files));
        return requesterView(id);
    }

    @Transactional
    public TicketView requesterClose(Long id, Boolean satisfied) {
        Ticket t = own(id);
        close(t, satisfied, t.requesterName);
        return requesterView(id);
    }

    /** The requester says whether the answer the assistant gave at once solved the problem. */
    @Transactional
    public TicketView requesterAssistantFeedback(Long id, boolean helpful) {
        Ticket t = own(id);
        assistantFeedback(t, helpful);
        return requesterView(id);
    }

    @Transactional(readOnly = true)
    public FileContent myAttachment(Long attachmentId) {
        Long ticketId = ticketOfAttachment(attachmentId, false);
        own(ticketId);
        return file(attachmentId);
    }

    // ================================================================= people without an account (public help page)

    @Transactional
    public GuestCreated guestCreate(String name, String phone, String email, Long categoryId, String subject, String body, List<MultipartFile> files) {
        if (name == null || name.isBlank() || name.trim().length() > 150) throw new BusinessRuleException("NAME_REQUIRED", "Enter your name.");
        String msisdn = PhoneNumbers.normalise(phone);
        String mail = email == null || email.isBlank() ? null : email.trim();
        if (mail != null && (mail.length() > 120 || !mail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))) {
            throw new BusinessRuleException("INVALID_EMAIL", "Enter a valid email address, or leave it empty.");
        }
        Integer open = jdbc.queryForObject("""
                select count(*) from helpdesk_tickets where requester_type = 'GUEST' and requester_phone = ? and status in ('OPEN', 'IN_PROGRESS', 'WAITING')""",
                Integer.class, msisdn);
        if (open != null && open >= MAX_OPEN_PER_GUEST_PHONE) {
            throw new BusinessRuleException("TOO_MANY_OPEN", "This phone number already has " + open + " open requests. Follow one of them with its number and code.");
        }
        CategoryView c = allowedCategory(categoryId, "GUEST");
        List<Upload> uploads = HelpdeskFiles.read(files);
        String code = code();
        String ticketNo = nextNumber();
        Long id = insert(c, "GUEST", null, name.trim(), msisdn, mail, new String[]{ticketNo, code}, subject, body, uploads);
        predict(id, "GUEST", subject, body);
        Ticket t = load(id);
        notifier.received(t.facts(c.name()), code);
        notifier.newTicket(t.facts(c.name()));
        return new GuestCreated(ticketNo, code, requesterView(id));
    }

    @Transactional(readOnly = true)
    public TicketView guestOpen(String ticketNo, String code) {
        return requesterView(guest(ticketNo, code).id);
    }

    @Transactional
    public TicketView guestReply(String ticketNo, String code, String body, List<MultipartFile> files) {
        Ticket t = guest(ticketNo, code);
        reply(t, null, t.requesterName, body, HelpdeskFiles.read(files));
        return requesterView(t.id);
    }

    @Transactional
    public TicketView guestClose(String ticketNo, String code, Boolean satisfied) {
        Ticket t = guest(ticketNo, code);
        close(t, satisfied, t.requesterName);
        return requesterView(t.id);
    }

    @Transactional
    public TicketView guestAssistantFeedback(String ticketNo, String code, boolean helpful) {
        Ticket t = guest(ticketNo, code);
        assistantFeedback(t, helpful);
        return requesterView(t.id);
    }

    @Transactional(readOnly = true)
    public FileContent guestAttachment(String ticketNo, String code, Long attachmentId) {
        Ticket t = guest(ticketNo, code);
        if (!t.id.equals(ticketOfAttachment(attachmentId, false))) throw new NotFoundException("Attachment", attachmentId);
        return file(attachmentId);
    }

    private Ticket guest(String ticketNo, String code) {
        if (ticketNo == null || code == null) throw wrongCode();
        List<Ticket> r = jdbc.query(TICKET + " where t.ticket_no = ? and t.requester_type = 'GUEST'", TICKET_MAPPER, ticketNo.trim().toUpperCase());
        if (r.isEmpty() || !r.get(0).accessCodeHash.equals(hash(r.get(0).ticketNo, code.trim().toUpperCase()))) throw wrongCode();
        return r.get(0);
    }

    private static BusinessRuleException wrongCode() {
        return new BusinessRuleException("WRONG_CODE", "The request number or code is not right. Check the SMS we sent when you asked for help.");
    }

    // ================================================================= the helpdesk (staff)

    @Transactional(readOnly = true)
    public DeskPage desk(String view, String status, Long categoryId, String q, int page, int size) {
        Access a = access();
        List<Object> args = new ArrayList<>();
        String visible = visibleClause(a, args);
        StringBuilder where = new StringBuilder(" where ").append(visible);
        String v = view == null ? "open" : view;
        switch (v) {
            case "mine" -> { where.append(" and t.assignee_user_id = ? and t.status in ('OPEN', 'IN_PROGRESS', 'WAITING')"); args.add(a.userId); }
            case "unassigned" -> where.append(" and t.assignee_user_id is null and t.status in ('OPEN', 'IN_PROGRESS', 'WAITING')");
            case "overdue" -> where.append(" and t.status in ('OPEN', 'IN_PROGRESS', 'WAITING') and ").append(LATE);
            case "done" -> where.append(" and t.status in ('RESOLVED', 'CLOSED')");
            case "all" -> { }
            default -> where.append(" and t.status in ('OPEN', 'IN_PROGRESS', 'WAITING')");
        }
        if (status != null && !status.isBlank()) { where.append(" and t.status = ?"); args.add(status); }
        if (categoryId != null) { where.append(" and t.category_id = ?"); args.add(categoryId); }
        if (q != null && !q.isBlank()) {
            where.append(" and (t.ticket_no ilike ? or t.subject ilike ? or t.requester_name ilike ? or t.requester_phone like ?)");
            String like = "%" + q.trim() + "%";
            args.add(like); args.add(like); args.add(like); args.add("%" + q.trim().replaceAll("^0", "") + "%");
        }
        String order = "done".equals(v) || "all".equals(v) ? " order by t.updated_at desc"
                : " order by (" + LATE + ") desc, case t.priority when 'URGENT' then 0 when 'HIGH' then 1 else 2 end, t.resolution_due_at";
        Long total = jdbc.queryForObject("select count(*) from helpdesk_tickets t" + where, Long.class, args.toArray());
        int s = Math.min(Math.max(size, 1), 100);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add(Math.max(page, 0) * s);
        List<TicketRow> rows = jdbc.query(SELECT + where + order + " limit ? offset ?", ROW, pageArgs.toArray());
        return new DeskPage(rows, total == null ? 0 : total, counts(a));
    }

    /** How many tickets wait in each view, for the tabs and the menu badge. */
    @Transactional(readOnly = true)
    public Map<String, Long> counts() {
        return counts(access());
    }

    private Map<String, Long> counts(Access a) {
        List<Object> args = new ArrayList<>();
        String visible = visibleClause(a, args);
        args.add(a.userId);
        Map<String, Object> r = jdbc.queryForMap("""
                select count(*) filter (where t.status in ('OPEN', 'IN_PROGRESS', 'WAITING')) open,
                       count(*) filter (where t.status in ('OPEN', 'IN_PROGRESS', 'WAITING') and t.assignee_user_id = ?) mine,
                       count(*) filter (where t.status in ('OPEN', 'IN_PROGRESS', 'WAITING') and t.assignee_user_id is null) unassigned,
                       count(*) filter (where t.status in ('OPEN', 'IN_PROGRESS', 'WAITING') and %s) overdue
                from helpdesk_tickets t where %s""".formatted(LATE, visible), reorder(args));
        Map<String, Long> out = new LinkedHashMap<>();
        for (String k : List.of("open", "mine", "unassigned", "overdue")) out.put(k, ((Number) r.get(k)).longValue());
        return out;
    }

    /** The "mine" count's parameter comes first in the SQL, before the visibility ones. */
    private static Object[] reorder(List<Object> args) {
        List<Object> out = new ArrayList<>();
        out.add(args.get(args.size() - 1));
        out.addAll(args.subList(0, args.size() - 1));
        return out.toArray();
    }

    @Transactional(readOnly = true)
    public TicketView deskTicket(Long id) {
        Access a = access();
        Ticket t = load(id);
        if (!a.canSee(t)) throw new AccessDeniedException("Not your office's ticket");
        return staffView(t, a);
    }

    @Transactional
    public TicketView staffMessage(Long id, String body, boolean internal, String statusAfter, List<MultipartFile> files) {
        Access a = access();
        Ticket t = load(id);
        a.requireAct(t);
        if ("CLOSED".equals(t.status) && !internal) {
            throw new BusinessRuleException("TICKET_CLOSED", "This request is closed. Reopen it first, or add a note.");
        }
        String text = text(body);
        List<Upload> uploads = HelpdeskFiles.read(files);
        Long messageId = addMessage(t.id, "STAFF", a.userId, a.name, text, internal);
        store(messageId, uploads);
        if (!internal) {
            jdbc.update("update helpdesk_tickets set first_response_at = coalesce(first_response_at, now()), updated_at = now() where id = ?", t.id);
            if (t.assigneeUserId == null) {
                // whoever answers first takes the ticket
                jdbc.update("update helpdesk_tickets set assignee_user_id = ?, assignee_name = ? where id = ?", a.userId, a.name, t.id);
            }
        } else {
            jdbc.update("update helpdesk_tickets set updated_at = now() where id = ?", t.id);
        }
        boolean chosen = statusAfter != null && !statusAfter.isBlank();
        String next = chosen ? statusAfter
                : internal ? t.status
                : Set.of("OPEN", "WAITING").contains(t.status) ? "IN_PROGRESS" : t.status;
        if (!Set.of("OPEN", "IN_PROGRESS", "WAITING", "RESOLVED", "CLOSED").contains(next)) throw new BusinessRuleException("INVALID_STATUS", "Unknown status " + next);
        if (!next.equals(t.status)) changeStatus(load(t.id), next, a.name, false);
        audit.record(internal ? "HELPDESK_NOTE" : "HELPDESK_REPLY", "HelpdeskTicket", t.id, Map.of("ticketNo", t.ticketNo, "files", uploads.size(), "status", next));
        if (!internal) {
            Ticket now = load(t.id);
            if ("RESOLVED".equals(next)) notifier.resolved(now.facts(categoryName(now)));
            else notifier.replied(now.facts(categoryName(now)));
        }
        return staffView(load(t.id), a);
    }

    @Transactional
    public TicketView assign(Long id, Long userId) {
        Access a = access();
        Ticket t = load(id);
        a.requireAct(t);
        if (userId == null) {
            jdbc.update("update helpdesk_tickets set assignee_user_id = null, assignee_name = null, updated_at = now() where id = ?", id);
            addMessage(id, "SYSTEM", a.userId, a.name, "Unassigned by " + a.name, true);
        } else {
            Agent agent = agents(t).stream().filter(x -> x.id().equals(userId)).findFirst()
                    .orElseThrow(() -> new BusinessRuleException("NOT_AN_AGENT", "Assign the request to someone in the office that answers it."));
            jdbc.update("update helpdesk_tickets set assignee_user_id = ?, assignee_name = ?, updated_at = now() where id = ?", userId, agent.name(), id);
            addMessage(id, "SYSTEM", a.userId, a.name, userId.equals(a.userId) ? "Taken by " + a.name : "Assigned to " + agent.name() + " by " + a.name, true);
            if (!userId.equals(a.userId)) notifier.assigned(load(id).facts(categoryName(t)), userId, a.name);
        }
        audit.record("HELPDESK_ASSIGN", "HelpdeskTicket", id, Map.of("ticketNo", t.ticketNo, "assignee", userId == null ? "" : userId));
        return staffView(load(id), a);
    }

    @Transactional
    public TicketView route(Long id, Long categoryId) {
        Access a = access();
        Ticket t = load(id);
        a.requireAct(t);
        if (!OPEN.contains(t.status)) throw new BusinessRuleException("TICKET_DONE", "Only an open request can be moved to another office.");
        CategoryView to = categories.get(categoryId);
        if (!to.active()) throw new BusinessRuleException("CATEGORY_INACTIVE", "This category is no longer used.");
        if (to.id().equals(t.categoryId)) return staffView(t, a);
        String from = categoryName(t);
        boolean keepAssignee = t.assigneeUserId != null && hasRole(t.assigneeUserId, to.officeRole());
        jdbc.update("""
                update helpdesk_tickets set category_id = ?, office_role = ?, assignee_user_id = case when ? then assignee_user_id end,
                  assignee_name = case when ? then assignee_name end, updated_at = now() where id = ?""",
                to.id(), to.officeRole(), keepAssignee, keepAssignee, id);
        addMessage(id, "SYSTEM", a.userId, a.name, "Moved from " + from + " to " + to.name() + " by " + a.name, true);
        audit.record("HELPDESK_ROUTE", "HelpdeskTicket", id, Map.of("ticketNo", t.ticketNo, "from", from, "to", to.name()));
        Ticket moved = load(id);
        notifier.newTicket(moved.facts(to.name()));
        // someone who no longer sees it gets the list back
        return a.canSee(moved) ? staffView(moved, a) : null;
    }

    @Transactional
    public TicketView setStatus(Long id, String status) {
        Access a = access();
        Ticket t = load(id);
        a.requireAct(t);
        if (!Set.of("OPEN", "IN_PROGRESS", "WAITING", "RESOLVED", "CLOSED").contains(status)) {
            throw new BusinessRuleException("INVALID_STATUS", "Unknown status " + status);
        }
        if (status.equals(t.status)) return staffView(t, a);
        changeStatus(t, status, a.name, true);
        audit.record("HELPDESK_STATUS", "HelpdeskTicket", id, Map.of("ticketNo", t.ticketNo, "from", t.status, "to", status));
        if ("RESOLVED".equals(status)) notifier.resolved(load(id).facts(categoryName(t)));
        return staffView(load(id), a);
    }

    @Transactional
    public TicketView setPriority(Long id, String priority) {
        Access a = access();
        Ticket t = load(id);
        a.requireAct(t);
        if (!Set.of("NORMAL", "HIGH", "URGENT").contains(priority)) throw new BusinessRuleException("INVALID_PRIORITY", "Unknown priority " + priority);
        if (priority.equals(t.priority)) return staffView(t, a);
        jdbc.update("update helpdesk_tickets set priority = ?, updated_at = now() where id = ?", priority, id);
        addMessage(id, "SYSTEM", a.userId, a.name, "Priority changed from " + label(t.priority) + " to " + label(priority) + " by " + a.name, true);
        audit.record("HELPDESK_PRIORITY", "HelpdeskTicket", id, Map.of("ticketNo", t.ticketNo, "priority", priority));
        return staffView(load(id), a);
    }

    /** Staff who can take the ticket: those with its office's role, and the administrators. */
    @Transactional(readOnly = true)
    public List<Agent> agents(Long id) {
        Access a = access();
        Ticket t = load(id);
        if (!a.canSee(t)) throw new AccessDeniedException("Not your office's ticket");
        return agents(t);
    }

    private List<Agent> agents(Ticket t) {
        return jdbc.query("""
                select u.id, u.full_name, string_agg(distinct r.role, ',') from user_accounts u join user_roles r on r.user_id = u.id
                where u.user_type = 'STAFF' and u.status = 'ACTIVE' and r.role in (?, 'INSTITUTION_ADMIN')
                group by u.id, u.full_name order by u.full_name""",
                (rs, i) -> new Agent(rs.getLong(1), rs.getString(2), List.of(rs.getString(3).split(","))), t.officeRole);
    }

    @Transactional(readOnly = true)
    public FileContent staffAttachment(Long attachmentId) {
        Access a = access();
        Ticket t = load(ticketOfAttachment(attachmentId, true));
        if (!a.canSee(t)) throw new AccessDeniedException("Not your office's ticket");
        return file(attachmentId);
    }

    /** How well each category is answered over the last {@code days} days: received, resolved, replies and resolution in time, satisfaction. */
    @Transactional(readOnly = true)
    public Performance performance(int days) {
        Access a = access();
        List<Object> args = new ArrayList<>();
        String visible = visibleClause(a, args);
        int d = Math.min(Math.max(days, 1), 366);
        args.add(0, d);
        String sql = """
                select c.id, c.name,
                  count(t.id),
                  count(t.id) filter (where t.resolved_at is not null),
                  count(t.id) filter (where t.status in ('OPEN', 'IN_PROGRESS', 'WAITING')),
                  count(t.id) filter (where t.status in ('OPEN', 'IN_PROGRESS', 'WAITING') and %s),
                  avg(extract(epoch from (t.first_response_at - t.created_at)) / 3600.0) filter (where t.first_response_at is not null),
                  100.0 * count(t.id) filter (where t.first_response_at is not null and t.first_response_at <= t.response_due_at)
                        / nullif(count(t.id) filter (where t.first_response_at is not null or t.response_due_at < now()), 0),
                  100.0 * count(t.id) filter (where t.resolved_at is not null and t.resolved_at <= t.resolution_due_at)
                        / nullif(count(t.id) filter (where t.resolved_at is not null or (t.status <> 'WAITING' and t.resolution_due_at < now())), 0),
                  100.0 * count(t.id) filter (where t.satisfied) / nullif(count(t.id) filter (where t.satisfied is not null), 0)
                from helpdesk_categories c
                left join helpdesk_tickets t on t.category_id = c.id and t.created_at >= now() - make_interval(days => ?) and %s
                group by c.id, c.name, c.sort_order order by c.sort_order, c.name""".formatted(LATE, visible);
        RowMapper<CategoryStats> m = (rs, i) -> new CategoryStats(rs.getObject(1) == null ? null : rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), round(rs, 7), round(rs, 8), round(rs, 9), round(rs, 10));
        List<CategoryStats> per = jdbc.query(sql, m, args.toArray());
        CategoryStats overall = jdbc.queryForObject(sql.substring(0, sql.indexOf("from helpdesk_categories c")).replace("select c.id, c.name,", "select null, 'All',")
                + " from helpdesk_tickets t where t.created_at >= now() - make_interval(days => ?) and " + visible, m, args.toArray());
        Map<String, Object> st = jdbc.queryForMap("select (select count(*) from helpdesk_deflections where created_at >= now() - make_interval(days => ?)) solved,"
                + " count(*) filter (where t.suggestion_outcome is not null) instant,"
                + " count(*) filter (where t.suggestion_outcome = 'ACCEPTED') accepted,"
                + " count(*) filter (where t.suggestion_outcome = 'REJECTED') rejected,"
                + " count(*) filter (where t.predicted_category_id is not null) guessed,"
                + " count(*) filter (where t.predicted_category_id = t.category_id) rightly,"
                + " (select count(*) from helpdesk_learned_answers) learned"
                + " from helpdesk_tickets t where t.created_at >= now() - make_interval(days => ?) and " + visible, prepend(d, args));
        AssistantStats as = new AssistantStats(n(st, "solved"), n(st, "instant"), n(st, "accepted"), n(st, "rejected"), n(st, "guessed"),
                n(st, "rightly"), n(st, "learned"));
        return new Performance(d, overall, per, as);
    }

    /** The days for the deflections, then {@code args} (the days and the visibility arguments). */
    private static Object[] prepend(int days, List<Object> args) {
        List<Object> out = new ArrayList<>();
        out.add(days);
        out.addAll(args);
        return out.toArray();
    }

    private static long n(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).longValue();
    }

    private static Double round(ResultSet rs, int col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : Math.round(v * 10) / 10.0;
    }

    // ================================================================= the engine

    private Long insert(CategoryView c, String type, Long uid, String name, String phone, String email, String[] guestNoAndCode,
                        String subject, String body, List<Upload> uploads) {
        String s = subject == null ? "" : subject.trim().replaceAll("\\s+", " ");
        if (s.length() < 3 || s.length() > 200) throw new BusinessRuleException("SUBJECT_REQUIRED", "Write a short subject (3 to 200 characters).");
        String text = text(body);
        String ticketNo = guestNoAndCode != null ? guestNoAndCode[0] : nextNumber();
        String codeHash = guestNoAndCode != null ? hash(ticketNo, guestNoAndCode[1]) : null;
        OffsetDateTime now = OffsetDateTime.now();
        Long id = jdbc.queryForObject("""
                insert into helpdesk_tickets (tenant_id, ticket_no, category_id, office_role, subject, requester_type, requester_user_id, requester_name,
                  requester_phone, requester_email, access_code_hash, response_due_at, resolution_due_at)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                ticketNo, c.id(), c.officeRole(), s, type, uid, name, phone, email, codeHash,
                Timestamp.from(now.plusHours(c.responseHours()).toInstant()), Timestamp.from(now.plusHours(c.resolutionHours()).toInstant()));
        Long messageId = addMessage(id, "REQUESTER", uid, name, text, false);
        store(messageId, uploads);
        audit.recordAs(uid, uid == null ? name + " (help page)" : CurrentUser.name(), "HELPDESK_RAISED", "HelpdeskTicket", id,
                Map.of("ticketNo", ticketNo, "category", c.name(), "requesterType", type, "files", uploads.size()));
        return id;
    }

    private void reply(Ticket t, Long uid, String name, String body, List<Upload> uploads) {
        if ("CLOSED".equals(t.status)) throw new BusinessRuleException("TICKET_CLOSED", "This request is closed. Raise a new request if you still need help.");
        if ("RESOLVED".equals(t.status) && t.resolvedAt != null && t.resolvedAt.isBefore(OffsetDateTime.now().minusDays(REOPEN_DAYS))) {
            throw new BusinessRuleException("TICKET_CLOSED", "This request was resolved more than " + REOPEN_DAYS + " days ago. Raise a new request.");
        }
        Long messageId = addMessage(t.id, "REQUESTER", uid, name, text(body), false);
        store(messageId, uploads);
        if ("WAITING".equals(t.status)) {
            changeStatus(t, "IN_PROGRESS", name, false);
        } else if ("RESOLVED".equals(t.status)) {
            int hours = jdbc.queryForObject("select response_hours from helpdesk_categories where id = ?", Integer.class, t.categoryId);
            jdbc.update("""
                    update helpdesk_tickets set status = 'IN_PROGRESS', resolved_at = null,
                      resolution_due_at = greatest(resolution_due_at, now() + make_interval(hours => ?)),
                      escalation_level = least(escalation_level, 1), updated_at = now() where id = ?""", hours, t.id);
            addMessage(t.id, "SYSTEM", uid, name, "Reopened by " + name, false);
        } else {
            jdbc.update("update helpdesk_tickets set updated_at = now() where id = ?", t.id);
        }
        audit.recordAs(uid, uid == null ? name + " (help page)" : CurrentUser.name(), "HELPDESK_REQUESTER_REPLY", "HelpdeskTicket", t.id,
                Map.of("ticketNo", t.ticketNo, "files", uploads.size()));
        notifier.requesterReplied(load(t.id).facts(categoryName(t)));
    }

    private void close(Ticket t, Boolean satisfied, String name) {
        if ("CLOSED".equals(t.status)) return;
        jdbc.update("""
                update helpdesk_tickets set status = 'CLOSED', closed_at = now(), resolved_at = coalesce(resolved_at, now()), paused_at = null,
                  satisfied = ?, updated_at = now() where id = ?""", satisfied, t.id);
        addMessage(t.id, "SYSTEM", t.requesterUserId, name, satisfied == null ? "Closed by " + name
                : Boolean.TRUE.equals(satisfied) ? "Closed by " + name + ": the answer helped" : "Closed by " + name + ": the answer did not help", false);
        audit.recordAs(t.requesterUserId, t.requesterUserId == null ? name + " (help page)" : CurrentUser.name(), "HELPDESK_CLOSED", "HelpdeskTicket", t.id,
                Map.of("ticketNo", t.ticketNo, "satisfied", satisfied == null ? "" : satisfied));
        assistant.learn(t.id, satisfied);
    }

    // ---------------------------------------------------------------- the assistant on a request

    /** Records what the assistant predicts for a new request and, when one answer is a confident match, gives it at once. */
    private void predict(Long id, String requesterType, String subject, String body) {
        String reader = "GUEST".equals(requesterType) ? "PUBLIC" : requesterType;
        List<Long> allowed = categories.forAudience(requesterType).stream().map(CategoryView::id).toList();
        var sg = assistant.suggest(reader, subject, body, allowed);
        var instant = assistant.instantAnswer(reader, subject, body);
        var top = instant != null ? instant : sg.answers().isEmpty() ? null : sg.answers().get(0);
        jdbc.update("update helpdesk_tickets set predicted_category_id = ?, category_confidence = ?, suggested_article_id = ?, suggestion_score = ?,"
                        + " suggestion_outcome = ? where id = ?",
                sg.category() == null ? null : sg.category().id(), sg.category() == null ? null : sg.category().confidence(),
                top == null ? null : top.id(), top == null ? null : top.confidence(), instant == null ? null : "INSTANT", id);
        if (instant != null) {
            jdbc.update("insert into helpdesk_messages (tenant_id, ticket_id, author_type, author_user_id, author_name, body, internal, article_id)"
                    + " values (cims_current_tenant(), ?, 'ASSISTANT', null, 'CIMS assistant', ?, false, ?)", id, instant.title() + "\n\n" + instant.body(), instant.id());
        }
    }

    private void assistantFeedback(Ticket t, boolean helpful) {
        Map<String, Object> r = jdbc.queryForMap("select suggestion_outcome, suggested_article_id from helpdesk_tickets where id = ?", t.id);
        if (!"INSTANT".equals(r.get("suggestion_outcome"))) {
            throw new BusinessRuleException("NO_SUGGESTION", "There is no suggested answer waiting for your reply on this request.");
        }
        Object article = r.get("suggested_article_id");
        jdbc.update("update helpdesk_tickets set suggestion_outcome = ?, updated_at = now() where id = ?", helpful ? "ACCEPTED" : "REJECTED", t.id);
        if (article != null) {
            jdbc.update(helpful ? "update helpdesk_articles set helpful = helpful + 1 where id = ?"
                    : "update helpdesk_articles set not_helpful = not_helpful + 1 where id = ?", article);
        }
        if (helpful) {
            addMessage(t.id, "SYSTEM", t.requesterUserId, t.requesterName, "Solved by the suggested answer", false);
            close(load(t.id), true, t.requesterName);
        } else {
            addMessage(t.id, "SYSTEM", t.requesterUserId, t.requesterName, "The suggested answer did not solve it; the office will reply", false);
            jdbc.update("update helpdesk_tickets set updated_at = now() where id = ?", t.id);
        }
    }

    /** Answers likely to solve what someone is writing (APPLICANT, STUDENT, STAFF or GUEST), and the category guessed among theirs. */
    @Transactional
    public tz.co.msingi.cims.helpdesk.assistant.HelpdeskAssistant.Suggestion suggestFor(String requesterType, String subject, String body) {
        String s = subject == null ? "" : subject;
        String b = body == null ? "" : body;
        if (s.length() + b.length() > 6000) b = b.substring(0, Math.max(0, 6000 - s.length()));
        List<Long> allowed = categories.forAudience(requesterType).stream().map(CategoryView::id).toList();
        return assistant.suggest("GUEST".equals(requesterType) ? "PUBLIC" : requesterType, s, b, allowed);
    }

    /** A suggested answer solved the question before a request was raised: counted, and the answer marked helpful. */
    @Transactional
    public void solvedBeforeAsking(String requesterType, Long articleId) {
        String reader = "GUEST".equals(requesterType) ? "PUBLIC" : requesterType;
        String column = switch (reader) { case "APPLICANT" -> "for_applicants"; case "STUDENT" -> "for_students"; case "STAFF" -> "for_staff"; default -> "for_public"; };
        Integer ok = jdbc.queryForObject("select count(*) from helpdesk_articles where id = ? and published and " + column, Integer.class, articleId);
        if (ok == null || ok == 0) throw new NotFoundException("Article", articleId);
        jdbc.update("update helpdesk_articles set helpful = helpful + 1 where id = ?", articleId);
        jdbc.update("insert into helpdesk_deflections (tenant_id, article_id, audience) values (cims_current_tenant(), ?, ?)", articleId, reader);
    }

    /** Frequent questions of the last {@code days} days with no answer yet, in the categories the person keeps answers for. */
    @Transactional(readOnly = true)
    public List<tz.co.msingi.cims.helpdesk.assistant.HelpdeskAssistant.Gap> gaps(int days) {
        Access a = access();
        List<Long> mine = categories.all().stream().filter(c -> a.admin() || a.roles.contains(c.officeRole())).map(CategoryView::id).toList();
        return assistant.gaps(Math.min(Math.max(days, 7), 365), mine);
    }

    /** Staff used a past reply the assistant suggested (it then ranks a little higher). */
    @Transactional
    public void usedSuggestion(Long learnedId) {
        access();
        assistant.used(learnedId);
    }

    /** Suggested replies for staff on a request, and the category it seems to belong to. */
    @Transactional(readOnly = true)
    public tz.co.msingi.cims.helpdesk.assistant.HelpdeskAssistant.StaffSuggestions suggestions(Long id) {
        Access a = access();
        Ticket t = load(id);
        if (!a.canSee(t)) throw new AccessDeniedException("Not your office's ticket");
        String text = t.subject + "\n" + String.join("\n", jdbc.queryForList(
                "select body from helpdesk_messages where ticket_id = ? and author_type = 'REQUESTER' order by created_at, id", String.class, id));
        List<Long> active = categories.all().stream().filter(CategoryView::active).map(CategoryView::id).toList();
        var out = assistant.forStaff(id, text, active);
        // the answer the requester already said did not solve it is not offered again
        Long rejected = jdbc.queryForObject("select case when suggestion_outcome = 'REJECTED' then suggested_article_id end from helpdesk_tickets where id = ?", Long.class, id);
        if (rejected == null) return out;
        return new tz.co.msingi.cims.helpdesk.assistant.HelpdeskAssistant.StaffSuggestions(
                out.replies().stream().filter(r -> !("ANSWER".equals(r.source()) && rejected.equals(r.sourceId()))).toList(), out.category());
    }

    /** Moves a ticket to another status, keeping the resolution clock stopped while it waits for the requester. */
    private void changeStatus(Ticket t, String to, String byName, boolean say) {
        // leaving WAITING: the time spent waiting is added to the resolution target
        jdbc.update("""
                update helpdesk_tickets set resolution_due_at = case when paused_at is not null then resolution_due_at + (now() - paused_at) else resolution_due_at end,
                  paused_at = null where id = ?""", t.id);
        switch (to) {
            case "WAITING" -> jdbc.update("update helpdesk_tickets set status = 'WAITING', paused_at = now(), updated_at = now() where id = ?", t.id);
            case "RESOLVED" -> jdbc.update("update helpdesk_tickets set status = 'RESOLVED', resolved_at = now(), updated_at = now() where id = ?", t.id);
            case "CLOSED" -> jdbc.update("update helpdesk_tickets set status = 'CLOSED', closed_at = now(), resolved_at = coalesce(resolved_at, now()), updated_at = now() where id = ?", t.id);
            default -> jdbc.update("update helpdesk_tickets set status = ?, resolved_at = null, closed_at = null, updated_at = now() where id = ?", to, t.id);
        }
        // the requester sees when it is resolved, closed or reopened; the other moves are for staff
        boolean forRequester = Set.of("RESOLVED", "CLOSED").contains(to) || Set.of("RESOLVED", "CLOSED").contains(t.status);
        if (say || forRequester) {
            String text = switch (to) {
                case "RESOLVED" -> "Marked resolved by " + byName;
                case "CLOSED" -> "Closed by " + byName;
                case "WAITING" -> "Waiting for the requester (set by " + byName + ")";
                default -> Set.of("RESOLVED", "CLOSED").contains(t.status) ? "Reopened by " + byName : "Status changed to " + label(to) + " by " + byName;
            };
            addMessage(t.id, "SYSTEM", null, byName, text, !forRequester);
        }
    }

    Long addMessage(Long ticketId, String authorType, Long uid, String name, String body, boolean internal) {
        return jdbc.queryForObject("""
                insert into helpdesk_messages (tenant_id, ticket_id, author_type, author_user_id, author_name, body, internal)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?) returning id""", Long.class, ticketId, authorType, uid, name, body, internal);
    }

    private void store(Long messageId, List<Upload> uploads) {
        for (Upload u : uploads) {
            jdbc.update("""
                    insert into helpdesk_attachments (tenant_id, message_id, file_name, content_type, size_bytes, sha256, data)
                    values (cims_current_tenant(), ?, ?, ?, ?, ?, ?)""", messageId, u.fileName(), u.contentType(), u.data().length, u.sha256(), u.data());
        }
    }

    private CategoryView allowedCategory(Long id, String requesterType) {
        return categories.forAudience(requesterType).stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessRuleException("INVALID_CATEGORY", "Choose what your request is about."));
    }

    private String nextNumber() {
        int year = Year.now(ZoneId.of("Africa/Dar_es_Salaam")).getValue();
        return NumberSequenceService.format("HD-" + year + "-", numbers.next("HELPDESK-" + year), 5);
    }

    private static String code() {
        StringBuilder b = new StringBuilder(8);
        for (int i = 0; i < 8; i++) b.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        return b.toString();
    }

    private static String hash(String ticketNo, String code) {
        return HelpdeskFiles.sha256(ticketNo + ":" + code);
    }

    private static String text(String body) {
        String t = body == null ? "" : body.trim();
        if (t.isEmpty()) throw new BusinessRuleException("MESSAGE_REQUIRED", "Write your message.");
        if (t.length() > 5000) throw new BusinessRuleException("MESSAGE_TOO_LONG", "Keep the message under 5,000 characters.");
        return t;
    }

    static String label(String code) {
        return switch (code) {
            case "IN_PROGRESS" -> "In progress";
            case "WAITING" -> "Waiting for requester";
            default -> code.charAt(0) + code.substring(1).toLowerCase();
        };
    }

    private boolean hasRole(Long userId, String role) {
        Integer n = jdbc.queryForObject("select count(*) from user_roles where user_id = ? and role in (?, 'INSTITUTION_ADMIN')", Integer.class, userId, role);
        return n != null && n > 0;
    }

    private String categoryName(Ticket t) {
        return jdbc.queryForObject("select name from helpdesk_categories where id = ?", String.class, t.categoryId);
    }

    private Long ticketOfAttachment(Long attachmentId, boolean staff) {
        List<Map<String, Object>> r = jdbc.queryForList("""
                select m.ticket_id, m.internal from helpdesk_attachments a join helpdesk_messages m on m.id = a.message_id where a.id = ?""", attachmentId);
        if (r.isEmpty() || (!staff && Boolean.TRUE.equals(r.get(0).get("internal")))) throw new NotFoundException("Attachment", attachmentId);
        return ((Number) r.get(0).get("ticket_id")).longValue();
    }

    private FileContent file(Long attachmentId) {
        return jdbc.queryForObject("select file_name, content_type, data from helpdesk_attachments where id = ?",
                (rs, i) -> new FileContent(rs.getString(1), rs.getString(2), rs.getBytes(3)), attachmentId);
    }

    private Ticket own(Long id) {
        Ticket t = load(id);
        if (t.requesterUserId == null || !t.requesterUserId.equals(CurrentUser.requireId())) throw new NotFoundException("Request", id);
        return t;
    }

    // ---------------------------------------------------------------- views

    private TicketView requesterView(Long id) {
        Ticket t = load(id);
        TicketRow row = row(id);
        boolean canReply = OPEN.contains(t.status) || ("RESOLVED".equals(t.status) && t.resolvedAt != null
                && t.resolvedAt.isAfter(OffsetDateTime.now().minusDays(REOPEN_DAYS)));
        return new TicketView(row, null, null, messages(id, false), canReply, !"CLOSED".equals(t.status), false, null, null, outcome(id));
    }

    private TicketView staffView(Ticket t, Access a) {
        TicketRow row = row(t.id);
        String msingiNo = t.msingiTicketId == null ? null
                : jdbc.queryForList("select ticket_no from support_tickets where id = ?", String.class, t.msingiTicketId).stream().findFirst().orElse(null);
        boolean act = a.canAct(t);
        return new TicketView(row, t.requesterPhone, t.requesterEmail, messages(t.id, true), act, false, act, msingiNo, t.msingiTicketId, outcome(t.id));
    }

    private String outcome(Long id) {
        return jdbc.queryForObject("select suggestion_outcome from helpdesk_tickets where id = ?", String.class, id);
    }

    private TicketRow row(Long id) {
        return jdbc.queryForObject(SELECT + " where t.id = ?", ROW, id);
    }

    private List<MessageView> messages(Long ticketId, boolean staff) {
        Map<Long, List<AttachmentView>> files = jdbc.query("""
                select a.message_id, a.id, a.file_name, a.content_type, a.size_bytes from helpdesk_attachments a
                join helpdesk_messages m on m.id = a.message_id where m.ticket_id = ? order by a.id""",
                (rs, i) -> Map.entry(rs.getLong(1), new AttachmentView(rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5))), ticketId)
                .stream().collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return jdbc.query("select id, author_type, author_name, body, internal, created_at from helpdesk_messages where ticket_id = ?"
                        + (staff ? "" : " and not internal") + " order by created_at, id",
                (rs, i) -> new MessageView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                        rs.getObject(6, OffsetDateTime.class), files.getOrDefault(rs.getLong(1), List.of())), ticketId);
    }

    // ---------------------------------------------------------------- who is asking at the helpdesk

    record Access(Long userId, String name, Set<String> roles) {
        boolean admin() { return roles.contains("INSTITUTION_ADMIN"); }
        boolean reader() { return roles.stream().anyMatch(READERS::contains); }
        boolean canSee(Ticket t) { return admin() || reader() || roles.contains(t.officeRole) || userId.equals(t.assigneeUserId); }
        boolean canAct(Ticket t) { return admin() || roles.contains(t.officeRole) || userId.equals(t.assigneeUserId); }
        void requireAct(Ticket t) {
            if (!canAct(t)) throw new AccessDeniedException("Only the office that answers this request can change it");
        }
    }

    private Access access() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> roles = auth == null ? Set.of() : auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(r -> r.startsWith("ROLE_")).map(r -> r.substring(5)).collect(Collectors.toSet());
        Long uid = CurrentUser.requireId();
        String name = jdbc.queryForObject("select full_name from user_accounts where id = ?", String.class, uid);
        return new Access(uid, name, roles);
    }

    private static String visibleClause(Access a, List<Object> args) {
        if (a.admin() || a.reader()) return "true";
        List<String> offices = a.roles.stream().filter(HelpdeskCategoryService.OFFICES::contains).toList();
        args.addAll(offices);
        args.add(a.userId);
        String in = offices.isEmpty() ? "false" : "t.office_role in (" + String.join(", ", offices.stream().map(x -> "?").toList()) + ")";
        return "(" + in + " or t.assignee_user_id = ?)";
    }

    // ---------------------------------------------------------------- rows

    static final String LATE = "((t.first_response_at is null and t.response_due_at < now()) or (t.status <> 'WAITING' and t.resolution_due_at < now()))";

    private static final String SELECT = """
            select t.id, t.ticket_no, t.subject, t.category_id, c.name, t.office_role, t.status, t.priority, t.requester_type, t.requester_name,
                   t.assignee_user_id, t.assignee_name, t.response_due_at, t.resolution_due_at, t.first_response_at, t.resolved_at, t.closed_at,
                   case when t.status in ('OPEN', 'IN_PROGRESS', 'WAITING') then
                        case when t.first_response_at is null and t.response_due_at < now() then 'RESPONSE'
                             when t.status <> 'WAITING' and t.resolution_due_at < now() then 'RESOLUTION' end end,
                   t.escalation_level, t.satisfied, t.created_at, t.updated_at,
                   (select count(*) from helpdesk_messages m where m.ticket_id = t.id and not m.internal and m.author_type <> 'SYSTEM')
            from helpdesk_tickets t join helpdesk_categories c on c.id = t.category_id""";

    private static final RowMapper<TicketRow> ROW = (rs, i) -> new TicketRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
            rs.getObject(11) == null ? null : rs.getLong(11), rs.getString(12), rs.getObject(13, OffsetDateTime.class),
            rs.getObject(14, OffsetDateTime.class), rs.getObject(15, OffsetDateTime.class), rs.getObject(16, OffsetDateTime.class),
            rs.getObject(17, OffsetDateTime.class), rs.getString(18), rs.getInt(19), (Boolean) rs.getObject(20),
            rs.getObject(21, OffsetDateTime.class), rs.getObject(22, OffsetDateTime.class), rs.getInt(23));

    /** A ticket as the engine needs it. */
    static final class Ticket {
        Long id; String ticketNo; Long categoryId; String officeRole; String subject; String status; String priority; String requesterType;
        Long requesterUserId; String requesterName; String requesterPhone; String requesterEmail; String accessCodeHash; Long assigneeUserId;
        OffsetDateTime responseDueAt; OffsetDateTime resolvedAt; Long msingiTicketId;

        HelpdeskNotifier.Ticket facts(String categoryName) {
            return new HelpdeskNotifier.Ticket(id, ticketNo, subject, requesterType, requesterUserId, requesterName, requesterPhone, requesterEmail,
                    officeRole, assigneeUserId, categoryName, responseDueAt);
        }
    }

    static final String TICKET = """
            select t.id, t.ticket_no, t.category_id, t.office_role, t.subject, t.status, t.priority, t.requester_type, t.requester_user_id,
                   t.requester_name, t.requester_phone, t.requester_email, t.access_code_hash, t.assignee_user_id, t.response_due_at, t.resolved_at,
                   t.msingi_ticket_id
            from helpdesk_tickets t""";

    static final RowMapper<Ticket> TICKET_MAPPER = (rs, i) -> {
        Ticket t = new Ticket();
        t.id = rs.getLong(1); t.ticketNo = rs.getString(2); t.categoryId = rs.getLong(3); t.officeRole = rs.getString(4); t.subject = rs.getString(5);
        t.status = rs.getString(6); t.priority = rs.getString(7); t.requesterType = rs.getString(8);
        t.requesterUserId = rs.getObject(9) == null ? null : rs.getLong(9); t.requesterName = rs.getString(10); t.requesterPhone = rs.getString(11);
        t.requesterEmail = rs.getString(12); t.accessCodeHash = rs.getString(13); t.assigneeUserId = rs.getObject(14) == null ? null : rs.getLong(14);
        t.responseDueAt = rs.getObject(15, OffsetDateTime.class); t.resolvedAt = rs.getObject(16, OffsetDateTime.class);
        t.msingiTicketId = rs.getObject(17) == null ? null : rs.getLong(17);
        return t;
    };

    Ticket load(Long id) {
        List<Ticket> r = jdbc.query(TICKET + " where t.id = ?", TICKET_MAPPER, id);
        if (r.isEmpty()) throw new NotFoundException("Request", id);
        return r.get(0);
    }

    /** Used by the Msingi support link: records that the ticket was escalated. */
    void linkMsingi(Long ticketId, Long supportTicketId, String supportNo, String byName) {
        jdbc.update("update helpdesk_tickets set msingi_ticket_id = ?, updated_at = now() where id = ?", supportTicketId, ticketId);
        addMessage(ticketId, "SYSTEM", null, byName, "Escalated to Msingi Technologies as " + supportNo + " by " + byName, true);
    }

    static Duration since(OffsetDateTime t) {
        return Duration.between(t, OffsetDateTime.now());
    }
}
