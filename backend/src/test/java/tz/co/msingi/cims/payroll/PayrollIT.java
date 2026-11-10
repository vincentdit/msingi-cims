package tz.co.msingi.cims.payroll;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-PAY-01..05: pay details, the monthly payroll prepared by one person and approved by another, payment, files and payslips. */
@IntegrationTest
class PayrollIT {

    static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String tenant, hr, bursar, accountant, lecturer, registrar;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "P" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "hr", Role.HR_OFFICER);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        hr = s.staffToken("hr");
        bursar = s.staffToken("bursar");
        accountant = s.staffToken("accountant");
        lecturer = s.staffToken("lecturer");
        registrar = s.staffToken("registrar");
    }

    private <T> T db(Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    private long staffRecord(String no, String name, String account) throws Exception {
        long userId = account == null ? -1 : s.body(s.call(get("/api/hr/accounts"), hr, null)).findParents("fullName").stream()
                .filter(x -> x.get("fullName").asString().equals(account)).findFirst().orElseThrow().get("id").asLong();
        return s.body(s.call(post("/api/hr/staff"), hr, "{\"staffNo\":\"" + no + "\",\"fullName\":\"" + name + "\",\"gender\":\"F\",\"category\":\"ACADEMIC\","
                + "\"employment\":\"PERMANENT\",\"phone\":\"0754 111 222\"" + (account == null ? "" : ",\"userId\":" + userId) + "}")).get("id").asLong();
    }

    @Test
    void preparedApprovedBySomeoneElsePaidAndReadByStaff() throws Exception {
        s.call(get("/api/payroll/runs"), registrar, null).andExpect(status().isForbidden());
        // the rates: NSSF by default; SDL is off for colleges
        s.call(get("/api/payroll/settings"), hr, null).andExpect(jsonPath("$.pensionFund").value("NSSF")).andExpect(jsonPath("$.sdlApplies").value(false))
                .andExpect(jsonPath("$.payeBands.length()").value(5));

        long amina = staffRecord("BTC-001", "Amina Said", "Staff lecturer");
        long juma = staffRecord("BTC-002", "Juma Ally", null);
        staffRecord("BTC-003", "Neema Mrema", null);           // no pay details yet
        s.call(put("/api/payroll/staff/" + amina), registrar, "{}").andExpect(status().isForbidden());
        s.call(put("/api/payroll/staff/" + amina), hr, "{\"basicSalary\":1000000,\"tin\":\"12345\"}").andExpect(jsonPath("$.code").value("TIN_INVALID"));
        s.call(put("/api/payroll/staff/" + amina), hr, "{\"basicSalary\":1000000,\"heslbLoan\":true}").andExpect(jsonPath("$.code").value("HESLB_INDEX_REQUIRED"));
        s.call(put("/api/payroll/staff/" + amina), hr, "{\"basicSalary\":1000000,\"bankName\":\"CRDB\",\"bankBranch\":\"Azikiwe\",\"bankAccount\":\"0150 1234 56700\","
                + "\"tin\":\"123-456-789\",\"pensionNo\":\"NSSF-998\",\"heslbLoan\":true,\"heslbIndexNo\":\"S0101/0001/2010\"}")
                // gross 1,000,000; NSSF 100,000; PAYE on 900,000 = 103,000; HESLB 150,000
                .andExpect(jsonPath("$.preview.net").value(647000));
        s.call(post("/api/payroll/staff/" + amina + "/items"), hr, "{\"kind\":\"ALLOWANCE\",\"name\":\"Housing\",\"amount\":200000,\"taxable\":true}")
                .andExpect(jsonPath("$.items.length()").value(1));
        JsonNode d = s.body(s.call(post("/api/payroll/staff/" + amina + "/items"), hr,
                "{\"kind\":\"DEDUCTION\",\"name\":\"Salary advance\",\"amount\":100000,\"balance\":150000}"));
        long advance = d.get("items").findParents("name").stream().filter(x -> x.get("name").asString().equals("Salary advance")).findFirst().orElseThrow()
                .get("id").asLong();
        s.call(put("/api/payroll/staff/" + juma), accountant, "{\"basicSalary\":400000}").andExpect(status().isOk());

        // prepared by HR
        String month = YearMonth.now(TZ).toString();
        JsonNode run = s.body(s.call(post("/api/payroll/runs"), hr, "{\"month\":\"" + month + "\"}").andExpect(status().isOk()));
        long runId = run.at("/run/id").asLong();
        assertThat(run.at("/run/staffCount").asInt()).isEqualTo(2);
        // Amina: gross 1,200,000; NSSF 120,000; taxable 1,080,000 → PAYE 152,000; HESLB 150,000; advance 100,000 → net 678,000
        JsonNode amSlip = run.get("slips").findParents("staffNo").stream().filter(x -> x.get("staffNo").asString().equals("BTC-001")).findFirst().orElseThrow();
        assertThat(amSlip.get("paye").asInt()).isEqualTo(152000);
        assertThat(amSlip.get("net").asInt()).isEqualTo(678000);
        // the problems: Juma has no bank account; Neema has no salary
        assertThat(run.get("problems").toString()).contains("Juma Ally: no bank account").contains("1 member of staff have no basic salary");

        // the bank file waits for approval; the person who prepared it does not approve it
        s.call(get("/api/payroll/runs/" + runId + "/bank-file"), accountant, null).andExpect(jsonPath("$.code").value("PAYROLL_NOT_APPROVED"));
        s.call(post("/api/payroll/runs/" + runId + "/approve"), hr, null).andExpect(status().isForbidden());
        s.call(get("/api/approvals"), bursar, null).andExpect(jsonPath("$.items[?(@.kind == 'PAYROLL')].canAct").value(true));
        // worked out again after a change, still a draft
        s.call(post("/api/payroll/runs"), accountant, "{\"month\":\"" + month + "\"}").andExpect(jsonPath("$.run.preparedByName").value("Staff accountant"));
        s.call(post("/api/payroll/runs/" + runId + "/approve"), bursar, null).andExpect(jsonPath("$.run.status").value("APPROVED"));
        s.call(post("/api/payroll/runs"), hr, "{\"month\":\"" + month + "\"}").andExpect(jsonPath("$.code").value("PAYROLL_LOCKED"));

        String csv = s.call(get("/api/payroll/runs/" + runId + "/bank-file"), accountant, null).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("\"0150123456700\"").contains(",678000.00,").doesNotContain("Juma Ally");

        byte[] xlsx = s.call(get("/api/payroll/runs/" + runId + "/workbook"), bursar, null).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheet("PAYE").getRow(1).getCell(9).getNumericCellValue()).isEqualTo(152000.0);
            assertThat(wb.getSheet("HESLB").getLastRowNum()).isEqualTo(1);
            assertThat(wb.getSheet("NSSF")).isNotNull();
        }

        // paid: the advance's balance goes down; each person is told
        s.call(post("/api/payroll/runs/" + runId + "/paid"), lecturer, "{}").andExpect(status().isForbidden());
        s.call(post("/api/payroll/runs/" + runId + "/paid"), accountant, "{\"paidOn\":\"" + LocalDate.now(TZ) + "\"}").andExpect(jsonPath("$.run.status").value("PAID"));
        assertThat(db(() -> jdbc.queryForObject("select balance from staff_pay_items where id = ?", BigDecimal.class, advance))).isEqualByComparingTo("50000");
        assertThat(db(() -> jdbc.queryForObject("select count(*) from messages where template_code = 'PAYSLIP_READY'", Integer.class))).isEqualTo(2);

        // the lecturer reads their own payslip, not someone else's
        JsonNode mine = s.body(s.call(get("/api/my/payslips"), lecturer, null));
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).get("net").asInt()).isEqualTo(678000);
        long slipId = mine.get(0).get("id").asLong();
        byte[] pdf = s.call(get("/api/payroll/payslips/" + slipId + "/pdf"), lecturer, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        long jumaSlip = db(() -> jdbc.queryForObject("select id from payslips where staff_id = ?", Long.class, juma));
        s.call(get("/api/payroll/payslips/" + jumaSlip + "/pdf"), lecturer, null).andExpect(status().isForbidden());
        s.call(get("/api/my/payslips"), registrar, null).andExpect(jsonPath("$.code").value("NO_STAFF_RECORD"));
    }
}
