package tz.co.msingi.cims.admission.letter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Joining instructions for an admission window, printed with every admission letter (FR-ADM-13).
 * Stored as JSON on the window; a snapshot is copied into each letter when it is issued.
 */
public record JoiningInstructions(
        @NotNull LocalDate reportingDate,
        @Pattern(regexp = "^$|^([01]\\d|2[0-3]):[0-5]\\d$", message = "use HH:mm, e.g. 08:00") String reportingTime,
        @NotBlank @Size(max = 200) String reportingVenue,
        LocalDate registrationDeadline,
        @Size(max = 20) List<@NotBlank @Size(max = 200) String> documentsToBring,
        @Size(max = 30) List<@NotBlank @Size(max = 200) String> itemsToBring,
        @Size(max = 1500) String feesNote,
        @Size(max = 10) List<@NotBlank @Size(max = 400) String> notes,
        @NotBlank @Size(max = 120) String signatoryName,
        @NotBlank @Size(max = 120) String signatoryTitle) {

    public JoiningInstructions {
        documentsToBring = documentsToBring == null ? List.of() : List.copyOf(documentsToBring);
        itemsToBring = itemsToBring == null ? List.of() : List.copyOf(itemsToBring);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
