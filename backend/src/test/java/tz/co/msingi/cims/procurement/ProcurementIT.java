package tz.co.msingi.cims.procurement;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-PRC-01..05: requisition, endorsement, approval, quotations, the purchase order and goods received into stores and the asset register. */
@IntegrationTest
class ProcurementIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String tenant, lecturer, hod, otherHod, bursar, procurement, stores, registrar;
    long departmentId;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "R" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        departmentId = db(() -> jdbc.queryForObject("select department_id from programmes where id = ?", Long.class, inst.programmeId()));
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        data.staffScoped(inst.tenant(), "hod", Role.HEAD_OF_DEPARTMENT, "DEPARTMENT", departmentId);
        data.staffScoped(inst.tenant(), "otherhod", Role.HEAD_OF_DEPARTMENT, "DEPARTMENT", 999_999L);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "procurement", Role.PROCUREMENT_OFFICER);
        data.staff(inst.tenant(), "stores", Role.STORES_OFFICER);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        lecturer = s.staffToken("lecturer");
        hod = s.staffToken("hod");
        otherHod = s.staffToken("otherhod");
        bursar = s.staffToken("bursar");
        procurement = s.staffToken("procurement");
        stores = s.staffToken("stores");
        registrar = s.staffToken("registrar");
    }

    private <T> T db(Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    @Test
    void fromRequisitionToGoodsInStoresAndTheAssetRegister() throws Exception {
        long paper = s.body(s.call(post("/api/stores/items"), stores, "{\"code\":\"STA-001\",\"name\":\"A4 paper\",\"unit\":\"ream\",\"reorderLevel\":10}"))
                .get("id").asLong();
        long supA = s.body(s.call(post("/api/procurement/suppliers"), procurement, "{\"name\":\"Kariakoo Office Supplies\",\"tin\":\"111-222-333\",\"phone\":\"0713000111\"}"))
                .get("id").asLong();
        long supB = s.body(s.call(post("/api/procurement/suppliers"), procurement, "{\"name\":\"Mlimani Computers\",\"tin\":\"444-555-666\"}")).get("id").asLong();
        s.call(post("/api/procurement/suppliers"), procurement, "{\"name\":\"mlimani computers\"}").andExpect(jsonPath("$.code").value("SUPPLIER_EXISTS"));
        s.call(post("/api/procurement/suppliers"), lecturer, "{\"name\":\"X\"}").andExpect(status().isForbidden());

        // a lecturer asks for paper (into stores) and two laptops (into the asset register)
        String body = "{\"departmentId\":" + departmentId + ",\"title\":\"Teaching materials\",\"justification\":\"Semester 2 practicals\",\"neededBy\":\""
                + LocalDate.now().plusDays(30) + "\",\"lines\":[{\"description\":\"A4 paper\",\"quantity\":20,\"estimatedCost\":12000,\"storeItemId\":" + paper + "},"
                + "{\"description\":\"Laptop, core i5\",\"quantity\":2,\"unit\":\"piece\",\"estimatedCost\":1500000,\"assetCategory\":\"ICT\"}]}";
        JsonNode req = s.body(s.call(post("/api/procurement/requisitions"), lecturer, body).andExpect(status().isOk()));
        long reqId = req.at("/requisition/id").asLong();
        assertThat(req.at("/requisition/status").asString()).isEqualTo("SUBMITTED");
        assertThat(req.at("/requisition/estimatedTotal").asInt()).isEqualTo(3_240_000);
        assertThat(req.at("/lines/0/unit").asString()).isEqualTo("ream");

        // the Head of the department endorses (not another department's Head, not the person who asked); then the Bursar approves
        s.call(get("/api/approvals"), hod, null).andExpect(jsonPath("$.items[?(@.kind == 'REQUISITION_ENDORSE')].canAct").value(true));
        s.call(post("/api/procurement/requisitions/" + reqId + "/endorse"), otherHod, "{\"approve\":true}").andExpect(status().isForbidden());
        s.call(post("/api/procurement/requisitions/" + reqId + "/approve"), bursar, "{\"approve\":true}").andExpect(jsonPath("$.code").value("REQUISITION_NOT_WAITING"));
        s.call(post("/api/procurement/requisitions/" + reqId + "/endorse"), hod, "{\"approve\":true}").andExpect(jsonPath("$.requisition.status").value("ENDORSED"));
        s.call(get("/api/procurement/requisitions/" + reqId), registrar, null).andExpect(status().isForbidden());
        s.call(get("/api/procurement/requisitions?mine=true"), lecturer, null).andExpect(jsonPath("$.length()").value(1));
        s.call(post("/api/procurement/requisitions/" + reqId + "/approve"), bursar, "{\"approve\":true}").andExpect(jsonPath("$.requisition.status").value("APPROVED"));

        // two quotations; the dearer one needs a reason
        mvc.perform(multipart("/api/procurement/requisitions/" + reqId + "/quotations").file(new MockMultipartFile("file", "quote.pdf", "application/pdf",
                        "%PDF-1.4 quote".getBytes(StandardCharsets.US_ASCII))).param("supplierId", String.valueOf(supA)).param("amount", "3200000")
                .param("deliveryDays", "14").header("X-Tenant", tenant).header("Authorization", "Bearer " + procurement)).andExpect(status().isOk());
        JsonNode withQuotes = s.body(mvc.perform(multipart("/api/procurement/requisitions/" + reqId + "/quotations").param("supplierId", String.valueOf(supB))
                .param("amount", "3100000").header("X-Tenant", tenant).header("Authorization", "Bearer " + procurement)));
        long dearer = withQuotes.get("quotations").findParents("supplierId").stream().filter(q -> q.get("supplierId").asLong() == supA).findFirst()
                .orElseThrow().get("id").asLong();
        s.call(post("/api/procurement/quotations/" + dearer + "/choose"), procurement, "{}").andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        s.call(post("/api/procurement/quotations/" + dearer + "/choose"), procurement, "{\"note\":\"Delivers in two weeks with a one-year warranty\"}")
                .andExpect(jsonPath("$.quotations[?(@.chosen == true)].supplier").value("Kariakoo Office Supplies"));

        // the order: no more than the quotation
        long paperLine = withQuotes.at("/lines/0/id").asLong(), laptopLine = withQuotes.at("/lines/1/id").asLong();
        s.call(post("/api/procurement/orders"), procurement, "{\"requisitionId\":" + reqId + ",\"lines\":[{\"requisitionLineId\":" + paperLine
                + ",\"unitPrice\":12000},{\"requisitionLineId\":" + laptopLine + ",\"unitPrice\":1600000}]}").andExpect(jsonPath("$.code").value("ABOVE_QUOTATION"));
        JsonNode order = s.body(s.call(post("/api/procurement/orders"), procurement, "{\"requisitionId\":" + reqId + ",\"deliverBy\":\"" + LocalDate.now().plusDays(14)
                + "\",\"lines\":[{\"requisitionLineId\":" + paperLine + ",\"unitPrice\":10000},{\"requisitionLineId\":" + laptopLine + ",\"unitPrice\":1500000}]}")
                .andExpect(status().isOk()));
        long orderId = order.at("/order/id").asLong();
        assertThat(order.at("/order/total").asInt()).isEqualTo(3_200_000);
        s.call(get("/api/procurement/requisitions/" + reqId), lecturer, null).andExpect(jsonPath("$.requisition.status").value("ORDERED"));
        byte[] pdf = s.call(get("/api/procurement/orders/" + orderId + "/pdf"), procurement, null).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        // Stores receive, the Procurement Officer does not; part first, then the rest
        long olPaper = order.at("/lines/0/id").asLong(), olLaptop = order.at("/lines/1/id").asLong();
        s.call(post("/api/procurement/orders/" + orderId + "/receipts"), procurement, "{\"lines\":[{\"orderLineId\":" + olPaper + ",\"quantity\":5}]}")
                .andExpect(status().isForbidden());
        s.call(post("/api/procurement/orders/" + orderId + "/receipts"), stores, "{\"lines\":[{\"orderLineId\":" + olPaper + ",\"quantity\":25}]}")
                .andExpect(jsonPath("$.code").value("MORE_THAN_ORDERED"));
        s.call(post("/api/procurement/orders/" + orderId + "/receipts"), stores, "{\"deliveryNote\":\"DN-77\",\"lines\":[{\"orderLineId\":" + olPaper
                + ",\"quantity\":20},{\"orderLineId\":" + olLaptop + ",\"quantity\":1}]}").andExpect(jsonPath("$.order.status").value("PART_RECEIVED"))
                .andExpect(jsonPath("$.receipts[0].lines[1].assetTags.length()").value(1));
        s.call(post("/api/procurement/orders/" + orderId + "/cancel"), procurement, "{\"note\":\"x\"}").andExpect(jsonPath("$.code").value("ORDER_RECEIVED"));
        s.call(post("/api/procurement/orders/" + orderId + "/receipts"), stores, "{\"lines\":[{\"orderLineId\":" + olLaptop + ",\"quantity\":1}]}")
                .andExpect(jsonPath("$.order.status").value("RECEIVED")).andExpect(jsonPath("$.order.receivedValue").value(3_200_000.0));

        // into stores at the order price, and two laptops in the register at their cost
        s.call(get("/api/stores/items"), stores, null).andExpect(jsonPath("$[0].onHand").value(20.0)).andExpect(jsonPath("$[0].averageCost").value(10000.0));
        assertThat(db(() -> jdbc.queryForObject("select count(*) from assets where category = 'ICT' and cost = 1500000 and supplier = 'Kariakoo Office Supplies'",
                Integer.class))).isEqualTo(2);

        s.call(get("/api/procurement/overview"), bursar, null).andExpect(jsonPath("$.openOrders").value(0));
        byte[] xlsx = s.call(get("/api/procurement/register"), bursar, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheet("Orders").getRow(1).getCell(8).getNumericCellValue()).isEqualTo(2.0);
            assertThat(wb.getSheet("Goods received").getLastRowNum()).isEqualTo(2);
        }
    }

    @Test
    void nobodyApprovesTheirOwnRequest() throws Exception {
        // the Head asks: no endorsement step, but the Head cannot approve; the Bursar's own request needs someone else
        JsonNode r = s.body(s.call(post("/api/procurement/requisitions"), hod, "{\"departmentId\":" + departmentId + ",\"title\":\"Chairs\",\"justification\":\"Lab\","
                + "\"lines\":[{\"description\":\"Chair\",\"quantity\":10,\"unit\":\"piece\",\"estimatedCost\":50000,\"assetCategory\":\"FURNITURE\"}]}"));
        assertThat(r.at("/requisition/status").asString()).isEqualTo("ENDORSED");
        long own = s.body(s.call(post("/api/procurement/requisitions"), bursar, "{\"title\":\"Calculator\",\"justification\":\"Office\","
                + "\"lines\":[{\"description\":\"Calculator\",\"quantity\":1,\"unit\":\"piece\",\"estimatedCost\":40000}]}")).at("/requisition/id").asLong();
        s.call(post("/api/procurement/requisitions/" + own + "/approve"), bursar, "{\"approve\":true}").andExpect(jsonPath("$.code").value("OWN_REQUEST"));
        s.call(post("/api/procurement/requisitions/" + r.at("/requisition/id").asLong() + "/approve"), bursar, "{\"approve\":false}")
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        s.call(post("/api/procurement/requisitions/" + r.at("/requisition/id").asLong() + "/approve"), bursar, "{\"approve\":false,\"note\":\"No budget this term\"}")
                .andExpect(jsonPath("$.requisition.status").value("REJECTED"));
        s.call(post("/api/procurement/requisitions"), lecturer, "{\"title\":\"Half a laptop\",\"justification\":\"x\",\"lines\":[{\"description\":\"Laptop\","
                + "\"quantity\":1.5,\"unit\":\"piece\",\"estimatedCost\":1,\"assetCategory\":\"ICT\"}]}").andExpect(jsonPath("$.code").value("QUANTITY_WHOLE"));
    }
}
