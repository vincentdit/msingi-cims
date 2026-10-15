package tz.co.msingi.cims.integration.muse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Port to MUSE, the government accounting system (FR-INT-07): a summarised journal of fee collections for a period. */
public interface MuseClient {

    /** Debit the cash (bank) account, credit revenue under each GFS code. */
    record Line(String account, String gfsCode, String description, BigDecimal debit, BigDecimal credit) {
    }

    record Journal(String voteCode, String batchRef, LocalDate periodFrom, LocalDate periodTo, LocalDate postingDate, String currency, List<Line> lines) {
    }

    /** POSTED (with MUSE's reference), DUPLICATE (already posted earlier, with that reference) or REJECTED (with the reason). */
    record PostResult(String status, String museRef, String message) {
    }

    PostResult post(Journal journal);
}
