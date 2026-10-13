package tz.co.msingi.nactvetmock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A development stand-in for NACTVET admission verification (BP-04, FR-ADM-12, FR-INT-02), so CIMS's live HTTP adapter can
 * be run end to end without NACTVET. It speaks the NOAS style of NACTVET's api-out-service: a JSON POST carrying
 * {@code authorization} and {@code api_code}, answered with {@code {code, message, params}}.
 * <p>
 * The endpoint paths and field names are placeholders until NACTVET publishes its admission verification API; CIMS reads
 * them from configuration, so only configuration changes when the real ones are known. State is kept in memory.
 */
@SpringBootApplication
public class NactvetMockApplication {

    public static void main(String[] args) {
        SpringApplication.run(NactvetMockApplication.class, args);
    }
}
