package tz.co.msingi.cims.integration.nactvet;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reading NACTVET's curriculum-details answer. */
class NactvetCurriculumParseTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void readsTheModules() {
        var s = LiveNactvetCurriculumClient.parse(JSON.readTree("""
                {"code":200,"message":"Success","programme_id":"1b6c88deba258d0f16439fc782e76a5d62a9d6c0",
                 "programme_name":"Technician Certificate in Clinical Medicine","curriculum_code":"CMT0502022","academic_year":"2023/2024",
                 "level":"NTA5","semester":"ONE","intake":"SEPT","total_modules":2,
                 "params":[{"curriculum_code":"CMT0502022","module_code":"CMT05101","module_name":"Clinical Pharmacology","credits":"12","module_type":"Core"},
                           {"curriculum_code":"CMT0502022","module_code":"CMT05102","module_name":"Community Health","credits":9.5,"module_type":"Elective"},
                           {"curriculum_code":"CMT0502022","module_name":"no code"}]}"""));
        assertThat(s.curriculumCode()).isEqualTo("CMT0502022");
        assertThat(s.programmeName()).isEqualTo("Technician Certificate in Clinical Medicine");
        assertThat(s.modules()).hasSize(2);
        assertThat(s.modules().get(0).code()).isEqualTo("CMT05101");
        assertThat(s.modules().get(0).credits()).isEqualByComparingTo("12");
        assertThat(s.modules().get(0).elective()).isFalse();
        assertThat(s.modules().get(1).credits()).isEqualByComparingTo("9.5");
        assertThat(s.modules().get(1).elective()).isTrue();
        assertThat(s.modules().get(0).fields()).contains("module_code").contains("credits");
    }

    @Test
    void emptyAndRefused() {
        var s = LiveNactvetCurriculumClient.parse(JSON.readTree("{\"code\":404,\"message\":\"No curriculum found\",\"params\":[]}"));
        assertThat(s.modules()).isEmpty();
        assertThat(s.message()).isEqualTo("No curriculum found");
        assertThatThrownBy(() -> LiveNactvetCurriculumClient.parse(JSON.readTree("{\"code\":403,\"message\":\"Forbidden\"}")))
                .isInstanceOf(NactvetUnavailableException.class).hasMessageContaining("NACTE_AUTHORIZATION");
    }
}
