package tz.co.msingi.cims.helpdesk;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The knowledge base: answers to common questions, shown on the help pages before someone raises a ticket. Each article says who
 * may read it (applicants, students, staff, the public help page). The administrator writes any article; the staff of an office
 * write the articles of its categories.
 */
@Service
@RequiredArgsConstructor
public class HelpdeskArticleService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public record ArticleRequest(Long categoryId, @NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 10000) String body,
                                 boolean forApplicants, boolean forStudents, boolean forStaff, boolean forPublic, boolean published) {
    }

    public record ArticleView(Long id, Long categoryId, String categoryName, String title, String body, boolean forApplicants, boolean forStudents,
                              boolean forStaff, boolean forPublic, boolean published, int views, int helpful, int notHelpful,
                              String updatedByName, OffsetDateTime updatedAt, boolean canEdit) {
    }

    /** Published articles for one kind of reader (APPLICANT, STUDENT, STAFF or PUBLIC), searched by words in the title or text. */
    @Transactional(readOnly = true)
    public List<ArticleView> forReader(String reader, String q, Long categoryId) {
        String column = switch (reader) {
            case "APPLICANT" -> "a.for_applicants";
            case "STUDENT" -> "a.for_students";
            case "STAFF" -> "a.for_staff";
            default -> "a.for_public";
        };
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where a.published and ").append(column);
        if (categoryId != null) { where.append(" and a.category_id = ?"); args.add(categoryId); }
        if (q != null && !q.isBlank()) {
            for (String word : q.trim().split("\\s+")) {
                if (word.length() < 2) continue;
                where.append(" and (a.title ilike ? or a.body ilike ?)");
                args.add("%" + word + "%");
                args.add("%" + word + "%");
            }
        }
        return jdbc.query(SELECT + where + " order by a.helpful - a.not_helpful desc, a.views desc, a.title limit 50", mapper(false), args.toArray());
    }

    /** One published article for a reader; counts the view. */
    @Transactional
    public ArticleView read(Long id, String reader) {
        ArticleView a = get(id, false);
        boolean allowed = a.published() && switch (reader) {
            case "APPLICANT" -> a.forApplicants();
            case "STUDENT" -> a.forStudents();
            case "STAFF" -> a.forStaff();
            default -> a.forPublic();
        };
        if (!allowed) throw new NotFoundException("Article", id);
        jdbc.update("update helpdesk_articles set views = views + 1 where id = ?", id);
        return a;
    }

    @Transactional
    public void vote(Long id, String reader, boolean helpful) {
        read(id, reader);
        jdbc.update(helpful ? "update helpdesk_articles set helpful = helpful + 1, views = views - 1 where id = ?"
                : "update helpdesk_articles set not_helpful = not_helpful + 1, views = views - 1 where id = ?", id);
    }

    // ---------------------------------------------------------------- staff

    @Transactional(readOnly = true)
    public List<ArticleView> all() {
        return jdbc.query(SELECT + " order by a.published desc, a.updated_at desc", mapper(true));
    }

    @Transactional
    public ArticleView create(ArticleRequest r) {
        requireEditor(r.categoryId());
        check(r);
        Long id = jdbc.queryForObject("""
                insert into helpdesk_articles (tenant_id, category_id, title, body, for_applicants, for_students, for_staff, for_public, published, updated_by_name)
                values (cims_current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                r.categoryId(), r.title().trim(), r.body().trim(), r.forApplicants(), r.forStudents(), r.forStaff(), r.forPublic(), r.published(), name());
        audit.record("HELPDESK_ARTICLE_ADDED", "HelpdeskArticle", id, Map.of("title", r.title().trim(), "published", r.published()));
        return get(id, true);
    }

    @Transactional
    public ArticleView update(Long id, ArticleRequest r) {
        ArticleView before = get(id, true);
        requireEditor(before.categoryId());
        requireEditor(r.categoryId());
        check(r);
        jdbc.update("""
                update helpdesk_articles set category_id = ?, title = ?, body = ?, for_applicants = ?, for_students = ?, for_staff = ?, for_public = ?,
                  published = ?, updated_by_name = ?, updated_at = now() where id = ?""",
                r.categoryId(), r.title().trim(), r.body().trim(), r.forApplicants(), r.forStudents(), r.forStaff(), r.forPublic(), r.published(), name(), id);
        audit.record("HELPDESK_ARTICLE_UPDATED", "HelpdeskArticle", id, Map.of("title", r.title().trim(), "published", r.published()));
        return get(id, true);
    }

    @Transactional
    public void delete(Long id) {
        ArticleView a = get(id, true);
        requireEditor(a.categoryId());
        jdbc.update("delete from helpdesk_articles where id = ?", id);
        audit.record("HELPDESK_ARTICLE_REMOVED", "HelpdeskArticle", id, Map.of("title", a.title()));
    }

    private void check(ArticleRequest r) {
        if (!(r.forApplicants() || r.forStudents() || r.forStaff() || r.forPublic())) {
            throw new BusinessRuleException("NO_AUDIENCE", "Choose who can read this article.");
        }
        if (r.categoryId() != null) {
            Integer n = jdbc.queryForObject("select count(*) from helpdesk_categories where id = ?", Integer.class, r.categoryId());
            if (n == null || n == 0) throw new NotFoundException("Helpdesk category", r.categoryId());
        }
    }

    /** The administrator edits any article; an office's staff those of its categories (an article with no category: administrator only). */
    private void requireEditor(Long categoryId) {
        Set<String> roles = roles();
        if (roles.contains("INSTITUTION_ADMIN")) return;
        if (categoryId != null) {
            String office = jdbc.queryForList("select office_role from helpdesk_categories where id = ?", String.class, categoryId).stream().findFirst().orElse(null);
            if (office != null && roles.contains(office)) return;
        }
        throw new AccessDeniedException("Only the administrator or the office that answers this category can change its articles");
    }

    private boolean canEdit(Long categoryId, String office) {
        Set<String> roles = roles();
        return roles.contains("INSTITUTION_ADMIN") || (categoryId != null && office != null && roles.contains(office));
    }

    private static Set<String> roles() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? Set.of() : auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(r -> r.startsWith("ROLE_")).map(r -> r.substring(5)).collect(Collectors.toSet());
    }

    private String name() {
        return jdbc.queryForObject("select full_name from user_accounts where id = ?", String.class, CurrentUser.requireId());
    }

    private ArticleView get(Long id, boolean staff) {
        List<ArticleView> r = jdbc.query(SELECT + " where a.id = ?", mapper(staff), id);
        if (r.isEmpty()) throw new NotFoundException("Article", id);
        return r.get(0);
    }

    private static final String SELECT = """
            select a.id, a.category_id, c.name, a.title, a.body, a.for_applicants, a.for_students, a.for_staff, a.for_public, a.published,
                   a.views, a.helpful, a.not_helpful, a.updated_by_name, a.updated_at, c.office_role
            from helpdesk_articles a left join helpdesk_categories c on c.id = a.category_id""";

    private RowMapper<ArticleView> mapper(boolean staff) {
        return (rs, i) -> {
            Long cat = rs.getObject(2) == null ? null : rs.getLong(2);
            return new ArticleView(rs.getLong(1), cat, rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getBoolean(7),
                    rs.getBoolean(8), rs.getBoolean(9), rs.getBoolean(10), rs.getInt(11), rs.getInt(12), rs.getInt(13),
                    staff ? rs.getString(14) : null, rs.getObject(15, OffsetDateTime.class), staff && canEdit(cat, rs.getString(16)));
        };
    }
}
