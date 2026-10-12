package tz.co.msingi.cims.reference;

import tz.co.msingi.cims.common.exception.BusinessRuleException;

import java.util.List;
import java.util.function.Supplier;

/**
 * A Tanzanian address chosen from region down to village/street, checked against {@link GeographyService}
 * and returned spelt as the data spells it. A level the data has no list for is taken as typed.
 */
public record Address(String region, String district, String ward, String street) {

    public static Address check(GeographyService geo, String who, String region, String district, String ward, String street) {
        String r = blank(region), d = blank(district), w = blank(ward), s = blank(street);
        if (r == null) {
            if (d != null || w != null || s != null) throw invalid(who, "region", "Choose the region first");
            return new Address(null, null, null, null);
        }
        if (!geo.loaded()) return new Address(r, d, w, s);
        String region1 = geo.region(r).orElseThrow(() -> invalid(who, "region", r + " is not a region of Tanzania"));
        String district1 = level(d, () -> geo.districts(region1), who, "district", "in " + region1, w != null || s != null);
        String ward1 = district1 == null ? null : level(w, () -> geo.wards(region1, district1), who, "ward", "in " + district1, s != null);
        String street1 = ward1 == null ? null : level(s, () -> geo.streets(region1, district1, ward1), who, "village / street", "in " + ward1, false);
        return new Address(region1, district1, ward1, street1);
    }

    private static String level(String value, Supplier<List<String>> options, String who, String what, String where, boolean lowerGiven) {
        if (value == null) {
            if (lowerGiven) throw invalid(who, what, "Choose the " + what + " first");
            return null;
        }
        List<String> list = options.get();
        if (list.isEmpty()) return value;
        return GeographyService.match(list, value).orElseThrow(() -> invalid(who, what, value + " is not a " + what + " " + where));
    }

    private static BusinessRuleException invalid(String who, String what, String message) {
        return new BusinessRuleException("INVALID_ADDRESS", (who.isEmpty() ? "" : who + ": ") + message);
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** One of {@code options} (ignoring case), the value already held, or nothing. */
    public static String choice(List<String> options, String value, String current, String code, String label) {
        String v = blank(value);
        if (v == null) return null;
        if (current != null && current.equalsIgnoreCase(v)) return current;
        return GeographyService.match(options, v).orElseThrow(() -> new BusinessRuleException(code, "Choose the " + label + " from the list"));
    }
}
