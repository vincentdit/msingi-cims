package tz.co.msingi.cims.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-RPT-01/02: the report catalogue by role, report figures, Excel and PDF exports, audit, dashboards and isolation. */
@IntegrationTest
class ReportsIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingVenue":"Main Hall","documentsToBring":["Original CSEE certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    AdmissionScenario s;
    String admin, officer, chair, registrar, bursar, accountant, viewer, auditor;
    long yearId;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        data.staff(inst.tenant(), "auditor", Role.AUDITOR);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        officer = s.staffToken("officer");
        chair = s.staffToken("chair");
        registrar = s.staffToken("registrar");
        bursar = s.staffToken("bursar");
        accountant = s.staffToken("accountant");
        viewer = s.staffToken("viewer");
        auditor = s.staffToken("auditor");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
    }

    @Test
    void theCatalogueFollowsRolesAndKeepsNamedListsFromManagementViewers() throws Exception {
        List<String> all = codes(admin);
        assertThat(all).contains("ADM-01", "ADM-08", "REG-02", "FIN-04", "GOV-01", "GOV-02", "TCH-01", "EXM-04", "GRD-01", "GRD-02", "HST-01", "HST-02").hasSize(33);
        assertThat(codes(viewer)).contains("ADM-01", "REG-01", "FIN-01", "FIN-06").doesNotContain("ADM-07", "ADM-08", "REG-02", "FIN-04", "FIN-05", "GOV-01");
        assertThat(codes(accountant)).allMatch(c -> c.startsWith("FIN-") || c.startsWith("HST-"));
        assertThat(codes(registrar)).allMatch(c -> c.startsWith("ADM-") || c.startsWith("REG-") || c.startsWith("GRD-") || c.startsWith("HST-"));
        assertThat(codes(auditor)).contains("GOV-02", "FIN-04", "ADM-08");
        s.call(get("/api/reports/ADM-08"), viewer, null).andExpect(status().isForbidden());
        s.call(get("/api/reports/FIN-01"), registrar, null).andExpect(status().isForbidden());
        s.call(get("/api/reports/NOPE-1"), admin, null).andExpect(status().isNotFound());
    }

    @Test
    void admissionAndRegistryFiguresAddUp() throws Exception {
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 400);
        s.admit(officer, chair, a, b);

        JsonNode p = s.body(s.call(get("/api/reports/ADM-01?windowId=" + s.inst.windowId()), viewer, null).andExpect(status().isOk()));
        List<Object> row = row(p, 0);
        assertThat(row.subList(3, 12)).containsExactly(50, 2, 2, 0, 2, 2, 2, 2, 0.04);
        assertThat(p.get("filterLines").get(0).asText()).startsWith("Admission window: Test window ");
        assertThat(p.at("/totals/0").asText()).isEqualTo("Total");

        JsonNode st = s.body(s.call(get("/api/reports/ADM-04"), admin, null));
        assertThat(texts(st, 0)).containsExactly("Accepted");
        assertThat(st.at("/totals/3").asInt()).isEqualTo(2);
        assertThat(st.at("/totals/4").asDouble()).isEqualTo(1.0);
        assertThat(s.body(s.call(get("/api/reports/ADM-04?sex=M"), admin, null)).get("rows")).isEmpty();
        s.call(get("/api/reports/ADM-04?sex=X"), admin, null).andExpect(jsonPath("$.code").value("INVALID_FILTER"));

        JsonNode acc = s.body(s.call(get("/api/reports/ADM-06"), admin, null));
        assertThat(row(acc, 0).subList(2, 8)).containsExactly(2, 2, 0, 0, 0, 1.0);
        assertThat(row(s.body(s.call(get("/api/reports/ADM-05"), admin, null)), 0).get(7)).isEqualTo(2);
        assertThat(s.body(s.call(get("/api/reports/ADM-03"), admin, null)).at("/rows/0/0").asText()).isEqualTo("CSEE (Form IV)");
        assertThat(s.body(s.call(get("/api/reports/ADM-02"), admin, null)).at("/rows/0/0").asText()).isEqualTo("Dodoma");
        assertThat(s.body(s.call(get("/api/reports/ADM-08?status=ACCEPTED"), admin, null)).get("rowCount").asInt()).isEqualTo(2);
        assertThat(s.body(s.call(get("/api/reports/ADM-08?status=SUBMITTED"), admin, null)).get("rowCount").asInt()).isZero();

        s.call(post("/api/registry/windows/" + s.inst.windowId() + "/handover"), registrar, "{}").andExpect(status().isOk());
        JsonNode enrol = s.body(s.call(get("/api/reports/REG-01?academicYearId=" + yearId), registrar, null));
        assertThat(row(enrol, 0).subList(2, 9)).containsExactly(1, 2, 0, 0, 2, 0, 2);
        JsonNode roll = s.body(s.call(get("/api/reports/REG-02"), registrar, null));
        assertThat(roll.get("rowCount").asInt()).isEqualTo(2);
        assertThat(roll.at("/rows/0/0").asText()).startsWith(s.tenant + "/");
        JsonNode ages = s.body(s.call(get("/api/reports/REG-03"), registrar, null));
        assertThat(texts(ages, 0)).containsExactly("Under 18", "18–20", "21–24", "25–29", "30–39", "40 and over");
        assertThat(ages.at("/totals/3").asInt()).isEqualTo(2);
        assertThat(row(s.body(s.call(get("/api/reports/REG-05"), registrar, null)), 0).subList(2, 7)).containsExactly(2, 0, 0, 2, 0.0);

        // dashboards: sections by role
        JsonNode d = s.body(s.call(get("/api/dashboard"), admin, null).andExpect(status().isOk()));
        assertThat(d.at("/admissions/funnel").findValuesAsText("value")).containsExactly("2", "2", "2", "2", "2", "2");
        assertThat(d.at("/admissions/female").asInt()).isEqualTo(2);
        assertThat(d.at("/registry/current").asInt()).isEqualTo(2);
        assertThat(d.at("/registry/awaitingRegistration").asInt()).isEqualTo(2);
        assertThat(d.hasNonNull("finance")).isTrue();
        JsonNode acct = s.body(s.call(get("/api/dashboard"), accountant, null));
        assertThat(acct.hasNonNull("admissions")).isFalse();
        assertThat(acct.hasNonNull("finance")).isTrue();
        assertThat(s.body(s.call(get("/api/dashboard"), registrar, null)).hasNonNull("finance")).isFalse();

        // another institution sees none of it
        String other = "U" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution o = data.institution(other, BigDecimal.ZERO);
        data.staff(o.tenant(), "admin", Role.INSTITUTION_ADMIN);
        AdmissionScenario os = new AdmissionScenario(mvc, json, other, o);
        String oAdmin = os.staffToken("admin");
        assertThat(os.body(os.call(get("/api/reports/ADM-04"), oAdmin, null)).get("rows")).isEmpty();
        assertThat(os.body(os.call(get("/api/reports/REG-02"), oAdmin, null)).get("rowCount").asInt()).isZero();
        assertThat(os.body(os.call(get("/api/dashboard"), oAdmin, null)).at("/registry/current").asInt()).isZero();
    }

    @Test
    void exportsAreRealExcelAndPdfFilesAndAreAudited() throws Exception {
        Applicant a = s.apply("S0101/0001");
        long invoiceId = s.body(s.call(get("/api/applicant/account"), a.token(), null)).at("/invoices/0/id").asLong(0);
        approvedStructure();
        s.admit(officer, chair, a);
        for (JsonNode inv : s.body(s.call(get("/api/applicant/account"), a.token(), null)).get("invoices")) {
            if (inv.get("purpose").asText().equals("ANNUAL_FEES")) invoiceId = inv.get("id").asLong();
        }
        s.call(post("/api/applicant/invoices/" + invoiceId + "/simulate-payment"), a.token(), "{\"amount\":150000}").andExpect(status().isOk());

        JsonNode items = s.body(s.call(get("/api/reports/FIN-01"), accountant, null).andExpect(status().isOk()));
        assertThat(items.at("/totals/2").decimalValue()).isEqualByComparingTo("150000");
        assertThat(items.get("filterLines").get(0).asText()).startsWith("Period: ");
        JsonNode debtors = s.body(s.call(get("/api/reports/FIN-05?academicYearId=" + yearId), bursar, null));
        assertThat(debtors.at("/rows/0/6").decimalValue()).isEqualByComparingTo("470000");
        s.call(get("/api/reports/FIN-01?from=2026-10-10&to=2026-10-01"), accountant, null).andExpect(jsonPath("$.code").value("INVALID_PERIOD"));

        byte[] xlsx = s.call(get("/api/reports/FIN-04/export?format=xlsx"), bursar, null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(sh.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Receipts register");
            Row header = null;
            for (Row r : sh) if (r.getCell(0) != null && "Receipt".equals(r.getCell(0).toString())) { header = r; break; }
            assertThat(header).isNotNull();
            Row first = sh.getRow(header.getRowNum() + 1);
            assertThat(first.getCell(0).getStringCellValue()).startsWith(s.tenant + "/RCT/");
            assertThat(first.getCell(7).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(first.getCell(7).getNumericCellValue()).isEqualTo(150000.0);
        }
        byte[] pdf = s.call(get("/api/reports/ADM-06/export?format=pdf"), admin, null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Offers and acceptance rate").contains(s.tenant + " Technical College").contains("100.0%");
        }
        s.call(get("/api/reports/ADM-06/export?format=doc"), admin, null).andExpect(jsonPath("$.code").value("INVALID_FORMAT"));

        // governance: access review and the audit trail of the exports
        JsonNode access = s.body(s.call(get("/api/reports/GOV-01"), auditor, null).andExpect(status().isOk()));
        assertThat(texts(access, 1)).contains("Staff bursar", "Staff viewer");
        JsonNode trail = s.body(s.call(get("/api/reports/GOV-02?text=report_exported"), auditor, null));
        assertThat(texts(trail, 2)).containsOnly("REPORT_EXPORTED").hasSize(2);
        assertThat(texts(trail, 1)).containsExactly("Staff bursar", "Staff admin");
        s.call(get("/api/reports/GOV-02"), bursar, null).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private void approvedStructure() throws Exception {
        long reg = s.body(s.call(post("/api/finance/fee-items"), accountant,
                "{\"code\":\"REG\",\"name\":\"Registration fee\",\"category\":\"REGISTRATION\",\"allocationPriority\":10,\"active\":true}")).get("id").asLong();
        long tuition = s.body(s.call(post("/api/finance/fee-items"), accountant,
                "{\"code\":\"TUITION\",\"name\":\"Tuition fee\",\"category\":\"TUITION\",\"allocationPriority\":50,\"active\":true}")).get("id").asLong();
        long id = s.body(s.call(post("/api/finance/fee-structures"), accountant, "{\"academicYearId\":" + yearId + ",\"programmeId\":" + s.inst.programmeId()
                + ",\"yearOfStudy\":1,\"lines\":[{\"feeItemId\":" + reg + ",\"amount\":20000,\"instalment\":1},{\"feeItemId\":" + tuition
                + ",\"amount\":600000,\"instalment\":1}]}")).get("id").asLong();
        s.call(post("/api/finance/fee-structures/" + id + "/approve"), bursar, null).andExpect(status().isOk());
    }

    private List<String> codes(String token) throws Exception {
        return s.body(s.call(get("/api/reports"), token, null).andExpect(status().isOk())).findValuesAsText("code");
    }

    private List<Object> row(JsonNode report, int i) {
        List<Object> out = new ArrayList<>();
        for (JsonNode v : report.get("rows").get(i)) out.add(v.isIntegralNumber() ? (Object) v.asInt() : v.isNumber() ? (Object) v.asDouble() : v.asText());
        return out;
    }

    private static List<String> texts(JsonNode report, int column) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : report.get("rows")) out.add(r.get(column).asText());
        return out;
    }
}
