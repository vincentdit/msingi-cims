package tz.co.msingi.cims.integration.nactvet;

import java.math.BigDecimal;
import java.util.List;

/**
 * NACTVET's curriculum of a programme (api-out-service/curriculum-details): for one programme, academic year, NTA level, semester
 * and intake, the curriculum code and its modules.
 */
public interface NactvetCurriculumClient {

    /** One module as NACTVET lists it; {@code credits} and {@code elective} are empty when NACTVET does not say. */
    record ModuleRow(String code, String title, BigDecimal credits, Boolean elective, String fields) {
    }

    /** The answer for one level and semester; no modules when NACTVET has none for it. */
    record Semester(String curriculumCode, String programmeName, List<ModuleRow> modules, String message) {
    }

    /**
     * @param programmeId NACTVET's programme id (40 characters)
     * @param academicYear for example 2025/2026
     * @param level NTA4 … NTA8
     * @param semester ONE or TWO
     * @param intake SEPT or MARCH
     */
    Semester fetch(String programmeId, String academicYear, String level, String semester, String intake);
}
