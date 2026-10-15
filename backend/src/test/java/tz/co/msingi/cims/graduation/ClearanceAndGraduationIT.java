package tz.co.msingi.cims.graduation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.exams.RegulationDefaults;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.AdmissionScenario.Applicant;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-GRD-01..04, BP-15, UC-18: clearance by offices, graduand list, Governing Board approval, certificates and verification. */
@IntegrationTest
class ClearanceAndGraduationIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingVenue":"Main Hall","documentsToBring":["Original CSEE certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired ProgrammeRepository programmes;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String admin, dpa, hod, otherHod, officer, registrar, lecturer, exams, accountant, bursar, librarian, warden, viewer;
    long yearId;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "G" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "dpa", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "hod", Role.HEAD_OF_DEPARTMENT);
        data.staffScoped(inst.tenant(), "otherhod", Role.HEAD_OF_DEPARTMENT, "DEPARTMENT", 999_999L);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        data.staff(inst.tenant(), "exams", Role.EXAMINATIONS_OFFICER);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "librarian", Role.CLEARANCE_OFFICER);
        data.staff(inst.tenant(), "warden", Role.HOSTEL_WARDEN);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        dpa = s.staffToken("dpa");
        hod = s.staffToken("hod");
        otherHod = s.staffToken("otherhod");
        officer = s.staffToken("officer");
        registrar = s.staffToken("registrar");
        lecturer = s.staffToken("lecturer");
        exams = s.staffToken("exams");
        accountant = s.staffToken("accountant");
        bursar = s.staffToken("bursar");
        librarian = s.staffToken("librarian");
        warden = s.staffToken("warden");
        viewer = s.staffToken("viewer");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
        s.call(post("/api/exams/regulations"), exams, json.writeValueAsString(RegulationDefaults.ntaCertificate(yearId))).andExpect(status().isCreated());
        // a one-semester programme, so the first published results are the final results
        TenantContext.runAs(inst.info(), () -> tx.executeWithoutResult(t -> {
            var p = programmes.findById(inst.programmeId()).orElseThrow();
            p.setDurationSemesters((short) 1);
        }));
    }

    @Test
    void finalistsAreClearedByEveryOfficeThenGraduateWithCertificates() throws Exception {
        Finalists f = finalists();

        // finalists: a and c have the award, b does not
        s.call(get("/api/clearance/finalists"), lecturer, null).andExpect(status().isForbidden());
        Map<String, JsonNode> fin = byRegNo(s.body(s.call(get("/api/clearance/finalists"), registrar, null)));
        assertThat(fin).hasSize(3);
        assertThat(fin.get(f.a).at("/award/qualified").asBoolean()).isTrue();
        assertThat(fin.get(f.a).at("/award/classification").asText()).isEqualTo("First Class");
        assertThat(fin.get(f.b).at("/award/qualified").asBoolean()).isFalse();
        assertThat(fin.get(f.b).at("/award/reason").asText()).startsWith("Final semester decision");

        // offices: the defaults, with officers assigned to library, hostel and sports
        JsonNode offices = s.body(s.call(get("/api/clearance/offices"), registrar, null));
        assertThat(offices.findValuesAsText("code")).containsExactly("HOD", "LIBRARY", "HOSTEL", "SPORTS", "FINANCE");
        Map<String, Long> office = new HashMap<>();
        offices.forEach(o -> office.put(o.get("code").asText(), o.get("id").asLong()));
        long librarianId = userId("Staff librarian"), wardenId = userId("Staff warden");
        s.call(put("/api/clearance/offices/" + office.get("LIBRARY")), librarian, officeBody("LIBRARY", "Library", "STAFF", 20, librarianId))
                .andExpect(status().isForbidden());
        s.call(put("/api/clearance/offices/" + office.get("LIBRARY")), registrar, officeBody("LIBRARY", "Library", "STAFF", 20, librarianId)).andExpect(status().isOk());
        s.call(put("/api/clearance/offices/" + office.get("HOSTEL")), registrar, officeBody("HOSTEL", "Hostel", "STAFF", 30, wardenId)).andExpect(status().isOk());
        s.call(put("/api/clearance/offices/" + office.get("SPORTS")), registrar, officeBody("SPORTS", "Sports and games", "STAFF", 40, librarianId)).andExpect(status().isOk());
        s.call(post("/api/clearance/offices"), registrar, officeBody("BURSARY2", "Second finance", "FINANCE", 60, null))
                .andExpect(jsonPath("$.code").value("ONE_FINANCE_OFFICE"));

        // fees: c owes; a has paid
        long item = s.body(s.call(post("/api/finance/fee-items"), accountant,
                "{\"code\":\"TUI\",\"name\":\"Tuition fee\",\"category\":\"TUITION\",\"allocationPriority\":10,\"active\":true}")).get("id").asLong();
        long fs = s.body(s.call(post("/api/finance/fee-structures"), accountant, "{\"academicYearId\":" + yearId + ",\"programmeId\":" + inst.programmeId()
                + ",\"yearOfStudy\":1,\"lines\":[{\"feeItemId\":" + item + ",\"amount\":100000,\"instalment\":1}]}")).get("id").asLong();
        s.call(post("/api/finance/fee-structures/" + fs + "/approve"), bursar, null).andExpect(status().isOk());
        s.call(post("/api/finance/billing-runs"), accountant, "{\"academicYearId\":" + yearId + "}").andExpect(status().isOk());
        payAnnualFees(f.aApplicant);

        // starting: a by themself on the portal; b is refused; c by Registry
        s.call(post("/api/student/clearance"), f.bToken, null).andExpect(jsonPath("$.code").value("NOT_A_FINALIST"));
        JsonNode mine = s.body(s.call(post("/api/student/clearance"), f.aToken, null).andExpect(status().isOk()));
        assertThat(mine.at("/clearance/total").asInt()).isEqualTo(5);
        Map<String, JsonNode> items = items(mine.get("clearance"));
        assertThat(items.get("FINANCE").get("status").asText()).isEqualTo("CLEARED");          // a owes nothing
        s.call(post("/api/student/clearance"), f.aToken, null).andExpect(jsonPath("$.code").value("CLEARANCE_STARTED"));
        JsonNode cc = s.body(s.call(post("/api/clearance/clearances"), registrar, "{\"studentId\":" + f.cId + "}").andExpect(status().isOk()));
        assertThat(items(cc).get("FINANCE").get("status").asText()).isEqualTo("HELD");
        assertThat(items(cc).get("FINANCE").get("note").asText()).startsWith("TZS 100,000 outstanding");

        // deciding
        long lib = items.get("LIBRARY").get("id").asLong(), hodItem = items.get("HOD").get("id").asLong();
        s.call(put("/api/clearance/items/" + lib), warden, "{\"status\":\"CLEARED\"}").andExpect(status().isForbidden());
        s.call(put("/api/clearance/items/" + lib), librarian, "{\"status\":\"HELD\"}").andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        s.call(put("/api/clearance/items/" + lib), librarian, "{\"status\":\"HELD\",\"note\":\"Two books overdue: return them\"}")
                .andExpect(jsonPath("$.status").value("HELD"));
        s.call(put("/api/clearance/items/" + hodItem), otherHod, "{\"status\":\"CLEARED\"}").andExpect(status().isForbidden());
        s.call(put("/api/clearance/items/" + hodItem), hod, "{\"status\":\"CLEARED\",\"note\":\"Project report received\"}").andExpect(status().isOk());
        s.call(put("/api/clearance/items/" + items.get("HOSTEL").get("id").asLong()), warden, "{\"status\":\"CLEARED\"}").andExpect(status().isOk());
        s.call(put("/api/clearance/items/" + items.get("SPORTS").get("id").asLong()), librarian, "{\"status\":\"CLEARED\"}").andExpect(status().isOk());
        s.call(put("/api/clearance/items/" + items.get("FINANCE").get("id").asLong()), registrar, "{\"status\":\"CLEARED\"}")
                .andExpect(jsonPath("$.code").value("FINANCE_AUTOMATIC"));
        JsonNode queue = s.body(s.call(get("/api/clearance/offices/" + office.get("LIBRARY") + "/queue"), librarian, null));
        assertThat(queue).hasSize(2);
        s.call(get("/api/clearance/offices/" + office.get("LIBRARY") + "/queue"), warden, null).andExpect(status().isForbidden());
        assertThat(s.body(s.call(get("/api/student/clearance"), f.aToken, null)).at("/clearance/status").asText()).isEqualTo("IN_PROGRESS");
        s.call(put("/api/clearance/items/" + lib), librarian, "{\"status\":\"CLEARED\",\"note\":\"Books returned\"}").andExpect(status().isOk());
        mine = s.body(s.call(get("/api/student/clearance"), f.aToken, null));
        assertThat(mine.at("/clearance/status").asText()).isEqualTo("CLEARED");
        List<String> sms = s.body(s.call(get("/api/messaging/messages?size=100"), viewer, null)).get("content").findValuesAsText("templateCode");
        assertThat(sms).contains("CLEARANCE_HELD", "CLEARANCE_COMPLETE");

        // graduation: only a is ready
        long gid = s.body(s.call(post("/api/graduations"), registrar, "{\"name\":\"1st Graduation 2028\",\"ceremonyDate\":\"2028-11-24\"}")
                .andExpect(status().isCreated())).get("id").asLong();
        JsonNode gen = s.body(s.call(post("/api/graduations/" + gid + "/generate"), registrar, null));
        assertThat(gen.get("added").asInt()).isEqualTo(1);
        Map<String, JsonNode> notReady = byRegNo(gen.get("notReady"));
        assertThat(notReady.get(f.b).get("reason").asText()).startsWith("Final semester decision");
        assertThat(notReady.get(f.c).get("reason").asText()).startsWith("Clearance in progress (");
        s.call(post("/api/graduations/" + gid + "/submit"), registrar, null).andExpect(jsonPath("$.status").value("SUBMITTED"));
        s.call(post("/api/graduations/" + gid + "/generate"), registrar, null).andExpect(jsonPath("$.code").value("LIST_SUBMITTED"));
        s.call(put("/api/clearance/items/" + lib), librarian, "{\"status\":\"HELD\",\"note\":\"late fine\"}").andExpect(jsonPath("$.code").value("GRADUAND_LISTED"));
        s.call(post("/api/graduations/" + gid + "/approve"), registrar, "{\"boardMinuteRef\":\"GB/2028/04\"}").andExpect(status().isForbidden());
        s.call(post("/api/graduations/" + gid + "/return"), admin, "{\"note\":\"Add the ceremony venue first\"}").andExpect(jsonPath("$.status").value("DRAFT"));
        // the administrator submits it, so another Board member must approve
        s.call(post("/api/graduations/" + gid + "/submit"), admin, null).andExpect(status().isOk());
        s.call(post("/api/graduations/" + gid + "/approve"), admin, "{\"boardMinuteRef\":\"GB/2028/04\"}").andExpect(jsonPath("$.code").value("SEGREGATION_OF_DUTIES"));
        data.staff(inst.tenant(), "chair", Role.INSTITUTION_ADMIN);
        String chair = s.staffToken("chair");
        JsonNode g = s.body(s.call(post("/api/graduations/" + gid + "/approve"), chair, "{\"boardMinuteRef\":\"GB/2028/04\"}")
                .andExpect(jsonPath("$.status").value("APPROVED")));
        assertThat(g.get("graduands")).hasSize(1);
        assertThat(g.at("/graduands/0/awardName").asText()).isEqualTo("Basic Technician Certificate (NTA Level 4)");
        assertThat(s.body(s.call(get("/api/registry/students/" + f.aId), registrar, null)).at("/summary/status").asText()).isEqualTo("GRADUATED");
        assertThat(s.body(s.call(get("/api/messaging/messages?size=100"), viewer, null)).get("content").findValuesAsText("templateCode"))
                .contains("GRADUATION_APPROVED");

        // certificates: numbered once, each print logged, reprints need a reason
        assertThat(s.body(s.call(post("/api/graduations/" + gid + "/certificates"), registrar, null)).get("issued").asInt()).isEqualTo(1);
        assertThat(s.body(s.call(post("/api/graduations/" + gid + "/certificates"), registrar, null)).get("alreadyIssued").asInt()).isEqualTo(1);
        byte[] pdf = mvc.perform(post("/api/graduations/" + gid + "/certificates/print").header("Authorization", "Bearer " + registrar)
                .header("X-Tenant", s.tenant)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        s.call(post("/api/graduations/" + gid + "/certificates/print"), registrar, null).andExpect(jsonPath("$.code").value("REPRINT_REASON_REQUIRED"));
        mvc.perform(post("/api/graduations/" + gid + "/certificates/print").header("Authorization", "Bearer " + registrar).header("X-Tenant", s.tenant)
                .contentType("application/json").content("{\"reason\":\"Original damaged in the post\"}")).andExpect(status().isOk());
        g = s.body(s.call(get("/api/graduations/" + gid), registrar, null));
        assertThat(g.at("/graduands/0/printCount").asInt()).isEqualTo(2);
        String code = g.at("/graduands/0/verificationCode").asText();
        assertThat(g.at("/graduands/0/certificateNo").asText()).contains("/CERT/");

        // public verification shows the award without unnecessary personal data
        JsonNode v = s.body(s.call(get("/api/public/verify/" + code), null, null).andExpect(status().isOk()));
        assertThat(v.get("documentType").asText()).isEqualTo("AWARD_CERTIFICATE");
        assertThat(v.get("award").asText()).isEqualTo("Basic Technician Certificate (NTA Level 4)");
        assertThat(v.has("dateOfBirth")).isFalse();

        // NACTVET graduand file and submission record
        byte[] xlsx = mvc.perform(get("/api/graduations/" + gid + "/nactvet-file").header("Authorization", "Bearer " + registrar).header("X-Tenant", s.tenant))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(1000);
        s.call(put("/api/graduations/" + gid + "/nactvet"), registrar, "{\"status\":\"ACCEPTED\"}").andExpect(jsonPath("$.code").value("NOT_SUBMITTED"));
        s.call(put("/api/graduations/" + gid + "/nactvet"), registrar, "{\"status\":\"SUBMITTED\",\"reference\":\"NACTVET/GRD/2028/77\"}")
                .andExpect(jsonPath("$.nactvetStatus").value("SUBMITTED"));

        // the student's view; reports
        mine = s.body(s.call(get("/api/student/clearance"), f.aToken, null));
        assertThat(mine.at("/graduation/status").asText()).isEqualTo("APPROVED");
        assertThat(mine.at("/graduation/ceremonyDate").asText()).isEqualTo("2028-11-24");
        assertThat(s.body(s.call(get("/api/reports/GRD-01"), registrar, null)).get("rowCount").asInt()).isEqualTo(2);
        assertThat(s.body(s.call(get("/api/reports/GRD-02"), registrar, null)).get("rowCount").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ scenario

    record Finalists(String a, String b, String c, long aId, long cId, String aToken, String bToken, Applicant aApplicant) {
    }

    /** Three students of a one-semester programme with published results: a and c pass, b fails. */
    private Finalists finalists() throws Exception {
        long m1 = s.body(s.call(post("/api/academic/modules"), hod, "{\"code\":\"ITU 04101\",\"title\":\"Computing\",\"credits\":10,\"ntaLevel\":4,"
                + "\"assessmentType\":\"GRADED\",\"active\":true}").andExpect(status().isCreated())).get("id").asLong();
        long cid = s.body(s.call(post("/api/academic/curricula"), hod, "{\"programmeId\":" + inst.programmeId() + ",\"effectiveYearId\":" + yearId
                + ",\"modules\":[{\"moduleId\":" + m1 + ",\"yearOfStudy\":1,\"semesterNo\":1,\"category\":\"CORE\"}],\"electiveRules\":[]}")).get("id").asLong();
        s.call(post("/api/academic/curricula/" + cid + "/approve"), dpa, null).andExpect(status().isOk());
        long semId = s.body(s.call(post("/api/academic/semesters"), dpa, "{\"academicYearId\":" + yearId + ",\"semesterNo\":1,\"name\":\"Semester 1\","
                + "\"startDate\":\"2027-09-27\",\"endDate\":\"2028-02-10\",\"registrationOpens\":\"" + TODAY.minusDays(1) + "\",\"registrationCloses\":\""
                + TODAY.plusDays(10) + "\",\"marksEntryOpens\":\"" + TODAY.minusDays(1) + "\",\"marksEntryCloses\":\"" + TODAY.plusDays(30) + "\"}")
                .andExpect(status().isCreated())).get("id").asLong();
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 400);
        Applicant c = s.applyEligibleEndingIn(5, 900);
        s.admit(officer, dpa, a, b, c);
        JsonNode handover = s.body(s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}"));
        Map<String, String> byPhone = new HashMap<>();
        Map<String, Long> idByRegNo = new HashMap<>();
        for (JsonNode st : handover.get("students")) {
            long id = st.get("studentId").asLong();
            JsonNode d = s.body(s.call(get("/api/registry/students/" + id), registrar, null));
            s.call(put("/api/registry/students/" + id + "/first-registration"), registrar,
                    "{\"documents\":[{\"item\":\"Original CSEE certificate\",\"verified\":true}]}").andExpect(status().isOk());
            s.call(post("/api/registry/students/" + id + "/first-registration/approve"), registrar, null).andExpect(status().isOk());
            byPhone.put(d.at("/summary/phone").asText(), d.at("/summary/registrationNo").asText());
            idByRegNo.put(d.at("/summary/registrationNo").asText(), id);
        }
        String[] regNos = new String[3];
        String[] tokens = new String[3];
        Applicant[] apps = {a, b, c};
        for (int i = 0; i < 3; i++) {
            String phone = s.body(s.call(get("/api/applicant/profile"), apps[i].token(), null)).get("phone").asText();
            regNos[i] = byPhone.get(phone);
            tokens[i] = s.body(s.call(post("/api/student-auth/login"), null, "{\"login\":\"" + regNos[i] + "\",\"password\":\"Kifaru#Mto47\"}")).get("accessToken").asText();
            s.call(post("/api/student/semester-registration"), tokens[i], "{\"semesterId\":" + semId + ",\"electiveModuleIds\":[],\"detailsConfirmed\":true}")
                    .andExpect(status().isOk());
        }
        s.call(post("/api/registry/semester-registrations/approve-all"), registrar, "{\"semesterId\":" + semId + "}").andExpect(jsonPath("$.approved").value(3));
        s.call(post("/api/exams/offerings/setup"), exams, "{\"semesterId\":" + semId + "}").andExpect(status().isOk());
        long off = s.body(s.call(get("/api/exams/offerings?semesterId=" + semId), exams, null)).get(0).get("id").asLong();
        s.call(put("/api/exams/offerings/" + off + "/staff"), hod, "{\"userIds\":[" + userId("Staff lecturer") + "]}").andExpect(status().isOk());
        JsonNode v = s.body(s.call(get("/api/exams/offerings/" + off), lecturer, null));
        long ca = v.get("components").get(0).get("id").asLong(), se = v.get("components").get(1).get("id").asLong();
        Map<String, Long> reg = new HashMap<>();
        v.get("rows").forEach(x -> reg.put(x.get("registrationNo").asText(), x.get("moduleRegistrationId").asLong()));
        String[] marks = {"85", "20", "70"};
        StringBuilder e = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) e.append(',');
            e.append(entry(reg.get(regNos[i]), ca, marks[i])).append(',').append(entry(reg.get(regNos[i]), se, marks[i]));
        }
        s.call(put("/api/exams/offerings/" + off + "/scores"), lecturer, "{\"entries\":[" + e + "]}").andExpect(status().isOk());
        s.call(post("/api/exams/offerings/" + off + "/submit"), lecturer, null).andExpect(status().isOk());
        s.call(post("/api/exams/offerings/" + off + "/hod-approve"), hod, null).andExpect(status().isOk());
        s.call(post("/api/exams/offerings/" + off + "/board-approve"), exams, null).andExpect(status().isOk());
        long sheet = s.body(s.call(post("/api/exams/sheets/compute"), exams, "{\"semesterId\":" + semId + ",\"programmeId\":" + inst.programmeId()
                + ",\"yearOfStudy\":1}").andExpect(status().isOk())).get("id").asLong();
        s.call(post("/api/exams/sheets/" + sheet + "/recommend"), dpa, "{\"note\":\"AC 1/2028\"}").andExpect(status().isOk());
        s.call(post("/api/exams/sheets/" + sheet + "/confirm"), admin, null).andExpect(status().isOk());
        s.call(post("/api/exams/sheets/" + sheet + "/publish"), exams, null).andExpect(jsonPath("$.status").value("PUBLISHED"));
        return new Finalists(regNos[0], regNos[1], regNos[2], idByRegNo.get(regNos[0]), idByRegNo.get(regNos[2]), tokens[0], tokens[1], a);
    }

    private long userId(String fullName) throws Exception {
        return s.body(s.call(get("/api/clearance/staff"), registrar, null)).findParents("fullName").stream()
                .filter(x -> x.get("fullName").asText().equals(fullName)).findFirst().orElseThrow().get("id").asLong();
    }

    private static String officeBody(String code, String name, String kind, int order, Long staffId) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"kind\":\"" + kind + "\",\"sortOrder\":" + order + ",\"active\":true,\"staffUserIds\":["
                + (staffId == null ? "" : staffId) + "]}";
    }

    private static String entry(long reg, long comp, String score) {
        return "{\"moduleRegistrationId\":" + reg + ",\"componentId\":" + comp + ",\"score\":" + score + "}";
    }

    private static Map<String, JsonNode> byRegNo(JsonNode arr) {
        Map<String, JsonNode> m = new HashMap<>();
        arr.forEach(x -> m.put(x.get("registrationNo").asText(), x));
        return m;
    }

    private static Map<String, JsonNode> items(JsonNode clearance) {
        Map<String, JsonNode> m = new HashMap<>();
        clearance.get("items").forEach(x -> m.put(x.get("officeCode").asText(), x));
        return m;
    }

    private void payAnnualFees(Applicant a) throws Exception {
        for (JsonNode inv : s.body(s.call(get("/api/applicant/account"), a.token(), null)).get("invoices")) {
            if (inv.get("purpose").asText().equals("ANNUAL_FEES") && inv.get("balance").decimalValue().signum() > 0) {
                s.call(post("/api/applicant/invoices/" + inv.get("id").asLong() + "/simulate-payment"), a.token(), "{}").andExpect(status().isOk());
            }
        }
    }
}
