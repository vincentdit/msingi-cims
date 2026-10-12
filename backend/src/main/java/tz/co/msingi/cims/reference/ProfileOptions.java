package tz.co.msingi.cims.reference;

import java.util.List;

/** Fixed choices on applicant and student profiles (as in NOAS). */
public final class ProfileOptions {

    private ProfileOptions() {
    }

    public static final List<String> DISABILITIES = List.of(
            "Visual impairment", "Hearing impairment", "Physical / mobility impairment", "Speech impairment", "Albinism",
            "Deaf-blind", "Autism", "Intellectual impairment", "Multiple impairments", "Other");

    public static final List<String> RELATIONSHIPS = List.of(
            "Father", "Mother", "Parent", "Guardian", "Spouse", "Brother", "Sister", "Uncle", "Aunt", "Grandfather", "Grandmother",
            "Son", "Daughter", "Relative", "Other");
}
