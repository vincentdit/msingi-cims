package tz.co.msingi.cims.integration.nactvet;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Development NACTVET particulars: a deterministic NTA award for any registration number, without names, so it is
 * accepted for whoever looks it up. Registration numbers containing "/0000/" are not found and those containing
 * "/9999/" belong to someone else (for the name check). The NTA level is the digit after "NS" when it is 4–8
 * (NS6123/... → level 6), otherwise 4.
 */
@Component
@ConditionalOnProperty(prefix = "cims.nactvet.awards", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedNactvetAwardClient implements NactvetAwardClient {

    private static final String[][] PROGRAMMES = {
            {"Basic Technician Certificate in Information Technology", "4"}, {"Technician Certificate in Information Technology", "5"},
            {"Ordinary Diploma in Information Technology", "6"}, {"Ordinary Diploma in Accountancy", "6"},
            {"Basic Technician Certificate in Clinical Medicine", "4"}, {"Technician Certificate in Procurement and Supply", "5"}};

    @Override
    public Optional<NtaAward> fetchAward(String registrationNumber, int graduationYear) {
        String reg = registrationNumber.trim().toUpperCase();
        if (reg.contains("/0000/")) return Optional.empty();
        Random r = new Random((reg + graduationYear).hashCode());
        Integer level = reg.length() > 2 ? NactvetAwardClient.level(reg.substring(2, 3)) : null;
        int lvl = level == null ? 4 : level;
        String programme = java.util.Arrays.stream(PROGRAMMES).filter(p -> p[1].equals(String.valueOf(lvl))).map(p -> p[0]).findFirst()
                .orElse("Technician award (NTA Level " + lvl + ")");
        BigDecimal gpa = BigDecimal.valueOf(2 + r.nextInt(30) / 10.0).setScale(1, RoundingMode.HALF_UP);
        String classAward = gpa.doubleValue() >= 3.5 ? "UPPER SECOND" : gpa.doubleValue() >= 2.7 ? "LOWER SECOND" : "PASS";
        boolean someoneElse = reg.contains("/9999/");
        return Optional.of(new NtaAward(reg, graduationYear, someoneElse ? "Someone" : null, null, someoneElse ? "Else" : null, null,
                "Simulated Technical Institute", programme, lvl, gpa, classAward, "PASS", null,
                List.of(new Module("GST0" + lvl + "101", "Communication Skills", "B", 6, "1"),
                        new Module("ITT0" + lvl + "102", "Computer Applications", "B+", 9, "1"),
                        new Module("ITT0" + lvl + "203", "Introduction to Programming", "C", 12, "2"))));
    }
}
