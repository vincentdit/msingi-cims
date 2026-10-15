package tz.co.msingi.govmocks;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Development stand-ins for the government systems CIMS talks to, so its live adapters can be run end to end on a developer's
 * computer: GePG (signed XML: bills, control numbers, payments, reconciliation), HESLB (beneficiaries, disbursements,
 * registration data), NIDA (NIN verification), MUSE (fee postings) and TAMISEMI (selections).
 * <p>
 * GePG follows the shape of the GePG service provider specification. The HESLB, NIDA, MUSE and TAMISEMI endpoints are
 * placeholders until those bodies give the institution their interface documents; CIMS reads every path from configuration.
 * Everything is kept in memory. A dashboard at {@code /} shows what arrived and lets the developer pay bills, disburse loans
 * and add selections.
 */
@SpringBootApplication
public class GovMocksApplication {

    public static void main(String[] args) {
        SpringApplication.run(GovMocksApplication.class, args);
    }
}
