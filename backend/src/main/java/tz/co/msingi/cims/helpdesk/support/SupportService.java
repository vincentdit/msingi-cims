package tz.co.msingi.cims.helpdesk.support;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.console.audit.PlatformAuditService;
import tz.co.msingi.cims.helpdesk.HelpdeskFiles;
import tz.co.msingi.cims.helpdesk.HelpdeskFiles.AttachmentView;
import tz.co.msingi.cims.helpdesk.HelpdeskFiles.FileContent;
import tz.co.msingi.cims.helpdesk.HelpdeskFiles.Upload;
import tz.co.msingi.cims.notification.email.EmailMessage;
import tz.co.msingi.cims.notification.email.EmailService;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.platform.tenancy.TenantWork;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Support from Msingi Technologies (FR-HLP-09). An institution's administrators raise tickets to Msingi, or escalate one of their
 * own helpdesk tickets; Msingi staff answer them in the platform console, with notes the institution does not see. Targets
 * depend on the priority: urgent 1 hour to a first reply and 1 day to resolve, high 4 hours and 2 days, normal 8 hours and
 * 5 days. Both sides are told by email; overdue tickets are escalated to all Msingi staff.
 */
@Service
@RequiredArgsConstructor
public class SupportService {

    public static final Set<String> CATEGORIES = Set.of("ACCOUNT_ACCESS", "PROBLEM", "INTEGRATION", "DATA_CHANGE", "TRAINING", "NEW_FEATURE", "BILLING", "OTHER");
    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "WAITING", "RESOLVED", "CLOSED");
    private static final int REOPEN_DAYS = 7;
    private static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final PlatformAuditService platformAudit;
    private final EmailService email;
    private final TenantRepository tenants;
    private final TenantWork tenantWork;

    public record SupportRow(Long id, String ticketNo, Long tenantId, String institutionCode, String institutionName, String category, String subject,
                             String status, String priority, String raisedByName, Long assigneeId, String assigneeName, OffsetDateTime responseDueAt,
                             OffsetDateTime resolutionDueAt, OffsetDateTime firstResponseAt, OffsetDateTime resolvedAt, String late,
                             Boolean satisfied, String helpdeskTicketNo, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record SupportMessage(Long id, String authorSide, String authorName, String body, boolean internal, OffsetDateTime createdAt,
                                 List<AttachmentView> attachments) {
    }

    public record SupportView(SupportRow ticket, String raisedByEmail, String raisedByPhone, List<SupportMessage> messages,
                              boolean canReply, boolean canClose) {
    }

    public record SupportPage(List<SupportRow> rows, long total, Map<String, Long> counts) {
    }

    public record Agent(Long id, String name) {
    }

    // ================================================================= the institution

    @Transactional(readOnly = true)
    public List<SupportRow> institutionTickets() {
        return jdbc.query(SELECT + " where s.tenant_id = cims_current_tenant() order by s.updated_at desc", ROW);
    }

    @Transactional
    public SupportView raise(String category, String priority, String subject, String body, List<MultipartFile> files) {
        Long id = create(category, priority, subject, body, HelpdeskFiles.read(files), null);
        return institutionView(id);
    }

    /** Sends one of the institution's own helpdesk tickets on to Msingi, with a note from the administrator. */
    @Transactional
    public SupportView escalate(Long helpdeskTicketId, String category, String priority, String note) {
        Map<String, Object> h = jdbc.queryForList("select ticket_no, subject, requester_name, msingi_ticket_id from helpdesk_tickets where id = ?", helpdeskTicketId)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Request", helpdeskTicketId));
        if (h.get("msingi_ticket_id") != null) throw new BusinessRuleException("ALREADY_ESCALATED", "This request has already been sent to Msingi.");
        String ticketNo = (String) h.get("ticket_no");
        String body = (note == null ? "" : note.trim()) + "\n\n— From helpdesk request " + ticketNo + " (" + h.get("requester_name") + "): " + h.get("subject");
        Long id = create(category, priority, "[" + ticketNo + "] " + h.get("subject"), body.trim(), List.of(), helpdeskTicketId);
        String supportNo = jdbc.queryForObject("select ticket_no from support_tickets where id = ?", String.class, id);
        String by = staffName();
        jdbc.update("update helpdesk_tickets set msingi_ticket_id = ?, updated_at = now() where id = ?", id, helpdeskTicketId);
        jdbc.update("""
                insert into helpdesk_messages (tenant_id, ticket_id, author_type, author_user_id, author_name, body, internal)
                values (cims_current_tenant(), ?, 'SYSTEM', ?, ?, ?, true)""", helpdeskTicketId, CurrentUser.requireId(), by,
                "Escalated to Msingi Technologies as " + supportNo + " by " + by);
        return institutionView(id);
    }

    @Transactional(readOnly = true)
    public SupportView institutionTicket(Long id) {
        requireOwn(id);
        return institutionView(id);
    }

    @Transactional
    public SupportView institutionReply(Long id, String body, List<MultipartFile> files) {
        Row s = requireOwn(id);
        if ("CLOSED".equals(s.status)) throw new BusinessRuleException("TICKET_CLOSED", "This ticket is closed. Raise a new one if you still need help.");
        if ("RESOLVED".equals(s.status) && s.resolvedAt != null && s.resolvedAt.isBefore(OffsetDateTime.now().minusDays(REOPEN_DAYS))) {
            throw new BusinessRuleException("TICKET_CLOSED", "This ticket was resolved more than " + REOPEN_DAYS + " days ago. Raise a new one.");
        }
        String by = staffName();
        Long m = message(s.id, s.tenantId, "INSTITUTION", by, text(body), false);
        store(m, s.tenantId, HelpdeskFiles.read(files));
        if ("WAITING".equals(s.status)) status(s, "IN_PROGRESS", by, false);
        else if ("RESOLVED".equals(s.status)) {
            jdbc.update("""
                    update support_tickets set status = 'IN_PROGRESS', resolved_at = null, escalation_level = least(escalation_level, 1),
                      resolution_due_at = greatest(resolution_due_at, now() + make_interval(hours => ?)), updated_at = now() where id = ?""",
                    targets(s.priority)[0], s.id);
            message(s.id, s.tenantId, "SYSTEM", by, "Reopened by " + by, false);
        } else {
            jdbc.update("update support_tickets set updated_at = now() where id = ?", s.id);
        }
        audit.record("MSINGI_SUPPORT_REPLY", "SupportTicket", s.id, Map.of("ticketNo", s.ticketNo));
        toMsingi(s.tenantId, s.assigneeId == null ? null : List.of(s.assigneeId), (s.ticketNo + ": reply from " + institutionName(s.tenantId)),
                by + " (" + institutionName(s.tenantId) + ") replied on " + s.ticketNo + " — " + s.subject + ".");
        return institutionView(id);
    }

    @Transactional
    public SupportView institutionClose(Long id, Boolean satisfied) {
        Row s = requireOwn(id);
        if (!"CLOSED".equals(s.status)) {
            String by = staffName();
            jdbc.update("""
                    update support_tickets set status = 'CLOSED', closed_at = now(), resolved_at = coalesce(resolved_at, now()), paused_at = null,
                      satisfied = ?, updated_at = now() where id = ?""", satisfied, id);
            message(id, s.tenantId, "SYSTEM", by, satisfied == null ? "Closed by " + by
                    : Boolean.TRUE.equals(satisfied) ? "Closed by " + by + ": the answer helped" : "Closed by " + by + ": the answer did not help", false);
            audit.record("MSINGI_SUPPORT_CLOSED", "SupportTicket", id, Map.of("ticketNo", s.ticketNo));
        }
        return institutionView(id);
    }

    @Transactional(readOnly = true)
    public FileContent institutionAttachment(Long attachmentId) {
        Map<String, Object> a = attachmentTicket(attachmentId);
        if (Boolean.TRUE.equals(a.get("internal"))) throw new NotFoundException("Attachment", attachmentId);
        requireOwn(((Number) a.get("ticket_id")).longValue());
        return file(attachmentId);
    }

    private Long create(String category, String priority, String subject, String body, List<Upload> uploads, Long helpdeskTicketId) {
        if (!CATEGORIES.contains(category)) throw new BusinessRuleException("INVALID_CATEGORY", "Choose what the ticket is about.");
        String p = priority == null || priority.isBlank() ? "NORMAL" : priority;
        if (!Set.of("NORMAL", "HIGH", "URGENT").contains(p)) throw new BusinessRuleException("INVALID_PRIORITY", "Unknown priority " + p);
        String s = subject == null ? "" : subject.trim().replaceAll("\\s+", " ");
        if (s.length() < 3 || s.length() > 200) throw new BusinessRuleException("SUBJECT_REQUIRED", "Write a short subject (3 to 200 characters).");
        Long uid = CurrentUser.requireId();
        Map<String, Object> u = jdbc.queryForMap("select full_name, email, phone from user_accounts where id = ?", uid);
        long seq = jdbc.queryForObject("select nextval('support_ticket_seq')", Long.class);
        String no = "MS-" + Year.now(TZ).getValue() + "-" + String.format("%05d", seq);
        int[] t = targets(p);
        OffsetDateTime now = OffsetDateTime.now();
        Long tenantId = TenantContext.requireTenantId();
        Long id = jdbc.queryForObject("""
                insert into support_tickets (tenant_id, ticket_no, category, subject, priority, raised_by_user_id, raised_by_name, raised_by_email,
                  raised_by_phone, response_due_at, resolution_due_at, helpdesk_ticket_id)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                no, category, s, p, uid, u.get("full_name"), u.get("email"), u.get("phone"),
                Timestamp.from(now.plusHours(t[0]).toInstant()), Timestamp.from(now.plusHours(t[1]).toInstant()), helpdeskTicketId);
        Long m = message(id, tenantId, "INSTITUTION", (String) u.get("full_name"), text(body), false);
        store(m, tenantId, uploads);
        audit.record("MSINGI_SUPPORT_RAISED", "SupportTicket", id, Map.of("ticketNo", no, "category", category, "priority", p));
        String inst = institutionName(tenantId);
        toMsingi(tenantId, null, "New support ticket " + no + " from " + inst + " (" + label(p) + ")",
                u.get("full_name") + " (" + inst + ") raised " + no + " — " + label(category) + ": " + s
                        + ".\nA first reply is due by " + when(now.plusHours(t[0])) + ". Open it under Support in the platform console.");
        return id;
    }

    private Row requireOwn(Long id) {
        Row s = load(id);
        if (!s.tenantId.equals(TenantContext.requireTenantId())) throw new NotFoundException("Support ticket", id);
        return s;
    }

    private SupportView institutionView(Long id) {
        Row s = load(id);
        boolean canReply = !"CLOSED".equals(s.status) && !("RESOLVED".equals(s.status) && s.resolvedAt != null
                && s.resolvedAt.isBefore(OffsetDateTime.now().minusDays(REOPEN_DAYS)));
        return new SupportView(row(id), null, null, messages(id, false), canReply, !"CLOSED".equals(s.status));
    }

    // ================================================================= Msingi (platform console)

    @Transactional(readOnly = true)
    public SupportPage platformTickets(String view, Long tenantId, String q, int page, int size) {
        Long me = CurrentUser.platformId().orElseThrow();
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where true");
        String v = view == null ? "open" : view;
        switch (v) {
            case "mine" -> { where.append(" and s.assignee_platform_user_id = ? and s.status in ('OPEN', 'IN_PROGRESS', 'WAITING')"); args.add(me); }
            case "unassigned" -> where.append(" and s.assignee_platform_user_id is null and s.status in ('OPEN', 'IN_PROGRESS', 'WAITING')");
            case "overdue" -> where.append(" and s.status in ('OPEN', 'IN_PROGRESS', 'WAITING') and ").append(LATE);
            case "done" -> where.append(" and s.status in ('RESOLVED', 'CLOSED')");
            case "all" -> { }
            default -> where.append(" and s.status in ('OPEN', 'IN_PROGRESS', 'WAITING')");
        }
        if (tenantId != null) { where.append(" and s.tenant_id = ?"); args.add(tenantId); }
        if (q != null && !q.isBlank()) {
            where.append(" and (s.ticket_no ilike ? or s.subject ilike ? or s.raised_by_name ilike ?)");
            String like = "%" + q.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        String order = "done".equals(v) || "all".equals(v) ? " order by s.updated_at desc"
                : " order by (" + LATE + ") desc, case s.priority when 'URGENT' then 0 when 'HIGH' then 1 else 2 end, s.resolution_due_at";
        Long total = jdbc.queryForObject("select count(*) from support_tickets s" + where, Long.class, args.toArray());
        int sz = Math.min(Math.max(size, 1), 100);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(sz);
        pageArgs.add(Math.max(page, 0) * sz);
        return new SupportPage(jdbc.query(SELECT + where + order + " limit ? offset ?", ROW, pageArgs.toArray()), total == null ? 0 : total, platformCounts());
    }

    @Transactional(readOnly = true)
    public Map<String, Long> platformCounts() {
        Long me = CurrentUser.platformId().orElse(-1L);
        Map<String, Object> r = jdbc.queryForMap("""
                select count(*) filter (where status in ('OPEN', 'IN_PROGRESS', 'WAITING')) open,
                       count(*) filter (where status in ('OPEN', 'IN_PROGRESS', 'WAITING') and assignee_platform_user_id = ?) mine,
                       count(*) filter (where status in ('OPEN', 'IN_PROGRESS', 'WAITING') and assignee_platform_user_id is null) unassigned,
                       count(*) filter (where status in ('OPEN', 'IN_PROGRESS', 'WAITING') and %s) overdue
                from support_tickets s""".formatted(LATE), me);
        Map<String, Long> out = new LinkedHashMap<>();
        for (String k : List.of("open", "mine", "unassigned", "overdue")) out.put(k, ((Number) r.get(k)).longValue());
        return out;
    }

    @Transactional(readOnly = true)
    public SupportView platformTicket(Long id) {
        return platformView(id);
    }

    @Transactional
    public SupportView platformMessage(Long id, String body, boolean internal, String statusAfter, List<MultipartFile> files) {
        Row s = load(id);
        if ("CLOSED".equals(s.status) && !internal) throw new BusinessRuleException("TICKET_CLOSED", "This ticket is closed. Reopen it first, or add a note.");
        String by = CurrentUser.platformName();
        Long me = CurrentUser.platformId().orElseThrow();
        String name = platformUserName(me);
        Long m = message(id, s.tenantId, "MSINGI", name, text(body), internal);
        store(m, s.tenantId, HelpdeskFiles.read(files));
        if (!internal) {
            jdbc.update("update support_tickets set first_response_at = coalesce(first_response_at, now()), updated_at = now() where id = ?", id);
            if (s.assigneeId == null) jdbc.update("update support_tickets set assignee_platform_user_id = ?, assignee_name = ? where id = ?", me, name, id);
        } else {
            jdbc.update("update support_tickets set updated_at = now() where id = ?", id);
        }
        boolean chosen = statusAfter != null && !statusAfter.isBlank();
        String next = chosen ? statusAfter : internal ? s.status : Set.of("OPEN", "WAITING").contains(s.status) ? "IN_PROGRESS" : s.status;
        if (!STATUSES.contains(next)) throw new BusinessRuleException("INVALID_STATUS", "Unknown status " + next);
        if (!next.equals(s.status)) status(load(id), next, name, false);
        platformAudit.record(internal ? "SUPPORT_NOTE" : "SUPPORT_REPLY", s.tenantId, "SupportTicket", id, Map.of("ticketNo", s.ticketNo, "status", next));
        if (!internal) {
            Row now = load(id);
            toInstitution(now, (("RESOLVED".equals(next) ? "Resolved: " : "Reply from Msingi: ") + now.ticketNo),
                    ("RESOLVED".equals(next) ? "Msingi Technologies has answered your support ticket " + now.ticketNo + " (" + now.subject
                            + ") and marked it resolved. If you still need help, reply within " + REOPEN_DAYS + " days."
                            : "Msingi Technologies has replied to your support ticket " + now.ticketNo + " (" + now.subject + ").")
                            + " Read it under Helpdesk › Msingi support in CIMS.");
        }
        return platformView(id);
    }

    @Transactional
    public SupportView platformAssign(Long id, Long platformUserId) {
        Row s = load(id);
        String by = platformUserName(CurrentUser.platformId().orElseThrow());
        if (platformUserId == null) {
            jdbc.update("update support_tickets set assignee_platform_user_id = null, assignee_name = null, updated_at = now() where id = ?", id);
            message(id, s.tenantId, "SYSTEM", by, "Unassigned by " + by, true);
        } else {
            String name = jdbc.queryForList("select full_name from platform_users where id = ? and status = 'ACTIVE'", String.class, platformUserId)
                    .stream().findFirst().orElseThrow(() -> new BusinessRuleException("NOT_AN_AGENT", "Choose an active Msingi staff member."));
            jdbc.update("update support_tickets set assignee_platform_user_id = ?, assignee_name = ?, updated_at = now() where id = ?", platformUserId, name, id);
            message(id, s.tenantId, "SYSTEM", by, name.equals(by) ? "Taken by " + by : "Assigned to " + name + " by " + by, true);
            if (!platformUserId.equals(CurrentUser.platformId().orElse(null))) {
                toMsingi(s.tenantId, List.of(platformUserId), "Assigned to you: " + s.ticketNo, by + " assigned " + s.ticketNo + " (" + s.subject + ", "
                        + institutionName(s.tenantId) + ") to you.");
            }
        }
        platformAudit.record("SUPPORT_ASSIGN", s.tenantId, "SupportTicket", id, Map.of("ticketNo", s.ticketNo, "assignee", platformUserId == null ? "" : platformUserId));
        return platformView(id);
    }

    @Transactional
    public SupportView platformStatus(Long id, String status) {
        Row s = load(id);
        if (!STATUSES.contains(status)) throw new BusinessRuleException("INVALID_STATUS", "Unknown status " + status);
        if (status.equals(s.status)) return platformView(id);
        String by = platformUserName(CurrentUser.platformId().orElseThrow());
        status(s, status, by, true);
        platformAudit.record("SUPPORT_STATUS", s.tenantId, "SupportTicket", id, Map.of("ticketNo", s.ticketNo, "from", s.status, "to", status));
        if ("RESOLVED".equals(status)) {
            toInstitution(load(id), "Resolved: " + s.ticketNo, "Msingi Technologies has marked your support ticket " + s.ticketNo + " (" + s.subject
                    + ") resolved. If you still need help, reply within " + REOPEN_DAYS + " days under Helpdesk › Msingi support.");
        }
        return platformView(id);
    }

    @Transactional
    public SupportView platformPriority(Long id, String priority) {
        Row s = load(id);
        if (!Set.of("NORMAL", "HIGH", "URGENT").contains(priority)) throw new BusinessRuleException("INVALID_PRIORITY", "Unknown priority " + priority);
        if (priority.equals(s.priority)) return platformView(id);
        int[] t = targets(priority);
        // the targets follow the new priority, counted from when the ticket was raised
        jdbc.update("""
                update support_tickets set priority = ?, response_due_at = created_at + make_interval(hours => ?),
                  resolution_due_at = created_at + make_interval(hours => ?) + coalesce(now() - paused_at, interval '0'), updated_at = now() where id = ?""",
                priority, t[0], t[1], id);
        String by = platformUserName(CurrentUser.platformId().orElseThrow());
        message(id, s.tenantId, "SYSTEM", by, "Priority changed from " + label(s.priority) + " to " + label(priority) + " by " + by, false);
        platformAudit.record("SUPPORT_PRIORITY", s.tenantId, "SupportTicket", id, Map.of("ticketNo", s.ticketNo, "priority", priority));
        return platformView(id);
    }

    @Transactional(readOnly = true)
    public List<Agent> platformAgents() {
        return jdbc.query("select id, full_name from platform_users where status = 'ACTIVE' order by full_name", (rs, i) -> new Agent(rs.getLong(1), rs.getString(2)));
    }

    @Transactional(readOnly = true)
    public FileContent platformAttachment(Long attachmentId) {
        attachmentTicket(attachmentId);
        return file(attachmentId);
    }

    private SupportView platformView(Long id) {
        Row s = load(id);
        return new SupportView(row(id), s.raisedByEmail, s.raisedByPhone, messages(id, true), true, false);
    }

    // ================================================================= targets (run by the helpdesk job, as the platform)

    @Transactional
    public void runTargets() {
        if (TenantContext.currentTenantIdOrNull() != null) throw new IllegalStateException("Msingi support targets run as the platform");
        for (Long id : jdbc.queryForList("""
                select id from support_tickets where status in ('OPEN', 'IN_PROGRESS', 'WAITING') and escalation_level < 1
                  and first_response_at is null and response_due_at < now()""", Long.class)) {
            Row s = load(id);
            jdbc.update("update support_tickets set escalation_level = 1, updated_at = now() where id = ?", id);
            message(id, s.tenantId, "SYSTEM", "CIMS", "Escalated: no reply from Msingi within the time set", true);
            toMsingi(s.tenantId, s.assigneeId == null ? null : List.of(s.assigneeId), "Overdue: " + s.ticketNo + " has no reply",
                    s.ticketNo + " (" + s.subject + ", " + institutionName(s.tenantId) + ", " + label(s.priority) + ") has not had a first reply in time.");
        }
        for (Long id : jdbc.queryForList("""
                select id from support_tickets where status in ('OPEN', 'IN_PROGRESS') and escalation_level < 2 and resolution_due_at < now()""", Long.class)) {
            Row s = load(id);
            jdbc.update("update support_tickets set escalation_level = 2, updated_at = now() where id = ?", id);
            message(id, s.tenantId, "SYSTEM", "CIMS", "Escalated to all Msingi staff: not resolved within the time set", true);
            toMsingi(s.tenantId, null, "Overdue: " + s.ticketNo + " not resolved", s.ticketNo + " (" + s.subject + ", " + institutionName(s.tenantId) + ", "
                    + label(s.priority) + ") was not resolved in the time set.");
        }
        for (Long id : jdbc.queryForList("select id from support_tickets where status = 'RESOLVED' and resolved_at < now() - make_interval(days => ?)",
                Long.class, REOPEN_DAYS)) {
            Row s = load(id);
            jdbc.update("update support_tickets set status = 'CLOSED', closed_at = now(), updated_at = now() where id = ?", id);
            message(id, s.tenantId, "SYSTEM", "CIMS", "Closed automatically " + REOPEN_DAYS + " days after it was resolved", false);
        }
    }

    // ================================================================= engine

    /** Hours to a first reply and to resolve, by priority. */
    static int[] targets(String priority) {
        return switch (priority) {
            case "URGENT" -> new int[]{1, 24};
            case "HIGH" -> new int[]{4, 48};
            default -> new int[]{8, 120};
        };
    }

    private void status(Row s, String to, String by, boolean say) {
        jdbc.update("""
                update support_tickets set resolution_due_at = case when paused_at is not null then resolution_due_at + (now() - paused_at) else resolution_due_at end,
                  paused_at = null where id = ?""", s.id);
        switch (to) {
            case "WAITING" -> jdbc.update("update support_tickets set status = 'WAITING', paused_at = now(), updated_at = now() where id = ?", s.id);
            case "RESOLVED" -> jdbc.update("update support_tickets set status = 'RESOLVED', resolved_at = now(), updated_at = now() where id = ?", s.id);
            case "CLOSED" -> jdbc.update("update support_tickets set status = 'CLOSED', closed_at = now(), resolved_at = coalesce(resolved_at, now()), updated_at = now() where id = ?", s.id);
            default -> jdbc.update("update support_tickets set status = ?, resolved_at = null, closed_at = null, updated_at = now() where id = ?", to, s.id);
        }
        boolean forInstitution = Set.of("RESOLVED", "CLOSED", "WAITING").contains(to) || Set.of("RESOLVED", "CLOSED").contains(s.status);
        if (say || forInstitution) {
            String text = switch (to) {
                case "RESOLVED" -> "Marked resolved by " + by;
                case "CLOSED" -> "Closed by " + by;
                case "WAITING" -> "Waiting for the institution (set by " + by + ")";
                default -> Set.of("RESOLVED", "CLOSED").contains(s.status) ? "Reopened by " + by : "Status changed to " + label(to) + " by " + by;
            };
            message(s.id, s.tenantId, "SYSTEM", by, text, !forInstitution);
        }
    }

    private Long message(Long ticketId, Long tenantId, String side, String name, String body, boolean internal) {
        return jdbc.queryForObject("""
                insert into support_messages (tenant_id, ticket_id, author_side, author_name, body, internal) values (?, ?, ?, ?, ?, ?) returning id""",
                Long.class, tenantId, ticketId, side, name, body, internal);
    }

    private void store(Long messageId, Long tenantId, List<Upload> uploads) {
        for (Upload u : uploads) {
            jdbc.update("""
                    insert into support_attachments (tenant_id, message_id, file_name, content_type, size_bytes, sha256, data) values (?, ?, ?, ?, ?, ?, ?)""",
                    tenantId, messageId, u.fileName(), u.contentType(), u.data().length, u.sha256(), u.data());
        }
    }

    private Map<String, Object> attachmentTicket(Long attachmentId) {
        return jdbc.queryForList("""
                select m.ticket_id, m.internal from support_attachments a join support_messages m on m.id = a.message_id where a.id = ?""", attachmentId)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Attachment", attachmentId));
    }

    private FileContent file(Long attachmentId) {
        return jdbc.queryForObject("select file_name, content_type, data from support_attachments where id = ?",
                (rs, i) -> new FileContent(rs.getString(1), rs.getString(2), rs.getBytes(3)), attachmentId);
    }

    private List<SupportMessage> messages(Long ticketId, boolean msingi) {
        Map<Long, List<AttachmentView>> files = jdbc.query("""
                select a.message_id, a.id, a.file_name, a.content_type, a.size_bytes from support_attachments a
                join support_messages m on m.id = a.message_id where m.ticket_id = ? order by a.id""",
                (rs, i) -> Map.entry(rs.getLong(1), new AttachmentView(rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5))), ticketId)
                .stream().collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return jdbc.query("select id, author_side, author_name, body, internal, created_at from support_messages where ticket_id = ?"
                        + (msingi ? "" : " and not internal") + " order by created_at, id",
                (rs, i) -> new SupportMessage(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                        rs.getObject(6, OffsetDateTime.class), files.getOrDefault(rs.getLong(1), List.of())), ticketId);
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
            case "WAITING" -> "Waiting for the institution";
            case "ACCOUNT_ACCESS" -> "Account or access";
            case "PROBLEM" -> "Something is not working";
            case "INTEGRATION" -> "Integration (NECTA, NACTVET, GePG, SMS…)";
            case "DATA_CHANGE" -> "Data correction";
            case "TRAINING" -> "Training or how-to";
            case "NEW_FEATURE" -> "New feature";
            case "BILLING" -> "Contract or billing";
            default -> code.charAt(0) + code.substring(1).toLowerCase(Locale.ROOT);
        };
    }

    private String staffName() {
        return jdbc.queryForObject("select full_name from user_accounts where id = ?", String.class, CurrentUser.requireId());
    }

    private String platformUserName(Long id) {
        return jdbc.queryForObject("select full_name from platform_users where id = ?", String.class, id);
    }

    private String institutionName(Long tenantId) {
        return tenants.findById(tenantId).map(Tenant::getName).orElse("institution " + tenantId);
    }

    /** Emails Msingi staff (some, or all active ones). Email belongs to an institution, so it is queued in the ticket's institution's outbox. */
    private void toMsingi(Long tenantId, List<Long> platformUserIds, String subject, String body) {
        List<Map<String, Object>> all = jdbc.queryForList("select id, email, full_name from platform_users where status = 'ACTIVE' and email is not null");
        List<Map<String, Object>> to = platformUserIds == null || platformUserIds.isEmpty() ? all
                : all.stream().filter(r -> platformUserIds.contains(((Number) r.get("id")).longValue())).toList();
        if (to.isEmpty()) return;
        Runnable send = () -> to.forEach(r -> email.queue((String) r.get("email"), (String) r.get("full_name"), EmailMessage.Kind.EVENT, "MSINGI_SUPPORT",
                subject, body + "\n\n— CIMS support desk", null));
        asInstitution(tenantId, send);
    }

    private void asInstitution(Long tenantId, Runnable work) {
        if (TenantContext.currentTenantIdOrNull() == null) {
            tenantWork.within(tenants.findById(tenantId).orElseThrow(), () -> { work.run(); return null; });
        } else {
            work.run();
        }
    }

    /** Emails the person who raised the ticket (in the institution's outbox). From the platform console, switches to the institution first. */
    private void toInstitution(Row s, String subject, String body) {
        if (s.raisedByEmail == null || s.raisedByEmail.isBlank()) return;
        Runnable send = () -> email.queue(s.raisedByEmail, s.raisedByName, EmailMessage.Kind.EVENT, "MSINGI_SUPPORT", subject,
                body + "\n\n— Msingi Technologies support", null);
        asInstitution(s.tenantId, send);
    }

    private static String when(OffsetDateTime t) {
        return t.atZoneSameInstant(TZ).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK));
    }

    // ---------------------------------------------------------------- rows

    static final String LATE = "((s.first_response_at is null and s.response_due_at < now()) or (s.status <> 'WAITING' and s.resolution_due_at < now()))";

    private static final String SELECT = """
            select s.id, s.ticket_no, s.tenant_id, tn.code, tn.name, s.category, s.subject, s.status, s.priority, s.raised_by_name,
                   s.assignee_platform_user_id, s.assignee_name, s.response_due_at, s.resolution_due_at, s.first_response_at, s.resolved_at,
                   case when s.status in ('OPEN', 'IN_PROGRESS', 'WAITING') then
                        case when s.first_response_at is null and s.response_due_at < now() then 'RESPONSE'
                             when s.status <> 'WAITING' and s.resolution_due_at < now() then 'RESOLUTION' end end,
                   s.satisfied, (select h.ticket_no from helpdesk_tickets h where h.id = s.helpdesk_ticket_id), s.created_at, s.updated_at
            from support_tickets s join tenants tn on tn.id = s.tenant_id""";

    private static final RowMapper<SupportRow> ROW = (rs, i) -> new SupportRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
            rs.getObject(11) == null ? null : rs.getLong(11), rs.getString(12), rs.getObject(13, OffsetDateTime.class),
            rs.getObject(14, OffsetDateTime.class), rs.getObject(15, OffsetDateTime.class), rs.getObject(16, OffsetDateTime.class), rs.getString(17),
            (Boolean) rs.getObject(18), rs.getString(19), rs.getObject(20, OffsetDateTime.class), rs.getObject(21, OffsetDateTime.class));

    private SupportRow row(Long id) {
        return jdbc.queryForObject(SELECT + " where s.id = ?", ROW, id);
    }

    static final class Row {
        Long id; Long tenantId; String ticketNo; String subject; String status; String priority; Long assigneeId; String raisedByName;
        String raisedByEmail; String raisedByPhone; OffsetDateTime resolvedAt;
    }

    private Row load(Long id) {
        List<Row> r = jdbc.query("""
                select id, tenant_id, ticket_no, subject, status, priority, assignee_platform_user_id, raised_by_name, raised_by_email, raised_by_phone, resolved_at
                from support_tickets where id = ?""", (rs, i) -> {
            Row x = new Row();
            x.id = rs.getLong(1); x.tenantId = rs.getLong(2); x.ticketNo = rs.getString(3); x.subject = rs.getString(4); x.status = rs.getString(5);
            x.priority = rs.getString(6); x.assigneeId = rs.getObject(7) == null ? null : rs.getLong(7); x.raisedByName = rs.getString(8);
            x.raisedByEmail = rs.getString(9); x.raisedByPhone = rs.getString(10); x.resolvedAt = rs.getObject(11, OffsetDateTime.class);
            return x;
        }, id);
        if (r.isEmpty()) throw new NotFoundException("Support ticket", id);
        return r.get(0);
    }

}
