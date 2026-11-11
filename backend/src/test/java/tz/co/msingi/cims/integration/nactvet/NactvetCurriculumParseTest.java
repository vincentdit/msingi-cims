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

    /** As NACTVET answered on 11 Nov 2026 (two of the six modules). */
    @Test
    void readsNactvetsRealAnswer() {
        var s = LiveNactvetCurriculumClient.parse(JSON.readTree("""
                {"code":200,"message":"Success","programme_id":"1b6c88deba258d0f16439fc782e76a5d62a9d6c0",
                 "programme_name":"Technician Certificate in Clinical Medicine","curriculum_code":"CMT0502022","academic_year":"2023/2024",
                 "level":"NTA5","semester":"ONE","intake":"SEPT","total_modules":6,
                 "params":[{"curriculum_code":"CMT0502022","module_code":"CMT05101","module_name":"Clinical Pharmacology","module_credit":10,
                            "module_category":"CORE","weight_CA":0.4,"weight_SE":0.6,"min_pass_CA":20,"min_pass_SE":30,"WR":0,"PR":0,
                            "academic_year":"2023/2024","level":"NTA5","semester":"ONE","intake":"SEPT"},
                           {"curriculum_code":"CMT0502022","module_code":"CMT05106","module_name":"Counselling","module_credit":8,
                            "module_category":"CORE","weight_CA":0.4,"weight_SE":0.6,"min_pass_CA":20,"min_pass_SE":30,"WR":0.2,"PR":0.4,
                            "academic_year":"2023/2024","level":"NTA5","semester":"ONE","intake":"SEPT"}]}"""));
        assertThat(s.curriculumCode()).isEqualTo("CMT0502022");
        assertThat(s.modules()).extracting(NactvetCurriculumClient.ModuleRow::code).containsExactly("CMT05101", "CMT05106");
        assertThat(s.modules().get(0).title()).isEqualTo("Clinical Pharmacology");
        assertThat(s.modules().get(0).credits()).isEqualByComparingTo("10");
        assertThat(s.modules().get(1).credits()).isEqualByComparingTo("8");
        assertThat(s.modules()).allSatisfy(m -> assertThat(m.elective()).isFalse());
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
