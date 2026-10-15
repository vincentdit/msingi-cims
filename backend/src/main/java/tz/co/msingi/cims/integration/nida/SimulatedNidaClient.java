package tz.co.msingi.cims.integration.nida;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Development NIDA, with the gov-mocks rules: a NIN is 20 digits starting with the birth date (YYYYMMDD); ending in 9 it is
 * not found; with another birth date it does not match; otherwise it is valid.
 */
@Component
@ConditionalOnProperty(prefix = "cims.nida", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedNidaClient implements NidaClient {

    @Override
    public Result verify(String nin, String firstName, String surname, LocalDate dateOfBirth) {
        String n = NidaClient.normalise(nin);
        if (n == null) return new Result("INVALID", null, null);
        if (n.endsWith("9")) return new Result("NOT_FOUND", null, null);
        if (dateOfBirth == null || !n.substring(0, 8).equals(dateOfBirth.toString().replace("-", ""))) return new Result("MISMATCH", null, null);
        return new Result("VALID", firstName == null ? null : firstName.toUpperCase(), surname == null ? null : surname.toUpperCase());
    }
}
