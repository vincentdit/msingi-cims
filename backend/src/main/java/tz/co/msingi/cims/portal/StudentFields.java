package tz.co.msingi.cims.portal;

import tz.co.msingi.cims.admission.service.PhoneNumbers;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.registry.Student;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The personal details a student may ask to correct (FR-REG-08), with validation and normalisation.
 * Registration number, programme, sex, nationality and status are not here: they change only through Registry processes.
 */
final class StudentFields {

    private static final Pattern NAME = Pattern.compile("^[\\p{L}][\\p{L} '\\-.]{0,59}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");

    /** Changes Registry must check against original documents. */
    static final Set<String> DOCUMENTED = Set.of("firstName", "middleName", "surname", "dateOfBirth");

    record Field(String label, Function<Student, String> get, BiConsumer<Student, String> set, boolean required, int max, String kind) {
    }

    static final Map<String, Field> FIELDS = new LinkedHashMap<>();

    static {
        FIELDS.put("firstName", new Field("First name", Student::getFirstName, Student::setFirstName, true, 60, "name"));
        FIELDS.put("middleName", new Field("Middle name", Student::getMiddleName, Student::setMiddleName, false, 60, "name"));
        FIELDS.put("surname", new Field("Surname", Student::getSurname, Student::setSurname, true, 60, "name"));
        FIELDS.put("dateOfBirth", new Field("Date of birth", s -> s.getDateOfBirth() == null ? null : s.getDateOfBirth().toString(),
                (s, v) -> s.setDateOfBirth(LocalDate.parse(v)), true, 10, "date"));
        FIELDS.put("phone", new Field("Mobile number", Student::getPhone, Student::setPhone, true, 20, "phone"));
        FIELDS.put("email", new Field("Email", Student::getEmail, Student::setEmail, false, 120, "email"));
        FIELDS.put("region", new Field("Region", Student::getRegion, Student::setRegion, false, 60, "text"));
        FIELDS.put("district", new Field("District", Student::getDistrict, Student::setDistrict, false, 60, "text"));
        FIELDS.put("postalAddress", new Field("Postal address", Student::getPostalAddress, Student::setPostalAddress, false, 200, "text"));
        FIELDS.put("disability", new Field("Disability or support needs", Student::getDisability, Student::setDisability, false, 200, "text"));
        FIELDS.put("nextOfKinName", new Field("Next of kin", Student::getNextOfKinName, Student::setNextOfKinName, false, 120, "text"));
        FIELDS.put("nextOfKinPhone", new Field("Next of kin's phone", Student::getNextOfKinPhone, Student::setNextOfKinPhone, false, 20, "phone"));
        FIELDS.put("nextOfKinRelation", new Field("Relationship", Student::getNextOfKinRelation, Student::setNextOfKinRelation, false, 40, "text"));
    }

    private StudentFields() {
    }

    static Map<String, String> labels() {
        Map<String, String> out = new LinkedHashMap<>();
        FIELDS.forEach((k, f) -> out.put(k, f.label()));
        return out;
    }

    static String label(String key) {
        Field f = FIELDS.get(key);
        return f == null ? key : f.label();
    }

    /** Validated, normalised value (null for an emptied optional field). */
    static String normalise(String key, String raw) {
        Field f = FIELDS.get(key);
        if (f == null) throw new BusinessRuleException("FIELD_NOT_EDITABLE", "'" + key + "' cannot be changed through a request");
        String v = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (v.isEmpty()) {
            if (f.required()) throw new BusinessRuleException("INVALID_VALUE", f.label() + " cannot be empty");
            return null;
        }
        if (v.length() > f.max()) throw new BusinessRuleException("INVALID_VALUE", f.label() + " is too long");
        switch (f.kind()) {
            case "name" -> {
                if (!NAME.matcher(v).matches()) throw new BusinessRuleException("INVALID_VALUE", f.label() + " may contain letters, spaces, apostrophes and hyphens only");
            }
            case "date" -> {
                LocalDate d;
                try {
                    d = LocalDate.parse(v);
                } catch (DateTimeParseException e) {
                    throw new BusinessRuleException("INVALID_VALUE", f.label() + " must be a date (YYYY-MM-DD)");
                }
                LocalDate today = LocalDate.now();
                if (d.isAfter(today.minusYears(13)) || d.isBefore(today.minusYears(90))) {
                    throw new BusinessRuleException("INVALID_VALUE", f.label() + " is not plausible for a student");
                }
            }
            case "phone" -> {
                try {
                    v = PhoneNumbers.normalise(v);
                } catch (RuntimeException e) {
                    throw new BusinessRuleException("INVALID_VALUE", f.label() + " must be a Tanzanian mobile number, e.g. 0712 345 678");
                }
            }
            case "email" -> {
                if (!EMAIL.matcher(v).matches()) throw new BusinessRuleException("INVALID_VALUE", f.label() + " is not a valid email address");
                v = v.toLowerCase();
            }
            default -> {
            }
        }
        return v;
    }
}
