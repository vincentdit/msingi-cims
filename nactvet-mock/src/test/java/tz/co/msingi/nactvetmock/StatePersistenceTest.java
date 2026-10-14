package tz.co.msingi.nactvetmock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tz.co.msingi.nactvetmock.VerificationStore.Candidate;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A restarted mock still knows the batches CIMS is waiting on, their outcomes and the confirmation codes. */
class StatePersistenceTest {

    @TempDir Path dir;
    final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void batchesDecisionsAndCodesSurviveARestart() {
        String file = dir.resolve("state.json").toString();
        VerificationStore before = new VerificationStore(0, file, json);
        String ref = before.submit("DEMO", List.of(
                new Candidate("APP-1", "S0101/0001", 2024, "Asha", null, "Mussa", "F", "2006-03-14", null, null, "NTA6-ODIT", null, 6, null),
                new Candidate("APP-3", "S0101/0003", 2024, "Juma", null, "Ali", "M", "2006-03-14", null, null, "NTA6-ODIT", null, 6, null))).batchRef();
        String code = before.confirmationCode("S0101/0003").orElseThrow();
        String vno = (String) before.outcomes(before.batch(ref).orElseThrow()).get(0).get("verification_number");

        VerificationStore after = new VerificationStore(0, file, json);
        assertThat(after.batch(ref)).isPresent();
        assertThat(after.outcomes(after.batch(ref).orElseThrow()).get(0).get("verification_number")).isEqualTo(vno);
        assertThat(after.confirmationCode("S0101/0003")).contains(code);
        assertThat(after.confirm("DEMO", "S0101/0003", code)).isEqualTo(VerificationStore.ConfirmResult.CONFIRMED);
        assertThat(after.submit("DEMO", List.of(
                new Candidate("APP-2", "S0101/0002", 2024, "Neema", null, "Said", "F", "2006-03-14", null, null, "NTA6-ODIT", null, 6, null)))
                .batchRef()).endsWith("-00002");

        after.reset();
        assertThat(new VerificationStore(0, file, json).batches()).isEmpty();
    }
}
