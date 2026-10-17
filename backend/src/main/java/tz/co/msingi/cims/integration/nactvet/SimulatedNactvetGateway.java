package tz.co.msingi.cims.integration.nactvet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.notification.SmsGateway;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Development NACTVET: outcomes are available as soon as they are fetched and depend only on the
 * candidate's index number, so a demo always behaves the same way. By the last digit of the index number:
 * <pre>
 *   …7  QUERIED   (name does not match NECTA) — verified once resubmitted with remarks
 *   …9  REJECTED  (does not meet minimum entry requirements)
 *   …3  VERIFIED, also admitted by another institution (confirmation code sent by SMS)
 *   any other digit  VERIFIED
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cims.nactvet", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedNactvetGateway implements NactvetGateway {

    private static final byte[] CODE_KEY = "cims-simulated-nactvet".getBytes(StandardCharsets.UTF_8);

    private final SmsGateway sms; // NACTVET's own SMS: not the institution's, so not in its message log

    @Override
    public String upload(Heading heading, List<Candidate> candidates) {
        log.info("[NACTVET simulated] upload of {} applicant(s) for programme {} ({} {}, level {}, payment {})", candidates.size(),
                heading.programmeId(), heading.intake(), heading.applicationYear(), heading.level(), heading.paymentReference());
        return "{\"code\":200,\"message\":\"Simulated: received " + candidates.size() + " applicant(s)\"}";
    }

    @Override
    public List<Outcome> fetchOutcomes(String applicationYear, String intake, Collection<Candidate> submitted) {
        List<Outcome> out = new ArrayList<>();
        for (Candidate c : submitted) {
            int digit = lastDigit(c.indexNumber());
            boolean resubmittedWithRemarks = c.remarks() != null && !c.remarks().isBlank();
            if (digit == 7 && !resubmittedWithRemarks) {
                out.add(new Outcome(c.applicationRef(), Result.QUERIED,
                        "Candidate name does not match NECTA records for this index number", null, false));
            } else if (digit == 9) {
                out.add(new Outcome(c.applicationRef(), Result.REJECTED,
                        "Does not meet NACTVET minimum entry requirements for " + c.programmeCode(), null, false));
            } else {
                boolean multiple = digit == 3;
                out.add(new Outcome(c.applicationRef(), Result.VERIFIED, null, verificationNo(c), multiple));
                if (multiple && c.phone() != null) {
                    // NACTVET itself sends the code to applicants admitted by more than one institution
                    sms.send(c.phone(), "NACTVET: You have been admitted by more than one institution. To confirm the one you choose, "
                            + "give it this confirmation code: " + code(c.indexNumber()), "NACTVET", null);
                }
            }
        }
        log.info("[NACTVET simulated] {} outcome(s) for {} {}", out.size(), applicationYear, intake);
        return out;
    }

    @Override
    public boolean confirmAdmission(String institutionCode, String indexNumber, String confirmationCode) {
        if (confirmationCode == null) return false;
        return MessageDigest.isEqual(code(indexNumber).getBytes(StandardCharsets.UTF_8),
                confirmationCode.trim().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Optional<String> developmentConfirmationCode(String indexNumber) {
        return Optional.of(code(indexNumber));
    }

    static String code(String indexNumber) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(CODE_KEY, "HmacSHA256"));
            byte[] h = mac.doFinal(indexNumber.trim().toUpperCase().getBytes(StandardCharsets.UTF_8));
            int n = ((h[0] & 0x7f) << 24 | (h[1] & 0xff) << 16 | (h[2] & 0xff) << 8 | (h[3] & 0xff)) % 1_000_000;
            return String.format("%06d", n);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String verificationNo(Candidate c) {
        int n = Math.floorMod((c.applicationRef() + c.indexNumber().toUpperCase() + c.programmeCode()).hashCode(), 1_000_000);
        return String.format("NV/%d/%06d", Year.now().getValue(), n);
    }

    static int lastDigit(String indexNumber) {
        for (int i = indexNumber.length() - 1; i >= 0; i--) {
            if (Character.isDigit(indexNumber.charAt(i))) return indexNumber.charAt(i) - '0';
        }
        return 0;
    }
}
