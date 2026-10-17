package tz.co.msingi.cims.admission.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The steps still to do before submitting are worded in plain English, never with internal field names. */
class BlockerWordingTest {

    @Test
    void missingDetailsAreNamedInPlainWords() {
        assertThat(ApplicationService.english(new AdmissionDtos.Todo("PROFILE", List.of("dateOfBirth", "nextOfKinName", "nextOfKinPhone"))))
                .isEqualTo("Complete your personal details: date of birth, next of kin's name, next of kin's phone");
        assertThat(ApplicationService.english(new AdmissionDtos.Todo("DOCUMENTS", List.of("Birth certificate")))).isEqualTo("Upload Birth certificate");
        assertThat(ApplicationService.english(new AdmissionDtos.Todo("CHANGE_CHOICE", List.of("Diploma in ICT", "needs four passes"))))
                .isEqualTo("Change your choice of Diploma in ICT: needs four passes");
    }
}
