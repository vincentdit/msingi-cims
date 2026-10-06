package tz.co.msingi.cims.integration.nactvet;

import org.junit.jupiter.api.Test;
import tz.co.msingi.cims.integration.nactvet.NactvetGateway.Candidate;
import tz.co.msingi.cims.integration.nactvet.NactvetGateway.Outcome;
import tz.co.msingi.cims.integration.nactvet.NactvetGateway.Result;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedNactvetGatewayTest {

    final List<String> sent = new ArrayList<>();
    final SimulatedNactvetGateway gateway = new SimulatedNactvetGateway((phone, message) -> sent.add(phone + ": " + message));

    @Test
    void outcomeDependsOnTheLastDigitOfTheIndexNumber() {
        List<Outcome> out = gateway.fetchOutcomes("REF", List.of(c("A", "S0101/0001", null), c("B", "S0101/0007", null),
                c("C", "S0101/0009", null), c("D", "S0101/0013", null)));
        assertThat(out).extracting(Outcome::result).containsExactly(Result.VERIFIED, Result.QUERIED, Result.REJECTED, Result.VERIFIED);
        assertThat(out.get(0).verificationNo()).matches("NV/\\d{4}/\\d{6}");
        assertThat(out.get(0).multipleAdmission()).isFalse();
        assertThat(out.get(1).reason()).isNotBlank();
        assertThat(out.get(3).multipleAdmission()).isTrue();
        // NACTVET sends the confirmation code to the multiple-admission applicant only
        assertThat(sent).hasSize(1).first().asString().contains(SimulatedNactvetGateway.code("S0101/0013"));
    }

    @Test
    void queriedRecordIsVerifiedWhenResubmittedWithRemarks() {
        assertThat(gateway.fetchOutcomes("REF", List.of(c("B", "S0101/0007", "Name corrected"))).get(0).result())
                .isEqualTo(Result.VERIFIED);
    }

    @Test
    void confirmationCodeIsStableAndChecked() {
        String code = SimulatedNactvetGateway.code("s0101/0013 ");
        assertThat(code).hasSize(6).isEqualTo(SimulatedNactvetGateway.code("S0101/0013"));
        assertThat(gateway.confirmAdmission("REG/X/1", "S0101/0013", code)).isTrue();
        assertThat(gateway.confirmAdmission("REG/X/1", "S0101/0013", " " + code + " ")).isTrue();
        assertThat(gateway.confirmAdmission("REG/X/1", "S0101/0013", "000000".equals(code) ? "111111" : "000000")).isFalse();
        assertThat(gateway.confirmAdmission("REG/X/1", "S0101/0013", null)).isFalse();
        assertThat(gateway.developmentConfirmationCode("S0101/0013")).contains(code);
    }

    private static Candidate c(String ref, String index, String remarks) {
        return new Candidate(ref, index, 2025, "Asha", null, "Mussa", "F", LocalDate.of(2006, 1, 1), "Tanzanian", "255712345678",
                "NTA4-IT", "Basic Technician Certificate in IT", 4, remarks);
    }
}
