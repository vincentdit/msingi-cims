package tz.co.msingi.cims.assets;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-AST-01..04: the asset register with moves and history, book value, verification, disposal; stores receipts, issues and stock. */
@IntegrationTest
class AssetsAndStoresIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    AdmissionScenario s;
    TestData.Institution inst;
    String stores, bursar, lecturer;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "A" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "stores", Role.STORES_OFFICER);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        stores = s.staffToken("stores");
        bursar = s.staffToken("bursar");
        lecturer = s.staffToken("lecturer");
    }

    @Test
    void assetsAreRegisteredMovedVerifiedAndDisposedOf() throws Exception {
        s.call(post("/api/assets"), bursar, "{\"name\":\"Desk\",\"category\":\"FURNITURE\"}").andExpect(status().isForbidden());
        s.call(get("/api/assets"), lecturer, null).andExpect(status().isForbidden());
        LocalDate twoYearsAgo = LocalDate.now().minusYears(2);
        JsonNode pc = s.body(s.call(post("/api/assets"), stores, "{\"name\":\"Desktop computer\",\"category\":\"ICT\",\"serialNo\":\"HP-8812\",\"location\":\"Computer lab 1\","
                + "\"custodianName\":\"Lab technician\",\"acquiredOn\":\"" + twoYearsAgo + "\",\"cost\":1200000,\"usefulLifeYears\":4,\"funding\":\"GOVERNMENT\"}")
                .andExpect(status().isOk()));
        long pcId = pc.at("/asset/id").asLong();
        String tag = pc.at("/asset/tagNo").asString();
        assertThat(tag).endsWith("/AST/00001");
        // two of four years used: half the cost
        assertThat(pc.at("/asset/bookValue").decimalValue()).isEqualByComparingTo("600000");
        long desk = s.body(s.call(post("/api/assets"), stores, "{\"tagNo\":\"FUR-001\",\"name\":\"Office desk\",\"category\":\"FURNITURE\",\"cost\":250000}")).at("/asset/id").asLong();
        s.call(post("/api/assets"), stores, "{\"tagNo\":\"fur-001\",\"name\":\"Chair\",\"category\":\"FURNITURE\"}").andExpect(jsonPath("$.code").value("TAG_TAKEN"));

        s.call(post("/api/assets/" + pcId + "/move"), stores, "{\"location\":\"Library\",\"custodianName\":\"Librarian\",\"note\":\"For the catalogue\"}")
                .andExpect(jsonPath("$.asset.location").value("Library")).andExpect(jsonPath("$.history[0].kind").value("MOVED"));
        s.call(post("/api/assets/" + pcId + "/condition"), stores, "{\"condition\":\"POOR\",\"status\":\"UNDER_REPAIR\",\"note\":\"Power supply\"}")
                .andExpect(jsonPath("$.history.length()").value(4));
        s.call(get("/api/assets/by-tag?tag=" + tag), bursar, null).andExpect(jsonPath("$.asset.status").value("UNDER_REPAIR"));
        s.call(get("/api/assets/overview"), bursar, null).andExpect(jsonPath("$.assets").value(2)).andExpect(jsonPath("$.underRepair").value(1))
                .andExpect(jsonPath("$.poorOrWorse").value(1));

        // verification: the computer is found (in fair condition now), the desk is missing and marked lost on closing
        long v = s.body(s.call(post("/api/asset-verifications"), stores, "{\"name\":\"Verification 2026\"}").andExpect(status().isOk())).at("/verification/id").asLong();
        s.call(post("/api/asset-verifications"), stores, "{}").andExpect(jsonPath("$.code").value("VERIFICATION_OPEN"));
        s.call(post("/api/asset-verifications/" + v + "/scan"), stores, "{\"tagNo\":\"" + tag + "\",\"condition\":\"FAIR\"}").andExpect(jsonPath("$.found").value(true));
        JsonNode lines = s.body(s.call(get("/api/asset-verifications/" + v), stores, null)).get("lines");
        long deskLine = lines.findParents("tagNo").stream().filter(l -> l.get("tagNo").asString().equals("FUR-001")).findFirst().orElseThrow().get("id").asLong();
        s.call(put("/api/asset-verification-lines/" + deskLine), stores, "{\"found\":false,\"note\":\"Not in room 3\"}").andExpect(jsonPath("$.found").value(false));
        s.call(post("/api/asset-verifications/" + v + "/close"), stores, "{\"markMissingLost\":true}")
                .andExpect(jsonPath("$.verification.found").value(1)).andExpect(jsonPath("$.verification.missing").value(1));
        s.call(get("/api/assets/" + pcId), stores, null).andExpect(jsonPath("$.asset.condition").value("FAIR"))
                .andExpect(jsonPath("$.history[0].kind").value("VERIFIED"));
        s.call(get("/api/assets/" + desk), stores, null).andExpect(jsonPath("$.asset.status").value("LOST"));
        s.call(get("/api/asset-verifications/" + v + "/report"), bursar, null).andExpect(status().isOk());

        // disposal needs the approval; disposed assets leave the register
        s.call(post("/api/assets/" + pcId + "/dispose"), stores, "{}").andExpect(jsonPath("$.code").value("FIELD_REQUIRED"));
        s.call(post("/api/assets/" + pcId + "/dispose"), stores, "{\"note\":\"Board of Survey, minute 4/2026; sold for scrap\"}").andExpect(jsonPath("$.asset.status").value("DISPOSED"));
        s.call(get("/api/assets"), stores, null).andExpect(jsonPath("$.length()").value(1));
        byte[] xlsx = s.call(get("/api/assets/register?status=ALL"), bursar, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(2);
        }
    }

    @Test
    void storesAreReceivedAtAverageCostAndIssuedToDepartments() throws Exception {
        long paper = s.body(s.call(post("/api/stores/items"), stores, "{\"code\":\"STA-001\",\"name\":\"A4 paper\",\"unit\":\"ream\",\"reorderLevel\":10}")
                .andExpect(status().isOk())).get("id").asLong();
        s.call(post("/api/stores/items"), stores, "{\"code\":\"sta-001\",\"name\":\"Again\",\"unit\":\"ream\"}").andExpect(jsonPath("$.code").value("CODE_TAKEN"));
        s.call(post("/api/stores/items/" + paper + "/movements"), stores, "{\"kind\":\"RECEIPT\",\"quantity\":20}").andExpect(jsonPath("$.code").value("COST_REQUIRED"));
        s.call(post("/api/stores/items/" + paper + "/movements"), stores, "{\"kind\":\"RECEIPT\",\"quantity\":20,\"unitCost\":10000,\"party\":\"Stationery Ltd\","
                + "\"reference\":\"DN-55\"}").andExpect(jsonPath("$.balanceAfter").value(20.0));
        s.call(post("/api/stores/items/" + paper + "/movements"), stores, "{\"kind\":\"RECEIPT\",\"quantity\":10,\"unitCost\":13000}").andExpect(status().isOk());
        // (20 x 10,000 + 10 x 13,000) / 30 = 11,000
        s.call(get("/api/stores/items"), bursar, null).andExpect(jsonPath("$[0].averageCost").value(11000.0)).andExpect(jsonPath("$[0].value").value(330000.0));
        s.call(post("/api/stores/items/" + paper + "/movements"), stores, "{\"kind\":\"ISSUE\",\"quantity\":5}").andExpect(jsonPath("$.code").value("ISSUED_TO_REQUIRED"));
        s.call(post("/api/stores/items/" + paper + "/movements"), stores, "{\"kind\":\"ISSUE\",\"quantity\":50,\"party\":\"Registry\"}")
                .andExpect(jsonPath("$.code").value("NOT_ENOUGH_STOCK"));
        s.call(post("/api/stores/items/" + paper + "/movements"), stores, "{\"kind\":\"ISSUE\",\"quantity\":22,\"party\":\"Registry office\",\"reference\":\"REQ-9\"}")
                .andExpect(jsonPath("$.quantity").value(-22.0)).andExpect(jsonPath("$.balanceAfter").value(8.0));
        s.call(get("/api/stores/items?low=true"), stores, null).andExpect(jsonPath("$.length()").value(1));
        s.call(post("/api/stores/items/" + paper + "/movements"), stores, "{\"kind\":\"ADJUSTMENT\",\"quantity\":-1}").andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        s.call(post("/api/stores/items/" + paper + "/movements"), stores, "{\"kind\":\"ADJUSTMENT\",\"quantity\":-1,\"note\":\"Stock take: one ream damaged\"}")
                .andExpect(jsonPath("$.balanceAfter").value(7.0));
        s.call(get("/api/stores/movements?itemId=" + paper), bursar, null).andExpect(jsonPath("$.length()").value(4));
        s.call(get("/api/stores/report"), bursar, null).andExpect(status().isOk());
    }
}
