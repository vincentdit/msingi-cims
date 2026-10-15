package tz.co.msingi.cims.platform.tenancy;

import java.util.List;
import java.util.Locale;

/**
 * NACTVET's ownership categories as CIMS stores them: PUBLIC (government and parastatal), PRIVATE and FBO (faith-based
 * organisation). Ownership decides how fees are collected: only public institutions use GePG.
 */
public final class Ownership {

    public static final String PUBLIC = "PUBLIC", PRIVATE = "PRIVATE", FBO = "FBO";
    public static final String RULE = "PUBLIC|PRIVATE|FBO";

    private static final List<String> GOVERNMENT = List.of("gov", "public", "parastatal", "state", "ministry", "local authorit");
    private static final List<String> FAITH = List.of("fbo", "faith", "relig", "church", "mission", "catholic", "lutheran", "anglican",
            "moravian", "adventist", "pentecost", "evangel", "christian", "diocese", "islam", "muslim", "baptist", "mennonite");

    private Ownership() {
    }

    /** Maps the ownership text NACTVET publishes ("Government", "Private", "FBO", "Faith Based", …) to a CIMS category. */
    public static String fromNactvet(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (FAITH.stream().anyMatch(t::contains)) return FBO;
        if (GOVERNMENT.stream().anyMatch(t::contains)) return PUBLIC;
        return PRIVATE;
    }

    public static String label(String ownership) {
        return switch (ownership == null ? "" : ownership) {
            case PUBLIC -> "Public (Government)";
            case FBO -> "Faith-based (FBO)";
            default -> "Private";
        };
    }
}
