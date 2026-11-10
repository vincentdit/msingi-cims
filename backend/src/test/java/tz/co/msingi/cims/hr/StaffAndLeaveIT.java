package tz.co.msingi.cims.hr;

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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-HR-01..04: staff records and qualifications, the staffing return, leave requests, approval by the Head of Department and balances. */
@IntegrationTest
class StaffAndLeaveIT {

    static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String tenant, hr, hod, otherHod, lecturer, admin, registrar;
    long departmentId;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "H" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        departmentId = TenantContext.callAs(inst.info(), () -> tx.execute(x -> jdbc.queryForObject(
                "select department_id from programmes where id = ?", Long.class, inst.programmeId())));
        data.staff(inst.tenant(), "hr", Role.HR_OFFICER);
        data.staffScoped(inst.tenant(), "hod", Role.HEAD_OF_DEPARTMENT, "DEPARTMENT", departmentId);
        data.staffScoped(inst.tenant(), "otherhod", Role.HEAD_OF_DEPARTMENT, "DEPARTMENT", 999_999L);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        hr = s.staffToken("hr");
        hod = s.staffToken("hod");
        otherHod = s.staffToken("otherhod");
        lecturer = s.staffToken("lecturer");
        admin = s.staffToken("admin");
        registrar = s.staffToken("registrar");
    }

    private <T> T db(Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    private long accountId(String fullName) throws Exception {
        return s.body(s.call(get("/api/hr/accounts"), hr, null)).findParents("fullName").stream()
                .filter(x -> x.get("fullName").asString().equals(fullName)).findFirst().orElseThrow().get("id").asLong();
    }

    @Test
    void staffRecordsQualificationsAndTheStaffingReturn() throws Exception {
        s.call(post("/api/hr/staff"), registrar, "{}").andExpect(status().isForbidden());
        String body = "{\"staffNo\":\"BTC-014\",\"title\":\"Ms\",\"fullName\":\"Neema Kweka\",\"gender\":\"F\",\"category\":\"ACADEMIC\",\"employment\":\"CONTRACT\","
                + "\"jobTitle\":\"Tutor\",\"departmentId\":" + departmentId + ",\"phone\":\"0713 222 333\",\"contractEndsOn\":\"" + LocalDate.now(TZ).plusDays(20) + "\","
                + "\"userId\":" + accountId("Staff lecturer") + "}";
        long id = s.body(s.call(post("/api/hr/staff"), hr, body).andExpect(status().isOk())).get("id").asLong();
        s.call(post("/api/hr/staff"), hr, body).andExpect(jsonPath("$.code").value("STAFF_NO_TAKEN"));
        s.call(post("/api/hr/staff"), hr, body.replace("BTC-014", "BTC-015").replace("\"CONTRACT\"", "\"PERMANENT\"")).andExpect(jsonPath("$.code").value("ACCOUNT_LINKED"));
        s.call(post("/api/hr/staff"), hr, "{\"staffNo\":\"BTC-020\",\"fullName\":\"Ally Hassan\",\"gender\":\"M\",\"category\":\"ACADEMIC\",\"employment\":\"CONTRACT\"}")
                .andExpect(jsonPath("$.code").value("CONTRACT_END"));

        // a qualification with its certificate raises the highest qualification; HR verifies it
        mvc.perform(multipart("/api/hr/staff/" + id + "/qualifications").file(new MockMultipartFile("file", "degree.pdf", "application/pdf",
                        "%PDF-1.4 degree".getBytes(StandardCharsets.US_ASCII))).param("level", "BACHELOR").param("award", "BSc Computer Science")
                        .param("institution", "University of Dar es Salaam").param("year", "2015").header("X-Tenant", tenant).header("Authorization", "Bearer " + hr))
                .andExpect(jsonPath("$.highestQualification").value("BACHELOR")).andExpect(jsonPath("$.qualifications[0].fileName").value("degree.pdf"));
        long q = s.body(s.call(get("/api/hr/staff/" + id), hr, null)).at("/qualifications/0/id").asLong();
        s.call(post("/api/hr/qualifications/" + q + "/verify"), hr, null).andExpect(jsonPath("$.qualifications[0].verifiedByName").value("Staff hr"));
        s.call(get("/api/hr/qualifications/" + q + "/file"), hr, null).andExpect(status().isOk());

        // import: one good row, one with a missing gender
        String csv = "Staff no,Full name,Gender,Category,Employment,Job title,Highest qualification\n"
                + "BTC-030,Peter Mollel,Male,Support,Permanent,Driver,Certificate\n"
                + "BTC-031,Asha Said,,Academic,Permanent,Tutor,Masters\n";
        mvc.perform(multipart("/api/hr/staff/import").file(new MockMultipartFile("file", "staff.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .header("X-Tenant", tenant).header("Authorization", "Bearer " + hr)).andExpect(jsonPath("$.added").value(1)).andExpect(jsonPath("$.skipped").value(1));

        s.call(get("/api/hr/overview"), hr, null).andExpect(jsonPath("$.active").value(2)).andExpect(jsonPath("$.academic").value(1))
                .andExpect(jsonPath("$.contractsEnding").value(1));
        byte[] xlsx = s.call(get("/api/hr/staffing-return"), hr, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sum = wb.getSheet("Summary");
            // the bachelor row (MASTERS, PHD... in reverse order of the list): female 1, total 1
            boolean found = false;
            for (var row : sum) {
                if (row.getCell(0) != null && "Bachelor".equals(row.getCell(0).getStringCellValue())) {
                    assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(1.0);
                    found = true;
                }
            }
            assertThat(found).isTrue();
            assertThat(wb.getSheet("Academic staff").getRow(1).getCell(1).getStringCellValue()).isEqualTo("Neema Kweka");
            assertThat(wb.getSheet("Other staff").getRow(1).getCell(1).getStringCellValue()).isEqualTo("Peter Mollel");
        }

        s.call(put("/api/hr/staff/" + id + "/left"), hr, "{\"leftOn\":\"" + LocalDate.now(TZ) + "\",\"reason\":\"RESIGNED\"}").andExpect(jsonPath("$.status").value("LEFT"));
        s.call(get("/api/hr/staff"), hr, null).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void leaveIsRequestedApprovedByTheHeadOfDepartmentAndCountedInWorkingDays() throws Exception {
        long staffId = s.body(s.call(post("/api/hr/staff"), hr, "{\"staffNo\":\"BTC-001\",\"fullName\":\"Staff lecturer\",\"gender\":\"M\",\"category\":\"ACADEMIC\","
                + "\"employment\":\"PERMANENT\",\"departmentId\":" + departmentId + ",\"phone\":\"0754 333 444\",\"userId\":" + accountId("Staff lecturer") + "}"))
                .get("id").asLong();
        s.call(get("/api/my/leave"), hod, null).andExpect(jsonPath("$.code").value("NO_STAFF_RECORD"));
        JsonNode mine = s.body(s.call(get("/api/my/leave"), lecturer, null).andExpect(status().isOk()));
        // a man is not offered maternity leave; annual leave is 28 days
        assertThat(mine.get("types").findValuesAsString("code")).contains("ANNUAL", "PATERNITY").doesNotContain("MATERNITY");
        long annual = 0, paternity = 0;
        for (JsonNode t : mine.get("types")) {
            if (t.get("code").asString().equals("ANNUAL")) annual = t.get("id").asLong();
            if (t.get("code").asString().equals("PATERNITY")) paternity = t.get("id").asLong();
        }

        // working days: Monday to Friday of a week with a public holiday HR added counts 4 days (paternity is in working days)
        int year = LocalDate.now(TZ).getYear() + 1;
        LocalDate monday = LocalDate.of(year, 3, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        s.call(post("/api/leave/holidays"), hr, "{\"day\":\"" + monday.plusDays(2) + "\",\"name\":\"Eid el-Fitr\"}").andExpect(status().isOk());
        s.call(post("/api/leave/holidays"), hr, "{\"day\":\"" + year + "-12-09\",\"name\":\"Again\"}").andExpect(jsonPath("$.code").value("HOLIDAY_FIXED"));
        s.call(get("/api/leave/count?leaveTypeId=" + paternity + "&from=" + monday + "&to=" + monday.plusDays(6)), lecturer, null)
                .andExpect(jsonPath("$.days").value(4)).andExpect(jsonPath("$.skipped.length()").value(1));
        s.call(post("/api/my/leave"), lecturer, req(paternity, monday, monday.plusDays(4))).andExpect(jsonPath("$.code").value("NOT_ENOUGH_DAYS"));

        long r1 = s.body(s.call(post("/api/my/leave"), lecturer, req(annual, monday, monday.plusDays(9))).andExpect(status().isOk())).get("id").asLong();
        // annual leave counts calendar days: 10
        s.call(get("/api/my/leave?year=" + year), lecturer, null).andExpect(jsonPath("$.requests[0].days").value(10))
                .andExpect(jsonPath("$.balances[?(@.code=='ANNUAL')].pending").value(10));
        s.call(post("/api/my/leave"), lecturer, req(annual, monday.plusDays(5), monday.plusDays(6))).andExpect(jsonPath("$.code").value("LEAVE_OVERLAPS"));
        s.call(post("/api/my/leave"), lecturer, req(annual, monday.plusDays(20), monday.plusDays(39))).andExpect(jsonPath("$.code").value("NOT_ENOUGH_DAYS"));

        // the Head of this department sees and decides it; another Head does not; the person cannot approve their own
        s.call(get("/api/leave/requests"), hod, null).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].canDecide").value(true));
        s.call(get("/api/leave/requests"), otherHod, null).andExpect(jsonPath("$.length()").value(0));
        s.call(post("/api/leave/requests/" + r1 + "/decision"), otherHod, "{\"approve\":true}").andExpect(status().isForbidden());
        s.call(post("/api/leave/requests/" + r1 + "/decision"), lecturer, "{\"approve\":true}").andExpect(status().isForbidden());
        s.call(post("/api/leave/requests/" + r1 + "/decision"), hod, "{\"approve\":true,\"note\":\"Enjoy\"}").andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedByName").value("Staff hod"));
        assertThat(db(() -> jdbc.queryForObject("select count(*) from messages where template_code = 'LEAVE_APPROVED'", Integer.class))).isEqualTo(1);
        s.call(get("/api/hr/staff/" + staffId + "/leave-balances?year=" + year), hr, null)
                .andExpect(jsonPath("$[?(@.code=='ANNUAL')].taken").value(10)).andExpect(jsonPath("$[?(@.code=='ANNUAL')].remaining").value(18));
        s.call(get("/api/leave/away?day=" + monday.plusDays(3)), hod, null).andExpect(jsonPath("$.length()").value(1));

        // days carried forward by HR; a refusal needs a reason; HR records leave already taken (approved at once)
        s.call(post("/api/hr/staff/" + staffId + "/leave-adjustments"), hr, "{\"leaveTypeId\":" + annual + ",\"year\":" + year + ",\"days\":5,\"reason\":\"Carried forward\"}")
                .andExpect(jsonPath("$[?(@.code=='ANNUAL')].remaining").value(23));
        long r2 = s.body(s.call(post("/api/my/leave"), lecturer, req(annual, monday.plusDays(30), monday.plusDays(31)))).get("id").asLong();
        s.call(post("/api/leave/requests/" + r2 + "/decision"), hr, "{\"approve\":false}").andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        s.call(post("/api/leave/requests/" + r2 + "/decision"), hr, "{\"approve\":false,\"note\":\"Examinations week\"}").andExpect(jsonPath("$.status").value("REJECTED"));
        s.call(post("/api/hr/staff/" + staffId + "/leave"), hr, req(annual, monday.plusDays(60), monday.plusDays(61))).andExpect(jsonPath("$.status").value("APPROVED"));
        s.call(post("/api/hr/staff/" + staffId + "/leave"), hod, req(annual, monday.plusDays(70), monday.plusDays(71))).andExpect(status().isForbidden());

        // the person cancels approved leave that has not started
        s.call(post("/api/leave/requests/" + r1 + "/cancel"), lecturer, null).andExpect(jsonPath("$.status").value("CANCELLED"));
        s.call(get("/api/hr/staff/" + staffId + "/leave-balances?year=" + year), hr, null).andExpect(jsonPath("$[?(@.code=='ANNUAL')].taken").value(2));
    }

    private static String req(long type, LocalDate from, LocalDate to) {
        return "{\"leaveTypeId\":" + type + ",\"startOn\":\"" + from + "\",\"endOn\":\"" + to + "\",\"reason\":\"Family\"}";
    }
}
