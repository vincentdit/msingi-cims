package tz.co.msingi.cims.integration.nactvet;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A made-up curriculum for development and tests, shaped like NACTVET's answer: five core modules and one elective for each level
 * and semester, coded from the programme id (SIM05101 …). Integration health says when it is simulated.
 */
@Component
@ConditionalOnProperty(prefix = "cims.nactvet.curriculum", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedNactvetCurriculumClient implements NactvetCurriculumClient {

    private static final String[] TITLES = {"Communication Skills", "Basic Computer Applications", "Principles of Practice", "Professional Ethics",
            "Applied Mathematics", "Entrepreneurship"};

    @Override
    public Semester fetch(String programmeId, String academicYear, String level, String semester, String intake) {
        if (programmeId == null || programmeId.isBlank()) return new Semester(null, null, List.of(), "programme_id is required");
        String lv = level.replaceAll("\\D", "");
        String sem = "ONE".equals(semester) ? "1" : "2";
        String prefix = "S" + programmeId.substring(0, Math.min(2, programmeId.length())).toUpperCase();
        List<ModuleRow> rows = new ArrayList<>();
        for (int i = 0; i < TITLES.length; i++) {
            rows.add(new ModuleRow(prefix + "0" + lv + sem + "0" + (i + 1), TITLES[i] + " (NTA " + lv + ")", BigDecimal.valueOf(i == 5 ? 6 : 10 + i),
                    i == 5, "credits,module_code,module_name,module_type"));
        }
        return new Semester(prefix + "0" + lv + "2025", "Simulated programme", rows, null);
    }
}
