package tz.co.msingi.cims.platform.branding;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantLookupService;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * How the public reaches the institution, set by its administrator: contact and admissions emails, up to four phone numbers,
 * the website, WhatsApp and the social media pages. Shown on the portal, the programme pages and the advert posters. The extra
 * details are kept with the institution's branding.
 */
@Service
@RequiredArgsConstructor
public class InstitutionProfileService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ]{9,16}$");
    private static final Pattern HANDLE = Pattern.compile("^@?[A-Za-z0-9._-]{2,60}$");
    static final List<String> SOCIAL = List.of("facebook", "instagram", "youtube", "tiktok", "x");
    private static final Map<String, String> SOCIAL_BASE = Map.of("facebook", "https://facebook.com/", "instagram", "https://instagram.com/",
            "youtube", "https://youtube.com/@", "tiktok", "https://tiktok.com/@", "x", "https://x.com/");

    private final TenantRepository tenants;
    private final TenantLookupService lookup;
    private final AuditService audit;

    public record Profile(String name, String contactEmail, String contactPhone, String admissionsEmail, List<String> phones, String website,
                          String whatsapp, String facebook, String instagram, String youtube, String tiktok, String x) {
    }

    @Transactional(readOnly = true)
    public Profile get() {
        Tenant t = tenant();
        Map<String, Object> b = t.getBranding();
        return new Profile(t.getName(), t.getContactEmail(), t.getContactPhone(), str(b, "admissionsEmail"), phones(b), str(b, "website"),
                str(b, "whatsapp"), str(b, "facebook"), str(b, "instagram"), str(b, "youtube"), str(b, "tiktok"), str(b, "x"));
    }

    @Transactional
    public Profile save(Profile p) {
        Tenant t = tenant();
        t.setContactEmail(email(p.contactEmail(), "contact"));
        t.setContactPhone(phone(p.contactPhone()));
        Map<String, Object> b = new HashMap<>(t.getBranding());
        put(b, "admissionsEmail", email(p.admissionsEmail(), "admissions"));
        List<String> phones = new ArrayList<>();
        for (String x : p.phones() == null ? List.<String>of() : p.phones()) {
            String ph = phone(x);
            if (ph != null && !phones.contains(ph) && !ph.equals(t.getContactPhone())) phones.add(ph);
        }
        if (phones.size() > 3) throw new BusinessRuleException("TOO_MANY_PHONES", "Give at most three more phone numbers.");
        if (phones.isEmpty()) b.remove("phones"); else b.put("phones", phones);
        put(b, "website", url(p.website()));
        put(b, "whatsapp", phone(p.whatsapp()));
        Map<String, String> given = new HashMap<>();
        given.put("facebook", p.facebook());
        given.put("instagram", p.instagram());
        given.put("youtube", p.youtube());
        given.put("tiktok", p.tiktok());
        given.put("x", p.x());
        for (String k : SOCIAL) put(b, k, social(k, given.get(k)));
        t.setBranding(b);
        tenants.saveAndFlush(t);
        lookup.evictAll();
        audit.record("INSTITUTION_PROFILE_CHANGED", "Tenant", t.getId(), Map.of());
        return get();
    }

    private Tenant tenant() {
        return tenants.findById(TenantContext.requireTenantId()).orElseThrow();
    }

    static String str(Map<String, Object> b, String k) {
        return b.get(k) instanceof String s && !s.isBlank() ? s : null;
    }

    @SuppressWarnings("unchecked")
    static List<String> phones(Map<String, Object> b) {
        return b.get("phones") instanceof List<?> l ? (List<String>) l : List.of();
    }

    private static void put(Map<String, Object> b, String k, String v) {
        if (v == null) b.remove(k); else b.put(k, v);
    }

    private static String email(String v, String what) {
        if (v == null || v.isBlank()) return null;
        String e = v.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(e).matches()) throw new BusinessRuleException("INVALID_EMAIL", "The " + what + " email address is not valid.");
        return e;
    }

    private static String phone(String v) {
        if (v == null || v.isBlank()) return null;
        String p = v.trim().replaceAll("[-.()]", " ").replaceAll("\\s+", " ");
        if (!PHONE.matcher(p).matches()) throw new BusinessRuleException("INVALID_PHONE", "The phone number " + v.trim() + " is not valid.");
        return p;
    }

    private static String url(String v) {
        if (v == null || v.isBlank()) return null;
        String u = v.trim();
        if (!u.matches("(?i)^https?://.*")) u = "https://" + u;
        if (!u.matches("(?i)^https?://[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(/\\S*)?$")) throw new BusinessRuleException("INVALID_URL", "The website address " + v.trim() + " is not valid.");
        return u;
    }

    /** A page address, or just the handle ("chuoafyankinga", "@chuoafyankinga"), made into the page address. */
    static String social(String network, String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        if (HANDLE.matcher(s).matches()) return SOCIAL_BASE.get(network) + s.replaceFirst("^@", "");
        return url(s);
    }
}
