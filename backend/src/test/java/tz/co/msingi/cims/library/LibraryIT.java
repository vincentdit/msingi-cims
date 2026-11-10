package tz.co.msingi.cims.library;

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
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-LIB-01..05: catalogue and import, lending and limits, renewals, fines billed to the student, lost items, reminders, clearance. */
@IntegrationTest
class LibraryIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingTime":"08:00","reportingVenue":"Main Hall",
             "documentsToBring":["Original CSEE certificate"],"signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired LibraryJob job;

    AdmissionScenario s;
    TestData.Institution inst;
    String tenant, officer, chair, registrar, librarian, hr, lecturer, studentToken;
    long studentId;
    String regNo;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "B" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "chair", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "librarian", Role.LIBRARIAN);
        data.staff(inst.tenant(), "hr", Role.HR_OFFICER);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        chair = s.staffToken("chair");
        registrar = s.staffToken("registrar");
        librarian = s.staffToken("librarian");
        hr = s.staffToken("hr");
        lecturer = s.staffToken("lecturer");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        Applicant a = s.apply("S0101/0001");
        s.admit(officer, chair, a);
        JsonNode handover = s.body(s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}").andExpect(status().isOk()));
        studentId = handover.get("students").get(0).get("studentId").asLong();
        regNo = s.body(s.call(get("/api/registry/students/" + studentId), registrar, null)).at("/summary/registrationNo").asString();
        studentToken = s.body(s.call(post("/api/student-auth/login"), null, "{\"login\":\"" + regNo + "\",\"password\":\"Kifaru#Mto47\"}")
                .andExpect(status().isOk())).get("accessToken").asString();
    }

    private <T> T db(Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    @Test
    void booksAreLentReturnedAndLateReturnsFined() throws Exception {
        s.call(put("/api/library/settings"), librarian, "{\"studentLoanDays\":14,\"staffLoanDays\":30,\"studentMaxLoans\":2,\"staffMaxLoans\":5,\"maxRenewals\":1,"
                + "\"finePerDay\":500,\"maxFine\":5000}").andExpect(jsonPath("$.finePerDay").value(500));
        s.call(post("/api/library/items"), lecturer, "{\"title\":\"x\"}").andExpect(status().isForbidden());
        long item = s.body(s.call(post("/api/library/items"), librarian, "{\"title\":\"Computer Networks\",\"authors\":\"A. Tanenbaum\",\"isbn\":\"978-0-13-212695-3\","
                + "\"year\":2010,\"callNumber\":\"004.6 TAN\",\"kind\":\"BOOK\"}").andExpect(status().isOk())).at("/item/id").asLong();
        s.call(post("/api/library/items"), librarian, "{\"title\":\"Bad\",\"isbn\":\"123\"}").andExpect(jsonPath("$.code").value("ISBN_INVALID"));
        s.call(post("/api/library/items/" + item + "/copies"), librarian, "{\"barcode\":\"LIB0001\",\"price\":45000}").andExpect(jsonPath("$.item.copies").value(1));
        s.call(post("/api/library/items/" + item + "/copies"), librarian, "{\"barcode\":\"LIB0002\",\"price\":45000}").andExpect(jsonPath("$.item.available").value(2));
        s.call(post("/api/library/items/" + item + "/copies"), librarian, "{\"barcode\":\"lib0001\"}").andExpect(jsonPath("$.code").value("BARCODE_TAKEN"));

        // import: two copies of a new title, one of an existing ISBN, one copy already known
        String csv = "Title,Authors,ISBN,Year,Call number,Barcode,Price\n"
                + "Electrical Installation,B. Scaddan,,2019,621.3 SCA,LIB0100,30000\n"
                + "Electrical Installation,B. Scaddan,,2019,621.3 SCA,LIB0101,30000\n"
                + "Computer Networks,A. Tanenbaum,9780132126953,2010,004.6 TAN,LIB0003,45000\n"
                + "Computer Networks,A. Tanenbaum,9780132126953,2010,004.6 TAN,LIB0001,45000\n";
        mvc.perform(multipart("/api/library/import").file(new MockMultipartFile("file", "books.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .header("X-Tenant", tenant).header("Authorization", "Bearer " + librarian))
                .andExpect(jsonPath("$.titlesAdded").value(1)).andExpect(jsonPath("$.copiesAdded").value(3)).andExpect(jsonPath("$.skipped").value(1));
        s.call(get("/api/library/items?q=tanenbaum"), librarian, null).andExpect(jsonPath("$[0].copies").value(3));

        // lending: the borrower is found by registration number; limits apply
        JsonNode b = s.body(s.call(get("/api/library/borrowers?q=" + regNo), librarian, null)).get(0);
        assertThat(b.get("canBorrow").asBoolean()).isTrue();
        long loan1 = s.body(s.call(post("/api/library/issue"), librarian, issue("LIB0001")).andExpect(status().isOk())).get("id").asLong();
        s.call(post("/api/library/issue"), librarian, issue("LIB0001")).andExpect(jsonPath("$.code").value("COPY_NOT_AVAILABLE"));
        long loan2 = s.body(s.call(post("/api/library/issue"), librarian, issue("LIB0100"))).get("id").asLong();
        s.call(post("/api/library/issue"), librarian, issue("LIB0101")).andExpect(jsonPath("$.code").value("CANNOT_BORROW"));
        s.call(get("/api/student/library"), studentToken, null).andExpect(jsonPath("$.onLoan.length()").value(2)).andExpect(jsonPath("$.maxLoans").value(2));
        s.call(get("/api/student/library/catalogue?q=networks"), studentToken, null).andExpect(jsonPath("$[0].available").value(2));

        // renewal; then the second loan becomes 4 days overdue: no renewal, no more borrowing, and a reminder once
        s.call(post("/api/library/loans/" + loan1 + "/renew"), librarian, null).andExpect(jsonPath("$.renewals").value(1));
        s.call(post("/api/library/loans/" + loan1 + "/renew"), librarian, null).andExpect(jsonPath("$.code").value("RENEWALS_USED"));
        db(() -> jdbc.update("update library_loans set due_on = current_date - 4 where id = ?", loan2));
        s.call(post("/api/library/loans/" + loan2 + "/renew"), librarian, null).andExpect(jsonPath("$.code").value("LOAN_OVERDUE"));
        s.call(get("/api/library/loans?which=OVERDUE"), librarian, null).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].daysLate").value(4));
        job.runNow();
        job.runNow();
        assertThat(db(() -> jdbc.queryForObject("select count(*) from messages where template_code = 'LIBRARY_OVERDUE'", Integer.class))).isEqualTo(1);

        // the clearance office cannot clear while books are out
        assertThat(db(() -> jdbc.queryForObject("select count(*) from library_loans where student_id = ? and returned_at is null", Integer.class, studentId))).isEqualTo(2);

        // late return: 4 days x 500 = 2,000 on an invoice with a control number, and an SMS
        JsonNode back = s.body(s.call(post("/api/library/return"), librarian, "{\"barcode\":\"LIB0100\"}").andExpect(status().isOk()));
        assertThat(back.get("fine").decimalValue()).isEqualByComparingTo("2000");
        assertThat(back.get("fineStatus").asString()).isEqualTo("BILLED");
        assertThat(db(() -> jdbc.queryForObject("select total_amount from invoices where purpose = 'LIBRARY_FINES'", BigDecimal.class))).isEqualByComparingTo("2000");
        assertThat(db(() -> jdbc.queryForObject("select count(*) from messages where template_code = 'LIBRARY_FINE'", Integer.class))).isEqualTo(1);
        s.call(get("/api/student/library"), studentToken, null).andExpect(jsonPath("$.unpaidFines").value(2000.0)).andExpect(jsonPath("$.history.length()").value(1));
        // waived, with the reason: the invoice is cancelled
        s.call(post("/api/library/loans/" + loan2 + "/waive"), librarian, "{\"note\":\"\"}").andExpect(jsonPath("$.code").value("FIELD_REQUIRED"));
        s.call(post("/api/library/loans/" + loan2 + "/waive"), librarian, "{\"note\":\"Was in hospital\"}").andExpect(jsonPath("$.fineStatus").value("WAIVED"));
        assertThat(db(() -> jdbc.queryForObject("select status from invoices where purpose = 'LIBRARY_FINES'", String.class))).isEqualTo("CANCELLED");

        // lost: the price is charged and the copy is marked lost
        s.call(post("/api/library/loans/" + loan1 + "/lost"), librarian, "{\"note\":\"Left on a bus\"}").andExpect(jsonPath("$.fine").value(45000.0))
                .andExpect(jsonPath("$.lost").value(true));
        s.call(get("/api/library/items/" + item), librarian, null).andExpect(jsonPath("$.copies[?(@.barcode=='LIB0001')].status").value("LOST"));
        s.call(get("/api/library/overview"), librarian, null).andExpect(jsonPath("$.lost").value(1)).andExpect(jsonPath("$.onLoan").value(0));
    }

    @Test
    void staffBorrowFromTheirStaffRecordAndPayFinesAtTheCashier() throws Exception {
        long staffId = s.body(s.call(post("/api/hr/staff"), hr, "{\"staffNo\":\"ST-001\",\"fullName\":\"Juma Mussa\",\"gender\":\"M\",\"category\":\"ACADEMIC\","
                + "\"employment\":\"PERMANENT\",\"phone\":\"0754 111 222\"}").andExpect(status().isOk())).get("id").asLong();
        long item = s.body(s.call(post("/api/library/items"), librarian, "{\"title\":\"Engineering Mathematics\",\"kind\":\"BOOK\"}")).at("/item/id").asLong();
        s.call(post("/api/library/items/" + item + "/copies"), librarian, "{\"barcode\":\"EM-1\"}").andExpect(status().isOk());
        long ref = s.body(s.call(post("/api/library/items"), librarian, "{\"title\":\"Oxford Dictionary\",\"kind\":\"REFERENCE\"}")).at("/item/id").asLong();
        s.call(post("/api/library/items/" + ref + "/copies"), librarian, "{\"barcode\":\"REF-1\"}").andExpect(jsonPath("$.copies[0].status").value("IN_LIBRARY"));

        JsonNode found = s.body(s.call(get("/api/library/borrowers?q=st-001"), librarian, null));
        assertThat(found.get(0).get("type").asString()).isEqualTo("STAFF");
        s.call(post("/api/library/issue"), librarian, "{\"barcode\":\"REF-1\",\"borrowerType\":\"STAFF\",\"borrowerId\":" + staffId + "}")
                .andExpect(jsonPath("$.code").value("COPY_NOT_AVAILABLE"));
        long loan = s.body(s.call(post("/api/library/issue"), librarian, "{\"barcode\":\"EM-1\",\"borrowerType\":\"STAFF\",\"borrowerId\":" + staffId + "}")
                .andExpect(status().isOk())).get("id").asLong();
        db(() -> jdbc.update("update library_loans set due_on = current_date - 100 where id = ?", loan));
        // capped at the maximum fine, to be paid at the cashier (no invoice)
        s.call(post("/api/library/return"), librarian, "{\"barcode\":\"EM-1\"}").andExpect(jsonPath("$.fine").value(20000.0)).andExpect(jsonPath("$.fineStatus").value("TO_PAY"));
        s.call(post("/api/library/loans/" + loan + "/fine-paid"), librarian, "{\"note\":\"RCT-778\"}").andExpect(jsonPath("$.fineStatus").value("PAID"));
        assertThat(db(() -> jdbc.queryForObject("select count(*) from invoices where purpose = 'LIBRARY_FINES'", Integer.class))).isZero();
        s.call(get("/api/library/loans?which=ALL&q=juma"), librarian, null).andExpect(jsonPath("$.length()").value(1));
    }

    private String issue(String barcode) {
        return "{\"barcode\":\"" + barcode + "\",\"borrowerType\":\"STUDENT\",\"borrowerId\":" + studentId + "}";
    }
}
