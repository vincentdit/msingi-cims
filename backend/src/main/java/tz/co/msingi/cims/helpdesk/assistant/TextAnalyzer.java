package tz.co.msingi.cims.helpdesk.assistant;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns English and Kiswahili text into terms the assistant can compare: lower case without accents or punctuation, common
 * words dropped, light stemming (English endings; Kiswahili subject and tense prefixes such as "nime-", "siku-"), and
 * <em>concepts</em>: words and phrases with the same meaning in either language ("malipo", "nimelipa", "paid", "payment" all add
 * the concept {@code ~pay}). Concepts let a question in Kiswahili find an answer written in English, and the reverse.
 */
public final class TextAnalyzer {

    private TextAnalyzer() {
    }

    private static final Set<String> STOP = Set.copyOf(java.util.Arrays.asList(
            // English
            "a", "an", "the", "and", "or", "but", "if", "then", "so", "of", "to", "in", "on", "at", "by", "for", "with", "from", "as", "is", "are",
            "was", "were", "be", "been", "am", "it", "its", "this", "that", "these", "those", "i", "me", "my", "we", "our", "you", "your", "he",
            "she", "they", "them", "their", "do", "does", "did", "have", "has", "had", "can", "could", "will", "would", "should", "please", "help",
            "hello", "hi", "dear", "sir", "madam", "thanks", "thank", "what", "how", "why", "when", "where", "which", "who", "there", "here",
            "about", "into", "up", "out", "also", "just", "still", "yet", "very", "some", "any", "all", "get", "got", "need", "want", "know",
            // Kiswahili
            "na", "ya", "wa", "za", "la", "cha", "vya", "kwa", "katika", "kwenye", "ni", "si", "hii", "huu", "hiyo", "hilo", "hicho", "ile", "yangu",
            "wangu", "langu", "changu", "zangu", "yako", "wako", "lako", "yetu", "mimi", "wewe", "sisi", "nyinyi", "habari", "tafadhali",
            "naomba", "nisaidie", "msaada", "asante", "shikamoo", "ndugu", "bwana", "bibi", "kama", "lakini", "au", "pia", "tu", "bado",
            "sana", "kuna", "hakuna", "nini", "vipi", "gani", "lini", "wapi", "kwanini", "je", "hapa", "pale", "huko", "hivyo",
            "nataka", "ninataka", "nataka", "nifanye", "nifanyeje", "nifanyaje", "naweza", "nawezaje", "inakuwaje", "imekuwaje", "sasa", "leo",
            "jana", "kesho", "wiki", "mara", "kila", "hata", "bila", "baada", "kabla", "mpaka", "hadi", "kwamba", "ili", "kuwa", "nina", "una",
            // English words that carry no topic of their own
            "not", "no", "never", "cannot", "cant", "dont", "didnt", "doesnt", "isnt", "wont", "still", "yet", "again", "today", "yesterday",
            "tomorrow", "now", "already", "since", "after", "before", "week", "day", "days", "time", "times", "way", "try", "tried", "trying", "three", "twice", "several",
            "jaribu", "kujaribu", "nimejaribu", "nilijaribu", "najaribu", "tumejaribu", "moja", "mbili", "tatu", "nne", "tano", "kadhaa",
            "ask", "asking", "asked", "question", "questions", "uliza", "kuuliza", "nauliza", "swali", "maswali"));

    /** Words and phrases with one meaning in English and Kiswahili. Order matters only for phrases (longest first within a group). */
    private static final String[][] CONCEPTS = {
            {"~pay", "pay", "paid", "paying", "payment", "payments", "malipo", "lipa", "kulipa", "nimelipa", "nililipa", "amelipa", "imelipwa", "kulipia", "lipia", "nimelipia"},
            {"~controlno", "control number", "control no", "controlnumber", "namba ya udhibiti", "namba ya malipo", "payment reference", "reference number", "kumbukumbu namba"},
            {"~fee", "fee", "fees", "tuition", "ada", "karo"},
            {"~receipt", "receipt", "receipts", "risiti", "stakabadhi"},
            {"~sms", "sms", "text message", "message", "messages", "ujumbe", "meseji"},
            {"~code", "code", "codes", "otp", "verification code", "pin", "nambari ya siri", "msimbo", "namba ya siri"},
            {"~password", "password", "passwords", "nenosiri", "neno siri", "neno la siri"},
            {"~signin", "sign in", "signin", "log in", "login", "logon", "ingia", "kuingia", "siwezi kuingia", "access"},
            {"~account", "account", "accounts", "akaunti", "profile", "wasifu"},
            {"~signup", "sign up", "signup", "register account", "create account", "creating account", "jisajili", "kujisajili", "fungua akaunti", "kufungua akaunti"},
            {"~arrive", "arrive", "arrived", "arriving", "receive", "received", "receiving", "pokea", "kupokea", "sijapokea", "sikupokea", "fika", "kufika", "haijafika", "hazifiki", "haifiki", "haufiki", "hakifiki", "haukufika", "haikufika", "hazikufika", "haujafika"},
            {"~notfound", "not found", "cannot find", "can not find", "missing", "does not exist", "doesnt exist", "haipatikani", "haijapatikana", "sioni"},
            {"~show", "not showing", "does not show", "doesnt show", "not reflected", "show", "shows", "showing", "appear", "appears", "reflect", "reflected",
                    "onekana", "kuonekana", "haionekani", "hayaonekani", "haijaonekana", "onyesha"},
            {"~portal", "portal", "website", "web site", "site", "system", "online", "tovuti", "mfumo", "mtandaoni"},
            {"~index", "index number", "index no", "index", "examination number", "exam number", "namba ya mtihani", "candidate number"},
            {"~necta", "necta", "csee", "acsee", "form four", "form six", "kidato cha nne", "kidato cha sita"},
            {"~results", "result", "results", "grade", "grades", "marks", "matokeo", "alama", "gpa"},
            {"~exam", "exam", "exams", "examination", "examinations", "test", "mtihani", "mitihani"},
            {"~transcript", "transcript", "transcripts", "statement of results", "nakala ya matokeo", "certificate", "cheti", "vyeti"},
            {"~apply", "apply", "applied", "applying", "application", "applications", "omba", "kuomba", "maombi", "nimeomba", "admission", "udahili", "dahili"},
            {"~programme", "programme", "programmes", "program", "programs", "course", "courses", "kozi", "programu"},
            {"~choice", "choice", "choices", "choose", "chose", "selection", "chaguo", "machaguo", "chagua"},
            {"~selected", "selected", "chosen", "admitted", "offer", "offers", "nimechaguliwa", "kuchaguliwa", "nafasi"},
            {"~letter", "letter", "admission letter", "joining instructions", "barua", "barua ya udahili"},
            {"~document", "document", "documents", "upload", "uploaded", "attach", "nyaraka", "hati", "pakia", "kupakia"},
            {"~register", "registration", "register", "registered", "enrol", "enroll", "enrolment", "usajili", "sajili", "kusajili", "nimesajili"},
            {"~semester", "semester", "term", "muhula"},
            {"~hostel", "hostel", "hostels", "room", "rooms", "bed", "accommodation", "dormitory", "hosteli", "bweni", "chumba", "kitanda", "malazi"},
            {"~repair", "repair", "repairs", "broken", "fix", "matengenezo", "imeharibika", "rekebisha"},
            {"~loan", "loan", "loans", "heslb", "sponsor", "sponsorship", "mkopo", "mikopo", "mfadhili", "ufadhili", "bodi ya mikopo"},
            {"~id", "id card", "identity card", "student id", "kitambulisho", "vitambulisho"},
            {"~nin", "nida", "national id", "nin", "kitambulisho cha taifa"},
            {"~insurance", "insurance", "nhif", "health cover", "bima", "bima ya afya"},
            {"~phone", "phone", "mobile", "number", "simu", "namba ya simu"},
            {"~email", "email", "e-mail", "mail", "barua pepe"},
            {"~wrong", "wrong", "incorrect", "mistake", "error", "typo", "kosa", "makosa", "si sahihi", "imekosewa"},
            {"~change", "change", "changed", "update", "correct", "correction", "edit", "badilisha", "kubadilisha", "rekebisha", "marekebisho", "sahihisha"},
            {"~name", "name", "names", "jina", "majina"},
            {"~refund", "refund", "refunds", "money back", "rejesho", "marejesho", "rudishiwa"},
            {"~transfer", "transfer", "move to", "change programme", "hamia", "kuhamia", "uhamisho"},
            {"~deadline", "deadline", "closing date", "last date", "closed", "close", "mwisho", "tarehe ya mwisho", "imefungwa"},
            {"~timetable", "timetable", "schedule", "ratiba"},
            {"~attendance", "attendance", "absent", "mahudhurio", "hudhuria"},
            {"~appeal", "appeal", "remark", "re-mark", "rufaa", "usahihishaji"},
            {"~clearance", "clearance", "clear", "graduation", "graduate", "mahafali", "kuhitimu"},
            {"~ipt", "ipt", "field", "industrial training", "practical training", "mafunzo kwa vitendo"},
            {"~forgot", "forgot", "forgotten", "forget", "lost", "nimesahau", "sahau", "kusahau", "nimepoteza"},
            {"~locked", "locked", "blocked", "disabled", "imefungwa akaunti", "imezuiwa", "zuiwa"},
            {"~slow", "slow", "loading", "not loading", "not working", "doesnt work", "does not work", "fails", "failed", "inakataa", "haifanyi kazi", "haifunguki"},
    };

    private static final Map<String, String> WORD_CONCEPT = new HashMap<>();
    private static final List<String[]> PHRASES = new ArrayList<>();   // {normalised phrase, concept}

    static {
        for (String[] g : CONCEPTS) {
            for (int i = 1; i < g.length; i++) {
                String n = fold(g[i]);
                if (n.contains(" ")) PHRASES.add(new String[]{" " + n + " ", g[0]});
                else WORD_CONCEPT.putIfAbsent(n, g[0]);
            }
        }
        PHRASES.sort((a, b) -> b[0].length() - a[0].length());
    }

    /** Lower case, accents and punctuation removed, single spaces. */
    public static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        n = n.replace("'", "").replaceAll("[^a-z0-9]+", " ").trim();
        return n;
    }

    /** The terms of a text with how often each occurs: stems of the words that matter, and the concepts they carry. */
    public static Map<String, Integer> terms(String text) {
        Map<String, Integer> out = new LinkedHashMap<>();
        String folded = " " + fold(text).replaceAll("\\b(yangu|wangu|langu|changu|zangu|yako|wako|lako|my|your|the|a)\\b", " ").replaceAll("\\s+", " ").trim() + " ";
        // phrases first ("control number", "namba ya mtihani"), so their words do not also count apart
        for (String[] p : PHRASES) {
            int at;
            while ((at = folded.indexOf(p[0])) >= 0) {
                out.merge(p[1], 1, Integer::sum);
                folded = folded.substring(0, at) + " " + folded.substring(at + p[0].length() - 1);
            }
        }
        for (String w : folded.trim().split(" ")) {
            if (w.isEmpty() || STOP.contains(w)) continue;
            if (w.length() < 2) continue;
            if (w.matches("\\d+") && w.length() < 4) continue;
            String concept = WORD_CONCEPT.get(w);
            String stem = stem(w);
            if (concept == null) concept = WORD_CONCEPT.get(stem);
            if (concept == null) {
                String root = swahiliRoot(w);
                if (root != null) concept = WORD_CONCEPT.get(root);
            }
            // a word that carries a concept is represented by it ("malipo" and "paid" both by ~pay); other words by their stem
            if (concept != null) {
                out.merge(concept, 1, Integer::sum);
            } else {
                if (!STOP.contains(stem)) out.merge(stem, 1, Integer::sum);
                String near = closestConcept(w);        // a typing mistake in a known word
                if (near != null) out.merge(near, 1, Integer::sum);
            }
        }
        return out;
    }

    /** English endings removed (payments → payment → pay is left to the concepts; arriving → arriv). */
    static String stem(String w) {
        if (w.length() <= 4 || w.matches(".*\\d.*")) return w;
        if (w.endsWith("ies") && w.length() > 5) return w.substring(0, w.length() - 3) + "y";
        if (w.endsWith("ing") && w.length() > 6) return w.substring(0, w.length() - 3);
        if (w.endsWith("ed") && w.length() > 5) return w.substring(0, w.length() - 2);
        if (w.endsWith("ment") && w.length() > 7) return w.substring(0, w.length() - 4);
        if (w.endsWith("es") && w.length() > 5 && !w.endsWith("ses")) return w.substring(0, w.length() - 2);
        if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && w.length() > 4) return w.substring(0, w.length() - 1);
        return w;
    }

    /** "nimelipa" → "lipa", "sikupokea" → "pokea", "haijafika" → "fika": a Kiswahili verb without its subject and tense prefixes. */
    static String swahiliRoot(String w) {
        if (w.length() < 6) return null;
        String r = w.replaceFirst("^(hatu|hawa|ham|hu|ha|si|ni|tu|wa|u|a|m|i|zi|li|ki|vi)(me|li|na|ta|ku|ja|ki|ka|si)(ku)?", "");
        return r.length() >= 3 && !r.equals(w) ? r : null;
    }

    private static final Map<String, Set<String>> CONCEPT_GRAMS = new HashMap<>();

    static {
        WORD_CONCEPT.keySet().stream().filter(w -> w.length() >= 5).forEach(w -> CONCEPT_GRAMS.put(w, trigrams(w)));
    }

    /** The concept of the closest concept word, for a word typed with a mistake ("pasword" → ~password), or null. */
    public static String closestConcept(String w) {
        if (w.length() < 5) return null;
        Set<String> g = trigrams(w);
        String best = null;
        double bestSim = 0.5;
        for (Map.Entry<String, Set<String>> e : CONCEPT_GRAMS.entrySet()) {
            double s = similarity(g, e.getValue());
            if (s > bestSim) { bestSim = s; best = e.getKey(); }
        }
        return best == null ? null : WORD_CONCEPT.get(best);
    }

    /** Character trigrams of a word, for words typed with a mistake ("pasword", "contrl"). */
    public static Set<String> trigrams(String w) {
        Set<String> out = new HashSet<>();
        String p = "  " + w + " ";
        for (int i = 0; i + 3 <= p.length(); i++) out.add(p.substring(i, i + 3));
        return out;
    }

    public static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int common = 0;
        for (String x : a) if (b.contains(x)) common++;
        return (double) common / (a.size() + b.size() - common);
    }

    public static boolean isConcept(String term) {
        return term.startsWith("~");
    }
}
