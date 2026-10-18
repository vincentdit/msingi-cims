package tz.co.msingi.cims.helpdesk.assistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small ranked-retrieval index (BM25) over documents made of {@link TextAnalyzer} terms. A query term that is in no document
 * is matched to the closest known word by character trigrams, so a word typed with a mistake still counts (a little less).
 * Each match carries a confidence from 0 to 1: how much of what the question is about the document covers, and how strongly.
 */
public final class MatchIndex<T> {

    public record Doc<T>(T item, Map<String, Double> terms) {
    }

    public record Hit<T>(T item, double score, double coverage, double confidence) {
    }

    private static final double K1 = 1.2, B = 0.75, CONCEPT_WEIGHT = 1.5, FUZZY_WEIGHT = 0.6;

    private final List<Doc<T>> docs;
    private final Map<String, Integer> df = new HashMap<>();
    private final Map<String, Set<String>> grams = new HashMap<>();
    private final double avgLength;

    public MatchIndex(List<Doc<T>> docs) {
        this.docs = docs;
        double total = 0;
        for (Doc<T> d : docs) {
            for (String t : d.terms().keySet()) df.merge(t, 1, Integer::sum);
            total += length(d);
        }
        avgLength = docs.isEmpty() ? 1 : Math.max(1, total / docs.size());
        for (String t : df.keySet()) if (!TextAnalyzer.isConcept(t) && t.length() >= 4) grams.put(t, TextAnalyzer.trigrams(t));
    }

    public int size() {
        return docs.size();
    }

    public double idf(String term) {
        int n = df.getOrDefault(term, 0);
        return Math.log(1 + (docs.size() - n + 0.5) / (n + 0.5));
    }

    /** The query's terms with weights, typing mistakes mapped to known words; {@code unknown} counts the words no document has. */
    public record Query(Map<String, Double> terms, int unknown) {
    }

    public Query query(Map<String, Integer> raw) {
        Map<String, Double> q = new HashMap<>();
        int unknown = 0;
        for (Map.Entry<String, Integer> e : raw.entrySet()) {
            String t = e.getKey();
            double w = TextAnalyzer.isConcept(t) ? CONCEPT_WEIGHT : 1.0;
            if (df.containsKey(t)) {
                q.merge(t, w, Double::sum);
            } else if (!TextAnalyzer.isConcept(t) && t.length() >= 4) {
                Set<String> g = TextAnalyzer.trigrams(t);
                String best = null;
                double bestSim = 0.45;
                for (Map.Entry<String, Set<String>> k : grams.entrySet()) {
                    double s = TextAnalyzer.similarity(g, k.getValue());
                    if (s > bestSim) { bestSim = s; best = k.getKey(); }
                }
                if (best != null) q.merge(best, FUZZY_WEIGHT, Double::sum);
                else unknown++;
            } else {
                unknown++;
            }
        }
        return new Query(q, unknown);
    }

    public List<Hit<T>> search(Map<String, Integer> raw, int limit) {
        Query query = query(raw);
        Map<String, Double> q = query.terms();
        List<Hit<T>> hits = new ArrayList<>();
        if (q.isEmpty()) return hits;
        double wanted = 0;
        for (Map.Entry<String, Double> e : q.entrySet()) wanted += idf(e.getKey()) * e.getValue();
        // words no answer mentions (a module code, a lecturer, a day) are part of the question too: they lower the coverage
        wanted += query.unknown() * 0.5 * idf("\u0000none");
        for (Doc<T> d : docs) {
            double score = 0, covered = 0, len = length(d);
            for (Map.Entry<String, Double> e : q.entrySet()) {
                Double tf = d.terms().get(e.getKey());
                if (tf == null) continue;
                double idf = idf(e.getKey());
                score += e.getValue() * idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * len / avgLength));
                covered += idf * e.getValue();
            }
            if (score <= 0) continue;
            double coverage = wanted <= 0 ? 0 : covered / wanted;
            double confidence = Math.min(1, 0.65 * coverage + 0.35 * (score / (score + 6)));
            hits.add(new Hit<>(d.item(), score, coverage, confidence));
        }
        hits.sort(Comparator.comparingDouble((Hit<T> h) -> -h.confidence()).thenComparingDouble(h -> -h.score()));
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    private static double length(Doc<?> d) {
        double n = 0;
        for (double v : d.terms().values()) n += v;
        return n;
    }

    /** Terms of several texts, each with its weight (a title counts more than a body). */
    public static Map<String, Double> weighted(Object... textAndWeight) {
        Map<String, Double> out = new HashMap<>();
        for (int i = 0; i + 1 < textAndWeight.length; i += 2) {
            String text = (String) textAndWeight[i];
            double w = ((Number) textAndWeight[i + 1]).doubleValue();
            if (text == null) continue;
            TextAnalyzer.terms(text).forEach((t, n) -> out.merge(t, n * w, Double::sum));
        }
        return out;
    }
}
