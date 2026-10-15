package tz.co.msingi.cims.platform.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Data migration with validation and sign-off (BR-39 / FR-ADM-S07) and students selected by external bodies (BR-08 / FR-ADM-15). */
@IntegrationTest
class ImportIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String tenant, registrar, admin, bursar, accountant, officer, lecturer;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "I" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        registrar = s.staffToken("registrar");
        admin = s.staffToken("admin");
        bursar = s.staffToken("bursar");
        accountant = s.staffToken("accountant");
        officer = s.staffToken("officer");
        lecturer = s.staffToken("lecturer");
    }

    private <T> T db(Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    private ResultActions upload(String token, String kind, String name, byte[] file, String query) throws Exception {
        var req = multipart("/api/imports/" + kind + (query == null ? "" : "?" + query)).file(new MockMultipartFile("file", name, "text/csv", file));
        req.header("X-Tenant", tenant).header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    private static final String STUDENTS = """
            registration_no,first_name,middle_name,surname,sex,date_of_birth,phone,email,nationality,programme_code,entry_year,intake_code,year_of_study,status
            OLD/19/0001,Asha,Juma,Mwinyi,F,14/03/2001,0712000001,,Tanzanian,BTCIT,2019/20,SEPTEMBER,2,ACTIVE
            OLD/19/0002,Baraka,,Said,Male,2000-07-01,0712000002,baraka@example.com,,BTCIT,2019,SEPTEMBER,1,
            """;

    @Test
    void studentsAreCheckedSignedOffByAnotherPersonAndBroughtIn() throws Exception {
        String bad = STUDENTS + "OLD/19/0001,Neema,,Kato,X,2001-01-01,0712000003,,,NOPE,2019/2020,SEPTEMBER,9,ACTIVE\n";
        JsonNode b1 = s.body(upload(registrar, "STUDENTS", "students.csv", bad.getBytes(StandardCharsets.UTF_8), null).andExpect(status().isCreated()));
        assertThat(b1.get("totalRows").asInt()).isEqualTo(3);
        assertThat(b1.get("errorRows").asInt()).isEqualTo(1);
        assertThat(b1.get("errors").toString()).contains("Appears twice in the file", "Use M or F", "No programme with this code", "Must be from 1 to 8");
        String report = s.text(s.call(get("/api/imports/batches/" + b1.get("id").asLong() + "/errors.csv"), registrar, null).andExpect(status().isOk()));
        assertThat(report).contains("Row,Column,Problem", "4,sex,Use M or F");
        s.call(post("/api/imports/batches/" + b1.get("id").asLong() + "/apply"), admin, null).andExpect(jsonPath("$.code").value("IMPORT_HAS_ERRORS"));
        s.call(post("/api/imports/batches/" + b1.get("id").asLong() + "/discard"), registrar, null).andExpect(jsonPath("$.status").value("DISCARDED"));

        JsonNode b2 = s.body(upload(registrar, "STUDENTS", "students.csv", STUDENTS.getBytes(StandardCharsets.UTF_8), null).andExpect(status().isCreated()));
        assertThat(b2.get("errorRows").asInt()).isZero();
        assertThat(b2.get("notes").toString()).contains("Academic year 2019/2020 will be added");
        assertThat(db(() -> jdbc.queryForObject("select count(*) from students", Integer.class))).isZero();   // nothing yet
        s.call(post("/api/imports/batches/" + b2.get("id").asLong() + "/apply"), registrar, null).andExpect(jsonPath("$.code").value("SIGN_OFF_OWN"));
        s.call(post("/api/imports/batches/" + b2.get("id").asLong() + "/apply"), lecturer, null).andExpect(status().isForbidden());
        s.call(post("/api/imports/batches/" + b2.get("id").asLong() + "/apply"), admin, "{\"note\":\"Checked against the 2019 register\"}")
                .andExpect(jsonPath("$.status").value("APPLIED")).andExpect(jsonPath("$.summary.students").value(2))
                .andExpect(jsonPath("$.summary.academicYearsAdded[0]").value("2019/2020"));
        assertThat(db(() -> jdbc.queryForObject("select count(*) from students where import_batch_id is not null and status = 'ACTIVE'", Integer.class))).isEqualTo(2);

        // the same file again: the students are there now
        JsonNode b3 = s.body(upload(registrar, "STUDENTS", "students.csv", STUDENTS.getBytes(StandardCharsets.UTF_8), null));
        assertThat(b3.get("errors").toString()).contains("Already in CIMS");

        // a migrated student sets a password with the phone and signs in to the student portal by registration number
        String code = s.body(s.call(post("/api/applicant-auth/forgot-password"), null, "{\"phone\":\"0712000001\"}")).get("devOtp").asText();
        s.call(post("/api/applicant-auth/reset-password"), null, "{\"phone\":\"0712000001\",\"code\":\"" + code + "\",\"newPassword\":\"Mbuyu#Kijani83\"}")
                .andExpect(status().is2xxSuccessful());
        s.call(post("/api/student-auth/login"), null, "{\"login\":\"OLD/19/0001\",\"password\":\"Mbuyu#Kijani83\"}").andExpect(status().isOk());

        // results from before CIMS, from an Excel file
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sh = wb.createSheet();
            String[][] rows = {
                    {"Registration No", "Academic Year", "Semester", "Year of study", "Module code", "Module title", "Credits", "Marks", "Grade", "Points", "GPA", "CGPA", "Decision"},
                    {"OLD/19/0001", "2019/2020", "1", "1", "ITU 04101", "Computer Applications", "12", "68", "B", "3", "3.4", "3.4", "PASS"},
                    {"OLD/19/0001", "2019/2020", "1", "1", "ITU 04102", "Communication Skills", "8", "75", "A", "4", "3.4", "3.4", "PASS"}};
            for (int r = 0; r < rows.length; r++) for (int c = 0; c < rows[r].length; c++) sh.createRow(r).getCell(c);
            for (int r = 0; r < rows.length; r++) { var row = sh.getRow(r); for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]); }
            wb.write(out);
            xlsx = out.toByteArray();
        }
        JsonNode res = s.body(upload(registrar, "RESULTS", "results.xlsx", xlsx, null).andExpect(status().isCreated()));
        assertThat(res.get("errorRows").asInt()).isZero();
        s.call(post("/api/imports/batches/" + res.get("id").asLong() + "/apply"), admin, null).andExpect(jsonPath("$.summary.modules").value(2));
        Long studentId = db(() -> jdbc.queryForObject("select id from students where registration_no = 'OLD/19/0001'", Long.class));
        s.call(get("/api/registry/students/" + studentId + "/earlier-results"), registrar, null)
                .andExpect(jsonPath("$[0].gpa").value(3.4)).andExpect(jsonPath("$[0].modules.length()").value(2));

        // opening balances: owed and in credit, by the Bursar's office, signed off by another member of it
        String balances = "registration_no,amount,as_at,description\nOLD/19/0001,350000,2026-06-30,\nOLD/19/0002,-20000,2026-06-30,Overpaid in 2025/2026\n";
        JsonNode bal = s.body(upload(accountant, "BALANCES", "balances.csv", balances.getBytes(StandardCharsets.UTF_8), null).andExpect(status().isCreated()));
        upload(registrar, "BALANCES", "balances.csv", balances.getBytes(StandardCharsets.UTF_8), null).andExpect(status().isForbidden());
        s.call(post("/api/imports/batches/" + bal.get("id").asLong() + "/apply"), bursar, null)
                .andExpect(jsonPath("$.summary.amountOwed").value(350000)).andExpect(jsonPath("$.summary.amountInCredit").value(20000));
        assertThat(db(() -> jdbc.queryForObject("""
                select total_amount from invoices where purpose = 'OPENING_BALANCE' and payer_id = (select id from students where registration_no = 'OLD/19/0001')""",
                BigDecimal.class))).isEqualByComparingTo("350000");
        assertThat(db(() -> jdbc.queryForObject("""
                select sum(p.unallocated_amount) from payments p join invoices i on i.id = p.invoice_id
                where i.payer_id = (select id from students where registration_no = 'OLD/19/0002')""", BigDecimal.class))).isEqualByComparingTo("20000");
    }

    @Test
    void studentsSelectedByTamisemiJoinTheWindowAsSelectedAndGoOnToVerification() throws Exception {
        String file = """
                index_number,exam_year,first_name,middle_name,surname,sex,date_of_birth,nationality,phone,programme_code,reference
                S0101/0001,2025,Asha,,Mwinyi,F,2008-05-20,,0713111001,BTCIT,TAM/26/001
                S0101/0003,2025,Juma,,Ali,M,2008-01-02,,0713111002,ODIT,TAM/26/002
                """;
        upload(officer, "SELECTIONS", "tamisemi.csv", file.getBytes(StandardCharsets.UTF_8), "windowId=" + inst.windowId())
                .andExpect(jsonPath("$.code").value("BODY_REQUIRED"));
        JsonNode b = s.body(upload(officer, "SELECTIONS", "tamisemi.csv", file.getBytes(StandardCharsets.UTF_8),
                "windowId=" + inst.windowId() + "&externalBody=TAMISEMI").andExpect(status().isCreated()));
        assertThat(b.get("errors").toString()).contains("This programme is not offered in the window");
        String ok = file.lines().limit(2).reduce((a, c) -> a + "\n" + c).orElseThrow() + "\n";
        JsonNode b2 = s.body(upload(officer, "SELECTIONS", "tamisemi.csv", ok.getBytes(StandardCharsets.UTF_8),
                "windowId=" + inst.windowId() + "&externalBody=TAMISEMI"));
        assertThat(b2.get("errorRows").asInt()).isZero();
        s.call(post("/api/imports/batches/" + b2.get("id").asLong() + "/apply"), admin, null).andExpect(jsonPath("$.summary.applications").value(1));
        assertThat(db(() -> jdbc.queryForObject("select status || ' ' || source || ' ' || external_body from applications", String.class)))
                .isEqualTo("SELECTED EXTERNAL TAMISEMI");
        assertThat(db(() -> jdbc.queryForObject("select body from messages where template_code = 'EXTERNAL_SELECTION'", String.class))).contains("TAMISEMI");

        // NACTVET verification takes them like any selected applicant
        long batch = s.body(s.call(post("/api/admission/windows/" + inst.windowId() + "/nactvet-submissions"), officer, "{\"channel\":\"API\"}")
                .andExpect(status().isOk())).get("id").asLong();
        s.call(post("/api/admission/nactvet-submissions/" + batch + "/fetch-outcomes"), officer, null).andExpect(status().isOk());
        assertThat(db(() -> jdbc.queryForObject("select status from applications", String.class))).isEqualTo("OFFERED");

        // the student signs up with the index number and takes over the account
        JsonNode reg = s.body(s.call(post("/api/applicant-auth/register"), null,
                "{\"indexNumber\":\"S0101/0001\",\"examYear\":2025,\"phone\":\"0713111001\",\"password\":\"Kifaru#Mto47\",\"acceptPrivacyNotice\":true}")
                .andExpect(status().isCreated()));
        String token = s.body(s.call(post("/api/applicant-auth/verify"), null, "{\"phone\":\"0713111001\",\"code\":\"" + reg.get("devOtp").asText() + "\"}"))
                .get("accessToken").asText();
        s.call(get("/api/applicant/applications"), token, null).andExpect(jsonPath("$[0].status").value("OFFERED"));

        // a second import of the same student is refused
        JsonNode again = s.body(upload(officer, "SELECTIONS", "tamisemi.csv", ok.getBytes(StandardCharsets.UTF_8), "windowId=" + inst.windowId() + "&externalBody=TAMISEMI"));
        assertThat(again.get("errors").toString()).contains("Already has an application in this window");
        String template = s.text(s.call(get("/api/imports/templates/SELECTIONS"), officer, null).andExpect(status().isOk()));
        assertThat(template).contains("index_number,exam_year");
    }
}
