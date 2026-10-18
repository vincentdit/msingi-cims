package tz.co.msingi.cims.admission.advert;

import tz.co.msingi.cims.admission.eligibility.EntryRules;
import tz.co.msingi.cims.admission.eligibility.EntryRules.Condition;
import tz.co.msingi.cims.admission.eligibility.EntryRules.Pathway;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The entry requirements in plain words, in English or Kiswahili, written from the rules CIMS checks (so an advert says exactly
 * what the system will accept). "Four CSEE passes at D or above, including Chemistry and Biology; or holder of NVA Level III."
 * / "Ufaulu wa masomo manne kidato cha nne kwa daraja D au zaidi, yakiwemo Chemistry na Biology; au mwenye NVA ngazi ya III."
 */
public final class EntryRulesText {

    private EntryRulesText() {
    }

    private static final String[] EN_NUM = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"};
    private static final String[] SW_NUM = {"", "moja", "mawili", "matatu", "manne", "matano", "sita", "saba", "manane", "tisa", "kumi"};
    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII"};

    /** One line per route; empty when there are no rules (open entry). */
    public static List<String> routes(EntryRules rules, boolean sw) {
        List<String> out = new ArrayList<>();
        if (rules == null) return out;
        for (Pathway p : rules.pathways()) {
            String s = pathway(p, sw);
            if (!s.isBlank() && !out.contains(s)) out.add(s);   // two routes that read the same are said once
        }
        return out;
    }

    /** All routes in one sentence: "…; or …" / "…; au …". */
    public static String sentence(EntryRules rules, boolean sw) {
        List<String> r = routes(rules, sw);
        if (r.isEmpty()) return rules != null && rules.summary() != null ? rules.summary() : "";
        String s = String.join(sw ? "; au " : "; or ", r);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1) + ".";
    }

    static String pathway(Pathway p, boolean sw) {
        List<String> main = new ArrayList<>();
        List<String> including = new ArrayList<>();
        for (Condition c : p.conditions()) {
            int n = c.count() == null || c.count() < 1 ? 1 : c.count();
            switch (c.type()) {
                case CSEE_MIN_PASSES -> main.add(sw
                        ? "ufaulu wa masomo " + num(n, true) + " kidato cha nne kwa daraja " + grade(c, "D") + " au zaidi"
                        : num(n, false) + " CSEE (Form Four) " + (n == 1 ? "pass" : "passes") + " at " + grade(c, "D") + " or above");
                case ACSEE_MIN_PRINCIPAL_PASSES -> main.add(sw
                        ? "ufaulu wa principal pass " + n + " kidato cha sita"
                        : num(n, false) + " ACSEE (Form Six) principal " + (n == 1 ? "pass" : "passes"));
                case CSEE_SUBJECT, ACSEE_SUBJECT -> {
                    boolean csee = c.type() == EntryRules.ConditionType.CSEE_SUBJECT;
                    String subjects = subjects(c.subjects(), n, sw);
                    String g = grade(c, csee ? "D" : "E");
                    if (main.isEmpty() || !csee && main.stream().noneMatch(m -> m.contains(sw ? "sita" : "ACSEE"))) {
                        main.add(sw ? (csee ? "ufaulu wa kidato cha nne katika " : "ufaulu wa kidato cha sita katika ") + subjects + " (daraja " + g + " au zaidi)"
                                : (csee ? "a CSEE pass in " : "an ACSEE pass in ") + subjects + " (" + g + " or above)");
                    } else {
                        including.add(subjects + (sw ? " (daraja " + g + " au zaidi)" : " (" + g + " or above)"));
                    }
                }
                case PRIOR_AWARD -> main.add(sw
                        ? "mwenye " + (c.family() == null ? "NTA au NVA" : c.family()) + " ngazi ya " + roman(c.minLevel()) + " au zaidi"
                        : "holder of " + (c.family() == null ? "an NTA or NVA award" : c.family()) + " Level " + roman(c.minLevel()) + " or above");
            }
        }
        String s = String.join(sw ? ", na " : ", and ", main);
        if (!including.isEmpty()) s += (sw ? ", yakiwemo " : ", including ") + String.join(sw ? " na " : " and ", including);
        return s;
    }

    private static String subjects(List<String> list, int n, boolean sw) {
        List<String> names = list == null ? List.of() : list.stream().map(EntryRulesText::title).toList();
        if (names.isEmpty()) return sw ? "masomo husika" : "the related subjects";
        if (names.size() == 1) return names.get(0);
        String last = names.get(names.size() - 1);
        String head = String.join(", ", names.subList(0, names.size() - 1));
        if (n >= names.size()) return head + (sw ? " na " : " and ") + last;
        return (sw ? "angalau " + n + " kati ya " : (n == 1 ? "any one of " : "any " + num(n, false) + " of ")) + head + (sw ? " au " : " or ") + last;
    }

    private static String grade(Condition c, String dflt) {
        return c.minGrade() == null || c.minGrade().isBlank() ? dflt : c.minGrade().trim().toUpperCase(Locale.ROOT);
    }

    private static String num(int n, boolean sw) {
        String[] t = sw ? SW_NUM : EN_NUM;
        return n < t.length ? t[n] : String.valueOf(n);
    }

    private static String roman(Integer level) {
        return level == null || level < 1 || level >= ROMAN.length ? String.valueOf(level) : ROMAN[level];
    }

    /** "BASIC MATHEMATICS" → "Basic Mathematics" (codes such as "B/MATH" stay as they are). */
    static String title(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.matches(".*[0-9/].*")) return t;
        StringBuilder b = new StringBuilder();
        for (String w : t.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w.equals("and") || w.equals("of") ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return b.toString();
    }
}
