package tz.co.msingi.cims.helpdesk.assistant;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.helpdesk.assistant.MatchIndex.Doc;
import tz.co.msingi.cims.helpdesk.assistant.MatchIndex.Hit;
import tz.co.msingi.cims.platform.tenancy.TenantContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The helpdesk assistant (FR-COM-08): predicts answers from what the institution already knows, with no outside service and no
 * data leaving the server.
 * <ul>
 * <li>While someone writes a request it suggests the published answers most likely to solve it, and guesses the category.</li>
 * <li>When a request is raised and one answer is a confident match, that answer is given at once (the office still sees the
 * request and can follow up).</li>
 * <li>Staff get suggested replies: published answers, and replies that solved similar requests before.</li>
 * <li>It learns: a request closed as solved adds its reply to what it knows, and every request raised trains the category
 * guess. Frequent questions it could not answer are grouped so an office writes the answer once.</li>
 * </ul>
 * Text is compared in English and Kiswahili through shared concepts (see {@link TextAnalyzer}).
 */
@Service
@RequiredArgsConstructor
public class HelpdeskAssistant {

    /** At or above this confidence an answer is given at once when a request is raised. */
    public static final double INSTANT = 0.58;
    /** At or above this an answer is suggested while writing. */
    public static final double SUGGEST = 0.28;
    /** Below this, a request counts as one the knowledge base could not answer. */
    static final double GAP = 0.45;

    private final JdbcTemplate jdbc;
    private final Map<Long, Knowledge> cache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ what the assistant knows

    public record Article(Long id, Long categoryId, String categoryName, String title, String body, Set<String> audiences) {
    }

    public record Learned(Long id, Long ticketId, String ticketNo, Long categoryId, String question, String answer, boolean confirmed, int used) {
    }

    public record Category(Long id, String name) {
    }

    record Knowledge(String fingerprint, MatchIndex<Article> articles, MatchIndex<Learned> learned, MatchIndex<Category> categories) {
    }

    // ------------------------------------------------------------------ results

    public record ArticleHit(Long id, String title, String body, Long categoryId, String categoryName, double confidence) {
    }

    public record CategoryGuess(Long id, String name, double confidence) {
    }

    public record Suggestion(List<ArticleHit> answers, CategoryGuess category) {
    }

    public record Reply(String source, Long sourceId, String title, String text, double confidence, boolean confirmed, String ticketNo) {
    }

    public record StaffSuggestions(List<Reply> replies, CategoryGuess category) {
    }

    // ------------------------------------------------------------------ predicting

    /** Answers likely to solve what is being written, for one kind of reader (APPLICANT, STUDENT, STAFF or PUBLIC), and the category guessed. */
    @Transactional(readOnly = true)
    public Suggestion suggest(String reader, String subject, String body, Collection<Long> allowedCategories) {
        Knowledge k = knowledge();
        Map<String, Integer> q = TextAnalyzer.terms(join(subject, body));
        if (q.isEmpty()) return new Suggestion(List.of(), null);
        List<ArticleHit> answers = k.articles().search(q, 12).stream()
                .filter(h -> h.item().audiences().contains(reader) && h.confidence() >= SUGGEST)
                .limit(3).map(HelpdeskAssistant::articleHit).toList();
        return new Suggestion(answers, guessCategory(k, q, allowedCategories));
    }

    /** The answer to give at once when a request is raised, if one is a confident match for this reader. */
    @Transactional(readOnly = true)
    public ArticleHit instantAnswer(String reader, String subject, String body) {
        Knowledge k = knowledge();
        Map<String, Integer> q = TextAnalyzer.terms(join(subject, body));
        if (meaningful(q) < 2) return null;
        List<Hit<Article>> hits = k.articles().search(q, 12).stream().filter(h -> h.item().audiences().contains(reader)).toList();
        if (hits.isEmpty() || hits.get(0).confidence() < INSTANT) return null;
        // two answers equally likely: better to let the office choose than to guess
        if (hits.size() > 1 && hits.get(1).confidence() > hits.get(0).confidence() - 0.05 && !hits.get(1).item().id().equals(hits.get(0).item().id())) return null;
        return articleHit(hits.get(0));
    }

    /** Replies for staff on a request: published answers (any audience) and replies that solved similar requests. */
    @Transactional(readOnly = true)
    public StaffSuggestions forStaff(Long ticketId, String text, Collection<Long> categories) {
        Knowledge k = knowledge();
        Map<String, Integer> q = TextAnalyzer.terms(text);
        List<Reply> out = new ArrayList<>();
        for (Hit<Article> h : k.articles().search(q, 4)) {
            if (h.confidence() < SUGGEST) continue;
            out.add(new Reply("ANSWER", h.item().id(), h.item().title(), h.item().body(), round(h.confidence()), true, null));
        }
        for (Hit<Learned> h : k.learned().search(q, 8)) {
            if (h.item().ticketId().equals(ticketId) || h.confidence() < SUGGEST) continue;
            // a reply the requester confirmed counts a little more than one that was simply not reopened
            double c = Math.min(1, h.confidence() + (h.item().confirmed() ? 0.05 : 0));
            out.add(new Reply("PAST_REPLY", h.item().id(), h.item().question(), h.item().answer(), round(c), h.item().confirmed(), h.item().ticketNo()));
        }
        out.sort(Comparator.comparingDouble((Reply r) -> -r.confidence()));
        // the same text twice (an answer pasted as a reply) is shown once
        Map<String, Reply> unique = new LinkedHashMap<>();
        for (Reply r : out) unique.putIfAbsent(TextAnalyzer.fold(r.text()).replaceAll("\\s+", " "), r);
        return new StaffSuggestions(unique.values().stream().limit(5).toList(), guessCategory(k, q, categories));
    }

    private CategoryGuess guessCategory(Knowledge k, Map<String, Integer> q, Collection<Long> allowed) {
        List<Hit<Category>> hits = k.categories().search(q, 20).stream()
                .filter(h -> allowed == null || allowed.contains(h.item().id())).toList();
        if (hits.isEmpty()) return null;
        Hit<Category> top = hits.get(0);
        double second = hits.size() > 1 ? hits.get(1).score() : 0;
        double share = top.score() / (top.score() + second);           // 0.5 (tie) … 1 (only one fits)
        double confidence = Math.min(1, top.coverage() * (0.35 + 0.65 * (2 * share - 1)) + (share > 0.8 ? 0.1 : 0));
        if (confidence < 0.2) return null;
        return new CategoryGuess(top.item().id(), top.item().name(), round(confidence));
    }

    // ------------------------------------------------------------------ learning

    /**
     * A request has closed. Its last public reply from staff joins the replies the assistant suggests, as confirmed when the
     * requester said it helped. Nothing is learned when the requester said it did not help, or when staff never replied.
     */
    @Transactional
    public void learn(Long ticketId, Boolean satisfied) {
        if (Boolean.FALSE.equals(satisfied)) {
            jdbc.update("delete from helpdesk_learned_answers where ticket_id = ?", ticketId);
            return;
        }
        List<Map<String, Object>> t = jdbc.queryForList("select category_id, subject from helpdesk_tickets where id = ?", ticketId);
        if (t.isEmpty()) return;
        String question = jdbc.queryForList("""
                select body from helpdesk_messages where ticket_id = ? and author_type = 'REQUESTER' order by created_at, id limit 1""",
                String.class, ticketId).stream().findFirst().orElse("");
        String answer = jdbc.queryForList("""
                select body from helpdesk_messages where ticket_id = ? and author_type = 'STAFF' and not internal order by created_at desc, id desc limit 1""",
                String.class, ticketId).stream().findFirst().orElse(null);
        if (answer == null || answer.length() < 15) return;
        String q = (t.get(0).get("subject") + "\n" + question).trim();
        jdbc.update("""
                insert into helpdesk_learned_answers (tenant_id, ticket_id, category_id, question, answer, confirmed)
                values (cims_current_tenant(), ?, ?, ?, ?, ?)
                on conflict (ticket_id) do update set question = excluded.question, answer = excluded.answer, confirmed = excluded.confirmed,
                  category_id = excluded.category_id""",
                ticketId, t.get(0).get("category_id"), cut(q, 2000), cut(answer, 5000), Boolean.TRUE.equals(satisfied));
    }

    /** Staff used a suggested past reply. */
    @Transactional
    public void used(Long learnedId) {
        jdbc.update("update helpdesk_learned_answers set used = used + 1 where id = ?", learnedId);
    }

    // ------------------------------------------------------------------ questions with no answer yet

    public record Gap(String topic, int requests, String categoryName, Long categoryId, List<String> examples, String question, String answer,
                      OffsetDateTime latest) {
    }

    /** Requests of the last {@code days} days the knowledge base could not answer, grouped by what they are about (two or more alike). */
    @Transactional(readOnly = true)
    public List<Gap> gaps(int days, Collection<Long> categories) {
        Knowledge k = knowledge();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select t.id, t.subject, t.category_id, c.name, t.created_at,
                       (select m.body from helpdesk_messages m where m.ticket_id = t.id and m.author_type = 'REQUESTER' order by m.created_at, m.id limit 1) q,
                       (select m.body from helpdesk_messages m where m.ticket_id = t.id and m.author_type = 'STAFF' and not m.internal
                          order by m.created_at desc, m.id desc limit 1) a
                from helpdesk_tickets t join helpdesk_categories c on c.id = t.category_id
                where t.created_at >= now() - make_interval(days => ?) order by t.created_at desc limit 1000""", days);
        List<Cluster> clusters = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Long cat = ((Number) r.get("category_id")).longValue();
            if (categories != null && !categories.contains(cat)) continue;
            String text = join((String) r.get("subject"), (String) r.get("q"));
            Map<String, Integer> terms = TextAnalyzer.terms(text);
            List<Hit<Article>> best = k.articles().search(terms, 1);
            if (!best.isEmpty() && best.get(0).confidence() >= GAP) continue;   // an answer exists
            Map<String, Double> v = vector(k, terms);
            if (v.isEmpty()) continue;
            Cluster into = null;
            double bestSim = 0.3;
            for (Cluster c : clusters) {
                double s = cosine(v, c.centroid);
                if (s > bestSim) { bestSim = s; into = c; }
            }
            if (into == null) { into = new Cluster(); clusters.add(into); }
            into.add(v, r);
        }
        return clusters.stream().filter(c -> c.rows.size() >= 2)
                .sorted(Comparator.comparingInt((Cluster c) -> -c.rows.size()))
                .limit(15).map(c -> c.gap()).toList();
    }

    private static final class Cluster {
        final Map<String, Double> centroid = new HashMap<>();
        final List<Map<String, Object>> rows = new ArrayList<>();

        void add(Map<String, Double> v, Map<String, Object> r) {
            v.forEach((t, w) -> centroid.merge(t, w, Double::sum));
            rows.add(r);
        }

        Gap gap() {
            String topic = centroid.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).limit(3)
                    .map(e -> label(e.getKey())).distinct().collect(Collectors.joining(", "));
            Map<String, Long> byCategory = rows.stream().collect(Collectors.groupingBy(r -> (String) r.get("name"), Collectors.counting()));
            String category = byCategory.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            Long categoryId = rows.stream().filter(r -> r.get("name").equals(category)).map(r -> ((Number) r.get("category_id")).longValue()).findFirst().orElse(null);
            List<String> examples = rows.stream().map(r -> (String) r.get("subject")).distinct().limit(4).toList();
            String answer = rows.stream().map(r -> (String) r.get("a")).filter(a -> a != null && !a.isBlank()).findFirst().orElse(null);
            OffsetDateTime latest = rows.stream().map(r -> toOffset(r.get("created_at"))).max(Comparator.naturalOrder()).orElse(null);
            return new Gap(topic, rows.size(), category, categoryId, examples, (String) rows.get(rows.size() - 1).get("subject"), answer, latest);
        }
    }

    private static OffsetDateTime toOffset(Object o) {
        if (o instanceof OffsetDateTime t) return t;
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return null;
    }

    /** A word for a term: a concept by its name ("~pay" → "pay"), a stem as it is. */
    static String label(String term) {
        if (!TextAnalyzer.isConcept(term)) return term;
        String c = term.substring(1);
        return switch (c) {
            case "controlno" -> "control number";
            case "notfound" -> "not found";
            case "signin" -> "sign in";
            case "signup" -> "sign up";
            case "nin" -> "national ID";
            case "id" -> "ID card";
            default -> c;
        };
    }

    private static Map<String, Double> vector(Knowledge k, Map<String, Integer> terms) {
        Map<String, Double> v = new HashMap<>();
        terms.forEach((t, n) -> {
            double idf = k.categories().idf(t) + 0.5;
            v.put(t, n * idf * (TextAnalyzer.isConcept(t) ? 1.3 : 1));
        });
        return v;
    }

    private static double cosine(Map<String, Double> a, Map<String, Double> b) {
        double dot = 0, na = 0, nb = 0;
        for (Map.Entry<String, Double> e : a.entrySet()) {
            na += e.getValue() * e.getValue();
            Double o = b.get(e.getKey());
            if (o != null) dot += e.getValue() * o;
        }
        for (double x : b.values()) nb += x * x;
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    // ------------------------------------------------------------------ the knowledge, kept per institution until it changes

    private Knowledge knowledge() {
        Long tenant = TenantContext.requireTenantId();
        String fp = jdbc.queryForObject("""
                select (select count(*) || ':' || coalesce(max(updated_at)::text, '') from helpdesk_articles) || '|' ||
                       (select count(*) || ':' || coalesce(max(id), 0) from helpdesk_learned_answers) || '|' ||
                       (select count(*) || ':' || coalesce(max(id), 0) from helpdesk_tickets) || '|' ||
                       (select count(*) || ':' || coalesce(max(updated_at)::text, '') from helpdesk_categories)""", String.class);
        Knowledge k = cache.get(tenant);
        if (k != null && k.fingerprint().equals(fp)) return k;
        k = build(fp);
        cache.put(tenant, k);
        return k;
    }

    private Knowledge build(String fp) {
        List<Article> articles = jdbc.query("""
                select a.id, a.category_id, c.name, a.title, a.body, a.for_applicants, a.for_students, a.for_staff, a.for_public
                from helpdesk_articles a left join helpdesk_categories c on c.id = a.category_id where a.published""", (rs, i) -> {
            Set<String> aud = new java.util.HashSet<>();
            if (rs.getBoolean(6)) aud.add("APPLICANT");
            if (rs.getBoolean(7)) aud.add("STUDENT");
            if (rs.getBoolean(8)) aud.add("STAFF");
            if (rs.getBoolean(9)) aud.add("PUBLIC");
            return new Article(rs.getLong(1), rs.getObject(2) == null ? null : rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), aud);
        });
        List<Doc<Article>> articleDocs = articles.stream().map(a -> new Doc<>(a, MatchIndex.weighted(a.title(), 2.5, a.body(), 1))).toList();

        List<Learned> learned = jdbc.query("""
                select l.id, l.ticket_id, t.ticket_no, l.category_id, l.question, l.answer, l.confirmed, l.used
                from helpdesk_learned_answers l join helpdesk_tickets t on t.id = l.ticket_id order by l.id desc limit 2000""",
                (rs, i) -> new Learned(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getInt(8)));
        List<Doc<Learned>> learnedDocs = learned.stream().map(l -> new Doc<>(l, MatchIndex.weighted(l.question(), 1.5, l.answer(), 0.5))).toList();

        // each category: its name and description, its answers, and what people asked in it (the requests teach the guess)
        Map<Long, Category> cats = new LinkedHashMap<>();
        Map<Long, Map<String, Double>> catTerms = new HashMap<>();
        jdbc.query("select id, name, description from helpdesk_categories where active", rs -> {
            Long id = rs.getLong(1);
            cats.put(id, new Category(id, rs.getString(2)));
            catTerms.put(id, MatchIndex.weighted(rs.getString(2), 3, rs.getString(3), 2));
        });
        for (Article a : articles) {
            if (a.categoryId() != null && catTerms.containsKey(a.categoryId())) {
                MatchIndex.weighted(a.title(), 1, a.body(), 0.5).forEach((t, w) -> catTerms.get(a.categoryId()).merge(t, w, Double::sum));
            }
        }
        jdbc.query("""
                select t.category_id, t.subject,
                       (select m.body from helpdesk_messages m where m.ticket_id = t.id and m.author_type = 'REQUESTER' order by m.created_at, m.id limit 1)
                from helpdesk_tickets t order by t.id desc limit 3000""", rs -> {
            Map<String, Double> into = catTerms.get(rs.getLong(1));
            if (into != null) MatchIndex.weighted(rs.getString(2), 1, rs.getString(3), 0.5).forEach((t, w) -> into.merge(t, w, Double::sum));
        });
        List<Doc<Category>> catDocs = cats.values().stream().map(c -> new Doc<>(c, catTerms.get(c.id()))).toList();
        return new Knowledge(fp, new MatchIndex<>(new ArrayList<>(articleDocs)), new MatchIndex<>(new ArrayList<>(learnedDocs)), new MatchIndex<>(new ArrayList<>(catDocs)));
    }

    // ------------------------------------------------------------------ helpers

    private static ArticleHit articleHit(Hit<Article> h) {
        Article a = h.item();
        return new ArticleHit(a.id(), a.title(), a.body(), a.categoryId(), a.categoryName(), round(h.confidence()));
    }

    private static int meaningful(Map<String, Integer> q) {
        return (int) q.keySet().stream().filter(t -> !t.matches("\\d+")).count();
    }

    static String join(String a, String b) {
        return ((a == null ? "" : a) + "\n" + (b == null ? "" : b)).trim();
    }

    static double round(double d) {
        return BigDecimal.valueOf(d).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
