package tz.co.msingi.cims.integration.nactvet;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A sample of the NACTVET catalogue for development and tests, shaped like the live answer: made-up institutions (registration
 * numbers REG/SIM/…) with certificate and diploma programmes under NACTVET's three boards. The platform console says when the
 * catalogue is simulated.
 */
@Component
@ConditionalOnProperty(prefix = "cims.nactvet.catalogue", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedNactvetCatalogueClient implements NactvetCatalogueClient {

    private record Inst(String id, String name, String code, String region, String district, String reg, String phone, String ownership,
                        List<String[]> fields) {
    }

    private static final String BTP = "Business, Tourism and Planning", HAS = "Health and Allied Sciences", SAT = "Science and Allied Technologies";

    /** field: board name, board code, category, programme field name, course abbreviation. */
    private static String[] f(String board, String boardCode, String category, String field, String abbr) {
        return new String[]{board, boardCode, category, field, abbr};
    }

    private static final List<Inst> INSTITUTIONS = List.of(
            new Inst("9101", "Nyota Institute of Business and ICT", "NIBI", "Dar es Salaam", "Kinondoni", "REG/SIM/101", "0754100101", "Private",
                    List.of(f(BTP, "BTP", "Accountancy", "Accountancy", "ACC"), f(SAT, "SAT", "Information Technology", "Information Technology", "IT"),
                            f(BTP, "BTP", "Procurement and Supplies", "Procurement and Supplies Management", "PSM"))),
            new Inst("9102", "Bahari Institute of Health and Allied Sciences", "BIHAS", "Tanga", "Tanga City", "REG/SIM/102", "0754100102", "Private",
                    List.of(f(HAS, "HAS", "Clinical Medicine", "Clinical Medicine", "CM"), f(HAS, "HAS", "Nursing and Midwifery", "Nursing and Midwifery", "NM"),
                            f(HAS, "HAS", "Medical Laboratory Sciences", "Medical Laboratory Sciences", "MLS"), f(HAS, "HAS", "Pharmaceutical Sciences", "Pharmaceutical Sciences", "PS"))),
            new Inst("9103", "Jabali Technical College", "JTC", "Arusha", "Arumeru", "REG/SIM/103", "0754100103", "Government",
                    List.of(f(SAT, "SAT", "Civil Engineering", "Civil Engineering", "CE"), f(SAT, "SAT", "Electrical Engineering", "Electrical Engineering", "EE"),
                            f(SAT, "SAT", "Mechanical Engineering", "Mechanical Engineering", "ME"), f(SAT, "SAT", "Information Technology", "Computer Science", "CS"))),
            new Inst("9104", "Kijani College of Agriculture and Livestock", "KCAL", "Morogoro", "Mvomero", "REG/SIM/104", "0754100104", "Government",
                    List.of(f(SAT, "SAT", "Agriculture", "General Agriculture", "AGR"), f(SAT, "SAT", "Animal Health", "Animal Health and Production", "AHP"))),
            new Inst("9105", "Mwangaza College of Community Development", "MCCD", "Dodoma", "Dodoma City", "REG/SIM/105", "0754100105", "Faith Based",
                    List.of(f(BTP, "BTP", "Community Development", "Community Development", "CD"), f(BTP, "BTP", "Social Work", "Social Work", "SW"),
                            f(BTP, "BTP", "Records Management", "Records, Archives and Information Management", "RAIM"))),
            new Inst("9106", "Safari Highlands Institute of Tourism", "SAHIT", "Kilimanjaro", "Moshi Municipal", "REG/SIM/106", "0754100106", "Private",
                    List.of(f(BTP, "BTP", "Tourism", "Tourism Management", "TM"), f(BTP, "BTP", "Hospitality", "Hotel Management", "HM"))),
            new Inst("9107", "Ziwa Polytechnic College", "ZPC", "Mwanza", "Ilemela", "REG/SIM/107", "0754100107", "Government",
                    List.of(f(SAT, "SAT", "Information Technology", "Information Technology", "IT"), f(BTP, "BTP", "Business Administration", "Business Administration", "BA"),
                            f(BTP, "BTP", "Accountancy", "Accountancy", "ACC"), f(SAT, "SAT", "Laboratory Technology", "Science and Laboratory Technology", "SLT"))));

    @Override
    public List<Offering> fetchOfferings() {
        List<Offering> out = new ArrayList<>();
        int pid = 50_000;
        for (Inst i : INSTITUTIONS) {
            for (String[] fd : i.fields()) {
                for (int level = 4; level <= 6; level++) {
                    pid++;
                    boolean health = fd[1].equals("HAS");
                    String prefix = switch (level) {
                        case 4 -> "Basic Technician Certificate in ";
                        case 5 -> "Technician Certificate in ";
                        default -> "Ordinary Diploma in ";
                    };
                    int capacity = level == 6 ? 120 : 80;
                    String necta = health ? "Chemistry:D,Biology:D,Physics:D" : level == 4 ? "Basic Mathematics:D,English Language:D" : null;
                    String fee = String.valueOf(level == 6 ? (health ? 1_650_000 : 1_250_000) : (health ? 1_400_000 : 950_000));
                    out.add(new Offering(i.id(), i.name(), i.code(), i.region(), i.district(), i.reg(), i.phone(), i.ownership(),
                            String.valueOf(pid), prefix + fd[3], "NTA" + level, fd[4] + level, fd[0], fd[1], fd[2], fd[1] + "0" + (level - 3), fee,
                            switch (level) {
                                case 4 -> "Certificate of Secondary Education Examination (CSEE) with at least four passes" + (health ? " in Chemistry, Biology and Physics" : "");
                                case 5 -> "Basic Technician Certificate (NTA Level 4) in " + fd[3] + " or a related field";
                                default -> "CSEE with at least four passes" + (health ? " including Chemistry, Biology and Physics" : " including Basic Mathematics and English")
                                        + ", or a Technician Certificate (NTA Level 5) in " + fd[3];
                            },
                            level == 5 ? "Minimum GPA of 2.0 at NTA Level 4" : "Four passes in CSEE; selection by merit",
                            necta, level == 5 ? "Basic Technician Certificate in " + fd[3] + ":2.0" : null,
                            level == 5 ? "2.0" : null, level == 5 ? "7" : null,
                            level == 6 ? 3 : 1, capacity / 2, capacity / 2, capacity));
                }
            }
        }
        return out;
    }
}
