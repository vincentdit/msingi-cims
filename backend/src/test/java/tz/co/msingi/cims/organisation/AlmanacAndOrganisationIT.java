package tz.co.msingi.cims.organisation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Stakeholders' comments: the institution's almanac and its organisation structure, for staff, students and the public site. */
@IntegrationTest
class AlmanacAndOrganisationIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    AdmissionScenario s;
    String admin, dpa, lecturer;
    long yearId;
    LocalDate yearStart;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "O" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "dpa", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        dpa = s.staffToken("dpa");
        lecturer = s.staffToken("lecturer");
        String officer = s.staffToken("officer");
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
        for (JsonNode y : s.body(s.call(get("/api/academic/years"), admin, null))) {
            if (y.get("id").asLong() == yearId) yearStart = LocalDate.parse(y.get("startDate").asText());
        }
    }

    String event(String kind, String title, LocalDate from, String audience) {
        return "{\"academicYearId\":" + yearId + ",\"kind\":\"" + kind + "\",\"title\":\"" + title + "\",\"startDate\":\"" + from
                + "\",\"endDate\":\"" + from + "\",\"audience\":\"" + audience + "\",\"venue\":\"Main hall\",\"responsible\":\"Office of the Principal\"}";
    }

    @Test
    void theAlmanacIsPublishedToTheRightReaders() throws Exception {
        LocalDate d = yearStart.plusDays(20);
        s.call(post("/api/academic/calendar-events"), dpa, event("MEETING", "Governing Board meeting", d, "STAFF")).andExpect(status().isCreated());
        s.call(post("/api/academic/calendar-events"), dpa, event("ACTIVITY", "Open day", d.plusDays(5), "ALL")).andExpect(status().isCreated());
        s.call(post("/api/academic/calendar-events"), lecturer, event("ACTIVITY", "Not allowed", d, "ALL")).andExpect(status().isForbidden());

        // every member of staff reads it, unpublished
        JsonNode staff = s.body(s.call(get("/api/academic/almanac?academicYearId=" + yearId), lecturer, null).andExpect(status().isOk()));
        assertThat(staff.get("published").asBoolean()).isFalse();
        assertThat(staff.findValuesAsText("title")).contains("Governing Board meeting", "Open day");
        assertThat(staff.get("entries").get(0).get("venue").asText()).isEqualTo("Main hall");

        // the public site sees nothing until it is published, then only what is for everyone
        s.call(get("/api/public/almanac?academicYearId=" + yearId), null, null).andExpect(status().isNotFound());
        s.call(post("/api/academic/almanac/" + yearId + "/publish"), lecturer, null).andExpect(status().isForbidden());
        s.call(post("/api/academic/almanac/" + yearId + "/publish"), dpa, null).andExpect(jsonPath("$.published").value(true));
        JsonNode pub = s.body(s.call(get("/api/public/almanac?academicYearId=" + yearId), null, null).andExpect(status().isOk()));
        assertThat(pub.findValuesAsText("title")).contains("Open day").doesNotContain("Governing Board meeting");
        assertThat(s.body(s.call(get("/api/public/almanac/years"), null, null)).findValuesAsText("code")).isNotEmpty();

        s.call(post("/api/academic/almanac/" + yearId + "/publish?publish=false"), dpa, null).andExpect(jsonPath("$.published").value(false));
        s.call(get("/api/public/almanac?academicYearId=" + yearId), null, null).andExpect(status().isNotFound());
    }

    @Test
    void theInstitutionBuildsItsOrganisationStructure() throws Exception {
        s.call(post("/api/organisation/template"), lecturer, null).andExpect(status().isForbidden());
        JsonNode t = s.body(s.call(post("/api/organisation/template"), admin, null).andExpect(status().isOk()));
        assertThat(t.get("roots").size()).isEqualTo(1);
        JsonNode board = t.get("roots").get(0);
        assertThat(board.get("name").asText()).isEqualTo("Governing Board");
        JsonNode principal = board.get("children").get(0);
        assertThat(principal.get("positions").get(0).get("title").asText()).isEqualTo("Principal");
        assertThat(t.findValuesAsText("kind")).contains("DEPARTMENT", "COMMITTEE", "DIRECTORATE");
        s.call(post("/api/organisation/template"), admin, null).andExpect(jsonPath("$.code").value("STRUCTURE_EXISTS"));

        // fill the Principal's position, add a unit and a vacant position, move nothing into a loop
        long principalPosition = principal.get("positions").get(0).get("id").asLong();
        s.call(put("/api/organisation/positions/" + principalPosition), admin,
                "{\"title\":\"Principal\",\"head\":true,\"holderName\":\"Dr. Amina Said\",\"acting\":false}").andExpect(status().isOk());
        JsonNode added = s.body(s.call(post("/api/organisation/units"), admin,
                "{\"parentId\":" + principal.get("id").asLong() + ",\"kind\":\"UNIT\",\"name\":\"Legal Unit\",\"advisory\":false}").andExpect(status().isOk()));
        long legal = findId(added, "Legal Unit");
        assertThat(legal).isPositive();
        s.call(post("/api/organisation/units/" + legal + "/positions"), admin, "{\"title\":\"Legal Officer\",\"head\":true}").andExpect(status().isOk());
        s.call(put("/api/organisation/units/" + board.get("id").asLong()), admin,
                "{\"parentId\":" + legal + ",\"kind\":\"BOARD\",\"name\":\"Governing Board\",\"advisory\":false}").andExpect(jsonPath("$.code").value("UNIT_LOOP"));
        s.call(delete("/api/organisation/units/" + principal.get("id").asLong()), admin, null).andExpect(jsonPath("$.code").value("UNIT_HAS_CHILDREN"));

        JsonNode now = s.body(s.call(get("/api/organisation"), lecturer, null).andExpect(status().isOk()));
        assertThat(now.toString()).contains("Dr. Amina Said");
        assertThat(now.get("vacantCount").asInt()).isPositive();

        // public once published, without the staff accounts behind the holders
        s.call(get("/api/public/organisation"), null, null).andExpect(status().isNotFound());
        s.call(post("/api/organisation/publish"), admin, null).andExpect(jsonPath("$.published").value(true));
        JsonNode pub = s.body(s.call(get("/api/public/organisation"), null, null).andExpect(status().isOk()));
        assertThat(pub.toString()).contains("Dr. Amina Said");
        assertThat(pub.findValues("holderUserId").stream().allMatch(JsonNode::isNull)).isTrue();

        s.call(delete("/api/organisation/units/" + legal), admin, null).andExpect(status().isOk());
    }

    static long findId(JsonNode tree, String name) {
        if (tree.has("name") && name.equals(tree.get("name").asText())) return tree.get("id").asLong();
        for (JsonNode child : tree) {
            long id = findId(child, name);
            if (id > 0) return id;
        }
        return -1;
    }
}
