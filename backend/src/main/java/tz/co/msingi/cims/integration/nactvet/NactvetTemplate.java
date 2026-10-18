package tz.co.msingi.cims.integration.nactvet;

import tz.co.msingi.cims.integration.nactvet.NactvetGateway.Background;
import tz.co.msingi.cims.integration.nactvet.NactvetGateway.Candidate;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NACTVET's applicant upload template (the {@code particulars} of NACTVET's upload API, as NOAS sends them): the same fields
 * go to NACTVET by API and into the file the admissions office uploads on the NACTVET portal. Field names are exactly as
 * NACTVET spells them, including {@code impairement}; dates of birth are {@code d-MM-yyyy} and gender is Male or Female.
 */
public final class NactvetTemplate {

    public static final DateTimeFormatter DOB = DateTimeFormatter.ofPattern("d-MM-yyyy");

    /** The particulars fields, in NACTVET's order. */
    public static final List<String> FIELDS = List.of("firstname", "secondname", "surname", "DOB", "gender", "impairement",
            "form_four_indexnumber", "form_four_year", "form_six_indexnumber", "form_six_year", "NTA4_reg", "NTA4_grad_year",
            "NTA5_reg", "NTA5_grad_year", "email_address", "mobile_number", "address", "region", "district", "nationality",
            "next_kin_name", "next_kin_address", "next_kin_email_address", "next_kin_phone", "next_kin_region", "next_kin_relation");

    private NactvetTemplate() {
    }

    /** One applicant's particulars, every field present (empty when unknown), in {@link #FIELDS} order. */
    public static Map<String, Object> particulars(Candidate c) {
        Background b = c.background() == null ? Background.NONE : c.background();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("firstname", nz(c.firstName()));
        m.put("secondname", nz(c.middleName()));
        m.put("surname", nz(c.surname()));
        m.put("DOB", c.dateOfBirth() == null ? "" : c.dateOfBirth().format(DOB));
        m.put("gender", gender(c.sex()));
        m.put("impairement", b.disability() == null || b.disability().isBlank() ? "None" : b.disability().trim());
        m.put("form_four_indexnumber", nz(c.indexNumber()));
        m.put("form_four_year", year(c.examYear()));
        m.put("form_six_indexnumber", nz(b.formSixIndex()));
        m.put("form_six_year", year(b.formSixYear()));
        m.put("NTA4_reg", nz(b.nta4Reg()));
        m.put("NTA4_grad_year", year(b.nta4Year()));
        m.put("NTA5_reg", nz(b.nta5Reg()));
        m.put("NTA5_grad_year", year(b.nta5Year()));
        m.put("email_address", nz(b.email()));
        m.put("mobile_number", nz(c.phone()));
        m.put("address", nz(b.address()));
        m.put("region", nz(b.region()));
        m.put("district", nz(b.district()));
        m.put("nationality", nz(c.nationality()));
        m.put("next_kin_name", nz(b.nextOfKinName()));
        m.put("next_kin_address", nz(b.nextOfKinAddress()));
        m.put("next_kin_email_address", nz(b.nextOfKinEmail()));
        m.put("next_kin_phone", nz(b.nextOfKinPhone()));
        m.put("next_kin_region", nz(b.nextOfKinRegion()));
        m.put("next_kin_relation", nz(b.nextOfKinRelation()));
        return m;
    }

    /** M or F as NACTVET writes it: Male or Female. */
    public static String gender(String sex) {
        if (sex == null) return "";
        return switch (sex.trim().toUpperCase(Locale.ROOT)) {
            case "M", "MALE" -> "Male";
            case "F", "FEMALE" -> "Female";
            default -> sex.trim();
        };
    }

    private static String year(Integer y) {
        return y == null ? "" : String.valueOf(y);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
