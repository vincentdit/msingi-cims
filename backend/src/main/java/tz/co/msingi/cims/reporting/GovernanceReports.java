package tz.co.msingi.cims.reporting;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.reporting.ReportModel.Column;
import tz.co.msingi.cims.reporting.ReportModel.Data;
import tz.co.msingi.cims.reporting.ReportModel.Definition;
import tz.co.msingi.cims.reporting.ReportModel.Filter;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static tz.co.msingi.cims.reporting.ReportModel.PERIOD;
import static tz.co.msingi.cims.reporting.ReportModel.TEXT;

/** Governance reports (SRS section 7): who can do what, and what was done. */
@Component
class GovernanceReports extends ReportQueries implements ReportProvider {

    static final Set<Role> ROLES = Set.of(Role.INSTITUTION_ADMIN, Role.AUDITOR);
    static final int AUDIT_MAX = 20_000;

    GovernanceReports(NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public List<Definition> definitions() {
        return List.of(
                new Definition("GOV-01", "Governance", "User access review",
                        "Every staff account with its roles, status and last sign-in, for the periodic access review.", ROLES, true, List.of(), this::access),
                new Definition("GOV-02", "Governance", "Audit log extract",
                        "What was done, by whom and when, for the period (optionally one kind of action).", ROLES, true, List.of(PERIOD, TEXT), this::audit));
    }

    private Data access(Filter f) {
        List<List<Object>> r = rows("""
                select u.username, u.full_name, u.email, coalesce(string_agg(ur.role, ', ' order by ur.role), ''), u.status, u.last_login_at,
                       u.failed_logins, u.created_at
                from user_accounts u left join user_roles ur on ur.user_id = u.id
                where u.user_type = 'STAFF'
                group by u.id, u.username, u.full_name, u.email, u.status, u.last_login_at, u.failed_logins, u.created_at
                order by u.full_name""", new Sql());
        r.forEach(x -> x.set(4, AdmissionReports.label(String.valueOf(x.get(4)))));
        return new Data(List.of(Column.text("username", "Username"), Column.text("name", "Name"), Column.text("email", "Email"),
                Column.text("roles", "Roles"), Column.text("status", "Status"), Column.dateTime("lastLogin", "Last sign-in"),
                Column.number("failed", "Failed sign-ins"), Column.dateTime("created", "Created")), r)
                .withNote("Review each account: does the person still need it, and these roles? Disable accounts of people who have left.");
    }

    private Data audit(Filter f) {
        LocalDate from = f.from() != null ? f.from() : LocalDate.now(Sql.ZONE).minusDays(6);
        LocalDate to = f.to() != null ? f.to() : LocalDate.now(Sql.ZONE);
        Sql q = new Sql().period("l.occurred_at", from, to).param("max", AUDIT_MAX);
        if (f.text() != null && !f.text().isBlank()) {
            q.raw("(upper(l.action) like :text or upper(l.actor_name) like :text or upper(l.entity) like :text)")
                    .param("text", "%" + f.text().trim().toUpperCase() + "%");
        }
        List<List<Object>> r = rows("""
                select l.occurred_at, coalesce(l.actor_name, 'System'), l.action, l.entity, l.entity_id, cast(l.details as text), l.ip_address
                from audit_logs l where 1 = 1""" + q + " order by l.occurred_at, l.id limit :max", q);
        Data d = new Data(List.of(Column.dateTime("at", "When"), Column.text("actor", "Who"), Column.text("action", "Action"),
                Column.text("entity", "Record"), Column.number("id", "Record id"), Column.text("details", "Details"), Column.text("ip", "IP address")), r);
        return r.size() >= AUDIT_MAX ? d.withNote("Only the first " + AUDIT_MAX + " entries are included: choose a shorter period.") : d;
    }
}
