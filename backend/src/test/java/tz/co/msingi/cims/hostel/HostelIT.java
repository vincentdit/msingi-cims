package tz.co.msingi.cims.hostel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-HST-01..05, BRU-18, BP-12: inventory, applications, allocation by priority, invoices, release of unpaid beds, check-in and out. */
@IntegrationTest
class HostelIT {

    static final String INSTRUCTIONS = """
            {"reportingDate":"2027-09-27","reportingVenue":"Main Hall","documentsToBring":["Original CSEE certificate"],
             "signatoryName":"Dr. Amina Salum","signatoryTitle":"Principal"}""";
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired StudentRepository studentRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    AdmissionScenario s;
    TestData.Institution inst;
    String officer, dpa, registrar, warden, lecturer, viewer;
    long yearId;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "H" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "dpa", Role.DEPUTY_PRINCIPAL_ACADEMIC);
        data.staff(inst.tenant(), "registrar", Role.REGISTRAR);
        data.staff(inst.tenant(), "warden", Role.HOSTEL_WARDEN);
        data.staff(inst.tenant(), "lecturer", Role.LECTURER);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        officer = s.staffToken("officer");
        dpa = s.staffToken("dpa");
        registrar = s.staffToken("registrar");
        warden = s.staffToken("warden");
        lecturer = s.staffToken("lecturer");
        viewer = s.staffToken("viewer");
        s.call(put("/api/admission/windows/" + inst.windowId() + "/joining-instructions"), officer, INSTRUCTIONS).andExpect(status().isOk());
        yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
    }

    @Test
    void bedsAreAllocatedByPriorityPaidForCheckedInAndOutAndReleasedWhenUnpaid() throws Exception {
        Students st = students();

        // inventory: a female, a male and a mixed hostel
        s.call(post("/api/hostel/fee-categories"), lecturer, "{\"code\":\"STD\",\"name\":\"Standard\",\"amount\":300000}").andExpect(status().isForbidden());
        long std = s.body(s.call(post("/api/hostel/fee-categories"), warden, "{\"code\":\"STD\",\"name\":\"Standard\",\"amount\":300000}")
                .andExpect(status().isCreated())).get("id").asLong();
        long kili = hostel("KILI", "Kilimanjaro", "FEMALE"), sere = hostel("SERE", "Serengeti", "MALE"), miku = hostel("MIKU", "Mikumi", "MIXED");
        s.call(post("/api/hostel/rooms"), warden, room(kili, "A", "1", "DOUBLE", "MALE", std, false, "AVAILABLE", 2)).andExpect(jsonPath("$.code").value("GENDER_CLASH"));
        s.call(post("/api/hostel/rooms"), warden, room(miku, "", "1", "SINGLE", null, std, false, "AVAILABLE", 1)).andExpect(jsonPath("$.code").value("GENDER_REQUIRED"));
        JsonNode a1 = s.body(s.call(post("/api/hostel/rooms"), warden, room(kili, "A", "1", "DOUBLE", null, std, false, "AVAILABLE", 2)).andExpect(status().isCreated()));
        assertThat(a1.get("gender").asText()).isEqualTo("FEMALE");
        assertThat(a1.get("beds").findValuesAsText("label")).containsExactly("A", "B");
        long a2 = s.body(s.call(post("/api/hostel/rooms"), warden, room(kili, "A", "2", "SINGLE", null, std, true, "MAINTENANCE", 1))).get("id").asLong();
        s.call(post("/api/hostel/rooms"), warden, room(kili, "A", "2", "SINGLE", null, std, true, "AVAILABLE", 1)).andExpect(jsonPath("$.code").value("DUPLICATE_ROOM"));
        JsonNode s1 = s.body(s.call(post("/api/hostel/rooms"), warden, room(sere, "", "1", "DOUBLE", null, std, false, "AVAILABLE", 2)));
        s.call(post("/api/hostel/rooms"), warden, room(miku, "", "1", "SINGLE", "FEMALE", std, false, "CLOSED", 1)).andExpect(status().isCreated());

        // window and applications
        long win = s.body(s.call(post("/api/hostel/windows"), warden, "{\"academicYearId\":" + yearId + ",\"name\":\"Hostels 2027/2028\",\"opensOn\":\""
                + TODAY.minusDays(1) + "\",\"closesOn\":\"" + TODAY.plusDays(10) + "\",\"paymentDays\":7}").andExpect(status().isCreated())).get("id").asLong();
        JsonNode mine = s.body(s.call(get("/api/student/hostel"), st.dToken, null));
        assertThat(mine.get("openWindows")).hasSize(1);
        assertThat(mine.get("hostels").findValuesAsText("name")).containsExactlyInAnyOrder("Serengeti", "Mikumi");
        s.call(post("/api/student/hostel/applications"), st.dToken, apply(win, kili, false)).andExpect(jsonPath("$.code").value("WRONG_HOSTEL"));
        s.call(post("/api/student/hostel/applications"), st.aToken, apply(win, kili, false)).andExpect(jsonPath("$.application.status").value("SUBMITTED"));
        s.call(post("/api/student/hostel/applications"), st.bToken, apply(win, kili, false)).andExpect(status().isOk());
        s.call(post("/api/student/hostel/applications"), st.cToken, apply(win, kili, true)).andExpect(status().isOk());
        s.call(post("/api/student/hostel/applications"), st.dToken, apply(win, sere, false)).andExpect(status().isOk());
        Map<String, JsonNode> apps = byRegNo(s.body(s.call(get("/api/hostel/windows/" + win + "/applications"), warden, null)));
        assertThat(apps.get(st.c).get("priority").asInt()).isGreaterThan(apps.get(st.a).get("priority").asInt());   // special needs first

        // automatic allocation: two female beds for three female applicants
        s.call(post("/api/hostel/windows/" + win + "/allocate"), lecturer, null).andExpect(status().isForbidden());
        JsonNode run = s.body(s.call(post("/api/hostel/windows/" + win + "/allocate"), warden, null).andExpect(status().isOk()));
        assertThat(run.get("allocated").asInt()).isEqualTo(3);
        assertThat(run.get("waitlisted").asInt()).isEqualTo(1);
        apps = byRegNo(s.body(s.call(get("/api/hostel/windows/" + win + "/applications"), warden, null)));
        assertThat(apps.get(st.c).get("status").asText()).isEqualTo("ALLOCATED");
        assertThat(apps.get(st.a).get("status").asText()).isEqualTo("ALLOCATED");
        assertThat(apps.get(st.b).get("status").asText()).isEqualTo("WAITLISTED");
        assertThat(apps.get(st.d).at("/allocation/hostel").asText()).isEqualTo("Serengeti");
        JsonNode aAlloc = apps.get(st.a).get("allocation");
        assertThat(aAlloc.get("fee").decimalValue()).isEqualByComparingTo("300000");
        assertThat(aAlloc.get("controlNo").asText()).isNotBlank();
        assertThat(aAlloc.get("payBy").asText()).isEqualTo(TODAY.plusDays(7).toString());
        List<String> sms = s.body(s.call(get("/api/messaging/messages?size=100"), viewer, null)).get("content").findValuesAsText("templateCode");
        assertThat(sms.stream().filter("HOSTEL_ALLOCATED"::equals).count()).isEqualTo(3);
        assertThat(sms).contains("HOSTEL_WAITLISTED");

        // manual allocation: b gets the accessible room once it is back in service; never a male room
        long bApp = apps.get(st.b).get("id").asLong();
        assertThat(s.body(s.call(get("/api/hostel/applications/" + bApp + "/free-beds"), warden, null))).isEmpty();
        s.call(put("/api/hostel/rooms/" + a2), warden, room(kili, "A", "2", "SINGLE", null, std, true, "AVAILABLE", 1)).andExpect(status().isOk());
        JsonNode free = s.body(s.call(get("/api/hostel/applications/" + bApp + "/free-beds"), warden, null));
        assertThat(free).hasSize(1);
        long sereBed = s1.get("beds").get(1).get("id").asLong();
        s.call(post("/api/hostel/allocations"), warden, "{\"applicationId\":" + bApp + ",\"bedId\":" + sereBed + "}").andExpect(jsonPath("$.code").value("GENDER_MISMATCH"));
        long bedA2 = free.get(0).get("bedId").asLong();
        s.call(post("/api/hostel/allocations"), warden, "{\"applicationId\":" + bApp + ",\"bedId\":" + bedA2 + "}").andExpect(jsonPath("$.method").value("MANUAL"));
        s.call(post("/api/hostel/allocations"), warden, "{\"applicationId\":" + bApp + ",\"bedId\":" + bedA2 + "}").andExpect(jsonPath("$.code").value("ALREADY_DECIDED"));
        // BRU-18 in the database itself: a second active allocation of the same bed is refused
        assertThatThrownBy(() -> TenantContext.runAs(inst.info(), () -> tx.executeWithoutResult(t -> jdbc.update("""
                insert into hostel_allocations (tenant_id, application_id, window_id, student_id, bed_id, status, fee, pay_by, method, allocated_by_name)
                select tenant_id, application_id, window_id, student_id, bed_id, 'ALLOCATED', fee, pay_by, 'MANUAL', 'test'
                from hostel_allocations where bed_id = ? and status = 'ALLOCATED'""", bedA2)))).hasMessageContaining("uq_hostel_allocations_bed");

        // check-in only once paid; check-out with damages invoiced
        long aId = aAlloc.get("id").asLong();
        s.call(post("/api/hostel/allocations/" + aId + "/check-in"), warden, "{}").andExpect(jsonPath("$.code").value("HOSTEL_FEE_UNPAID"));
        s.call(post("/api/student/invoices/" + aAlloc.get("invoiceId").asLong() + "/simulate-payment"), st.aToken, "{}").andExpect(status().isOk());
        s.call(post("/api/hostel/allocations/" + aId + "/check-in"), warden, "{\"note\":\"Room clean; mattress and chair in good order\"}")
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));
        s.call(post("/api/hostel/allocations/" + aId + "/check-out"), warden, "{\"damageCharge\":20000}").andExpect(jsonPath("$.code").value("DAMAGES_REQUIRED"));
        JsonNode out = s.body(s.call(post("/api/hostel/allocations/" + aId + "/check-out"), warden,
                "{\"condition\":\"Clean\",\"damages\":\"Broken chair\",\"damageCharge\":20000}").andExpect(status().isOk()));
        assertThat(out.get("status").asText()).isEqualTo("CHECKED_OUT");
        assertThat(out.get("damageInvoiceNo").asText()).isNotBlank();

        // unpaid allocations are released after the payment date; the invoice is cancelled
        long dId = apps.get(st.d).at("/allocation/id").asLong();
        TenantContext.runAs(inst.info(), () -> tx.executeWithoutResult(t -> jdbc.update("update hostel_allocations set pay_by = ? where id = ?",
                java.sql.Date.valueOf(TODAY.minusDays(1)), dId)));
        JsonNode rel = s.body(s.call(post("/api/hostel/allocations/release-unpaid"), warden, null));
        assertThat(rel.get("released").asInt()).isEqualTo(1);
        JsonNode dAlloc = byRegNo(s.body(s.call(get("/api/hostel/windows/" + win + "/applications"), warden, null))).get(st.d);
        assertThat(dAlloc.get("status").asText()).isEqualTo("RELEASED");
        assertThat(dAlloc.at("/allocation/invoiceStatus").asText()).isEqualTo("CANCELLED");
        assertThat(s.body(s.call(get("/api/messaging/messages?size=100"), viewer, null)).get("content").findValuesAsText("templateCode")).contains("HOSTEL_RELEASED");

        // a warden can cancel an unpaid allocation with a reason
        long cId = apps.get(st.c).at("/allocation/id").asLong();
        s.call(post("/api/hostel/allocations/" + cId + "/cancel"), warden, "{\"reason\":\"Student withdrew the request\"}").andExpect(jsonPath("$.status").value("CANCELLED"));

        // the inventory and the student's own view
        JsonNode hostels = s.body(s.call(get("/api/hostel/hostels"), viewer, null));
        JsonNode k = hostels.findParents("code").stream().filter(x -> x.get("code").asText().equals("KILI")).findFirst().orElseThrow();
        assertThat(k.get("beds").asInt()).isEqualTo(3);
        assertThat(k.get("allocatedUnpaid").asInt()).isEqualTo(1);       // b in A-2
        assertThat(k.get("available").asInt()).isEqualTo(2);
        mine = s.body(s.call(get("/api/student/hostel"), st.aToken, null));
        assertThat(mine.at("/application/status").asText()).isEqualTo("ALLOCATED");
        assertThat(mine.get("history").findValuesAsText("status")).contains("CHECKED_OUT");
        assertThat(s.body(s.call(get("/api/reports/HST-01"), viewer, null)).get("rowCount").asInt()).isEqualTo(3);
        assertThat(s.body(s.call(get("/api/reports/HST-02?academicYearId=" + yearId), warden, null)).get("rowCount").asInt()).isEqualTo(4);
    }

    @Test
    void wardensWorkOnlyWithTheirHostelsAndStudentsReportRepairs() throws Exception {
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "warden2", Role.HOSTEL_WARDEN);
        String admin = s.staffToken("admin"), warden2 = s.staffToken("warden2");
        Students st = students();
        long std = s.body(s.call(post("/api/hostel/fee-categories"), warden, "{\"code\":\"STD\",\"name\":\"Standard\",\"amount\":300000}")).get("id").asLong();
        long kili = hostel("KILI", "Kilimanjaro", "FEMALE"), sere = hostel("SERE", "Serengeti", "MALE");
        s.call(post("/api/hostel/rooms"), warden, room(kili, "A", "1", "DOUBLE", null, std, false, "AVAILABLE", 2)).andExpect(status().isCreated());
        long sereRoom = s.body(s.call(post("/api/hostel/rooms"), warden, room(sere, "", "1", "DOUBLE", null, std, false, "AVAILABLE", 2))).get("id").asLong();
        long win = s.body(s.call(post("/api/hostel/windows"), warden, "{\"academicYearId\":" + yearId + ",\"name\":\"Hostels 2027/2028\",\"opensOn\":\""
                + TODAY.minusDays(1) + "\",\"closesOn\":\"" + TODAY.plusDays(10) + "\",\"paymentDays\":7}")).get("id").asLong();
        // b has not applied yet: the portal says so
        JsonNode next = s.body(s.call(get("/api/student/dashboard"), st.bToken, null)).get("next");
        assertThat(next.findValuesAsText("kind")).contains("HOSTEL_APPLY");
        s.call(post("/api/student/hostel/applications"), st.aToken, apply(win, kili, false)).andExpect(status().isOk());
        s.call(post("/api/student/hostel/applications"), st.dToken, apply(win, sere, false)).andExpect(status().isOk());
        s.call(post("/api/hostel/windows/" + win + "/allocate"), warden, null).andExpect(jsonPath("$.allocated").value(2));
        assertThat(s.body(s.call(get("/api/student/dashboard"), st.aToken, null)).get("next").findValuesAsText("kind")).contains("HOSTEL_PAY");

        // the administrator assigns the second warden to Serengeti
        long w2 = userId("warden2"), lect = userId("lecturer");
        s.call(put("/api/hostel/hostels/" + sere + "/wardens"), warden, "{\"userIds\":[" + w2 + "]}").andExpect(status().isForbidden());
        s.call(put("/api/hostel/hostels/" + sere + "/wardens"), admin, "{\"userIds\":[" + lect + "]}").andExpect(jsonPath("$.code").value("NOT_A_WARDEN"));
        JsonNode d = s.body(s.call(put("/api/hostel/hostels/" + sere + "/wardens"), admin, "{\"userIds\":[" + w2 + "]}").andExpect(status().isOk()));
        assertThat(d.get("wardens").findValuesAsText("fullName")).containsExactly("Staff warden2");

        // the assigned warden sees and runs only Serengeti; window-wide work is for wardens of every hostel
        assertThat(s.body(s.call(get("/api/hostel/hostels"), warden2, null)).findValuesAsText("code")).containsExactly("SERE");
        s.call(get("/api/hostel/hostels/" + kili), warden2, null).andExpect(status().isForbidden());
        s.call(post("/api/hostel/rooms"), warden2, room(kili, "B", "1", "SINGLE", null, std, false, "AVAILABLE", 1)).andExpect(status().isForbidden());
        s.call(post("/api/hostel/windows/" + win + "/allocate"), warden2, null).andExpect(status().isForbidden());
        s.call(post("/api/hostel/windows"), warden2, "{\"academicYearId\":" + yearId + ",\"name\":\"Again\",\"opensOn\":\"" + TODAY + "\",\"closesOn\":\""
                + TODAY.plusDays(5) + "\",\"paymentDays\":7}").andExpect(status().isForbidden());
        JsonNode allocs = s.body(s.call(get("/api/hostel/windows/" + win + "/allocations"), warden2, null));
        assertThat(allocs.findValuesAsText("registrationNo")).containsExactly(st.d);
        long aAlloc = s.body(s.call(get("/api/hostel/windows/" + win + "/allocations"), warden, null)).findParents("registrationNo").stream()
                .filter(x -> x.get("registrationNo").asText().equals(st.a)).findFirst().orElseThrow().get("id").asLong();
        s.call(post("/api/hostel/allocations/" + aAlloc + "/cancel"), warden2, "{\"reason\":\"Not my hostel at all\"}").andExpect(status().isForbidden());
        assertThat(s.body(s.call(get("/api/hostel/hostels"), warden, null)).findValuesAsText("code")).containsExactlyInAnyOrder("KILI", "SERE");

        // repairs: a reports one from the portal; b has no room; the Kilimanjaro side handles it, the Serengeti warden does not see it
        s.call(post("/api/student/hostel/repairs"), st.bToken, "{\"category\":\"ELECTRICAL\",\"description\":\"The light does not work\"}")
                .andExpect(jsonPath("$.code").value("NO_ROOM"));
        long rep = s.body(s.call(post("/api/student/hostel/repairs"), st.aToken, "{\"category\":\"ELECTRICAL\",\"priority\":\"URGENT\","
                + "\"description\":\"The socket by the window sparks\"}").andExpect(status().isOk())).get("id").asLong();
        assertThat(s.body(s.call(get("/api/hostel/repairs?open=true"), warden2, null))).isEmpty();
        s.call(post("/api/hostel/repairs/" + rep + "/status"), warden2, "{\"status\":\"IN_PROGRESS\"}").andExpect(status().isForbidden());
        JsonNode list = s.body(s.call(get("/api/hostel/repairs?open=true"), warden, null));
        assertThat(list).hasSize(1);
        assertThat(list.at("/0/room").asText()).isEqualTo("A · Room 1");
        assertThat(list.at("/0/canUpdate").asBoolean()).isTrue();
        s.call(post("/api/hostel/repairs/" + rep + "/status"), warden, "{\"status\":\"IN_PROGRESS\",\"note\":\"Electrician booked\"}").andExpect(status().isOk());
        s.call(post("/api/hostel/repairs/" + rep + "/status"), warden, "{\"status\":\"DONE\"}").andExpect(jsonPath("$.code").value("NOTE_REQUIRED"));
        s.call(post("/api/hostel/repairs/" + rep + "/status"), warden, "{\"status\":\"DONE\",\"note\":\"Socket replaced\"}").andExpect(jsonPath("$.status").value("DONE"));
        assertThat(s.body(s.call(get("/api/student/hostel/repairs"), st.aToken, null)).at("/0/status").asText()).isEqualTo("DONE");
        assertThat(s.body(s.call(get("/api/messaging/messages?size=100"), viewer, null)).get("content").findValuesAsText("templateCode")
                .stream().filter("HOSTEL_REPAIR_UPDATED"::equals).count()).isEqualTo(2);
        // a warden logs one for a room without a student
        s.call(post("/api/hostel/repairs"), warden2, "{\"hostelId\":" + sere + ",\"roomId\":" + sereRoom + ",\"category\":\"PLUMBING\",\"description\":\"Shower leaks\"}")
                .andExpect(status().isOk());
        s.call(post("/api/hostel/repairs"), warden2, "{\"hostelId\":" + kili + ",\"category\":\"PLUMBING\",\"description\":\"Tap leaks\"}").andExpect(status().isForbidden());
    }

    @Test
    void bedChangesChargeOrRefundTheDifferenceAndEndingAStayEarlyIsRefunded() throws Exception {
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        String bursar = s.staffToken("bursar");
        Students st = students();
        long std = s.body(s.call(post("/api/hostel/fee-categories"), warden, "{\"code\":\"STD\",\"name\":\"Standard\",\"amount\":300000}")).get("id").asLong();
        long prm = s.body(s.call(post("/api/hostel/fee-categories"), warden, "{\"code\":\"PRM\",\"name\":\"Premium\",\"amount\":420000}")).get("id").asLong();
        long kili = hostel("KILI", "Kilimanjaro", "FEMALE"), sere = hostel("SERE", "Serengeti", "MALE");
        long bedA1 = s.body(s.call(post("/api/hostel/rooms"), warden, room(kili, "A", "1", "DOUBLE", null, std, false, "AVAILABLE", 2))).at("/beds/0/id").asLong();
        long bedA2 = s.body(s.call(post("/api/hostel/rooms"), warden, room(kili, "A", "2", "SINGLE", null, prm, false, "AVAILABLE", 1))).at("/beds/0/id").asLong();
        long bedA3 = s.body(s.call(post("/api/hostel/rooms"), warden, room(kili, "A", "3", "SINGLE", null, std, false, "AVAILABLE", 1))).at("/beds/0/id").asLong();
        long bedS1 = s.body(s.call(post("/api/hostel/rooms"), warden, room(sere, "", "1", "SINGLE", null, std, false, "AVAILABLE", 1))).at("/beds/0/id").asLong();
        // occupancy period of 100 days, 10 of them gone: 90% of the year is left today
        long win = s.body(s.call(post("/api/hostel/windows"), warden, "{\"academicYearId\":" + yearId + ",\"name\":\"Hostels 2027/2028\",\"opensOn\":\""
                + TODAY.minusDays(1) + "\",\"closesOn\":\"" + TODAY.plusDays(10) + "\",\"occupancyStarts\":\"" + TODAY.minusDays(10)
                + "\",\"occupancyEnds\":\"" + TODAY.plusDays(89) + "\",\"paymentDays\":7}")).get("id").asLong();
        for (String t : List.of(st.aToken, st.bToken, st.cToken)) s.call(post("/api/student/hostel/applications"), t, apply(win, kili, false)).andExpect(status().isOk());
        Map<String, JsonNode> apps = byRegNo(s.body(s.call(get("/api/hostel/windows/" + win + "/applications"), warden, null)));
        long aAlloc = s.body(s.call(post("/api/hostel/allocations"), warden, "{\"applicationId\":" + apps.get(st.a).get("id").asLong() + ",\"bedId\":" + bedA1 + "}"))
                .get("id").asLong();
        s.call(post("/api/student/invoices/" + allocation(win, st.a).get("invoiceId").asLong() + "/simulate-payment"), st.aToken, "{}").andExpect(status().isOk());

        // a moves to the premium room: 90% of the 120,000 difference is billed on a new invoice
        s.call(post("/api/hostel/allocations/" + aAlloc + "/move"), lecturer, "{\"bedId\":" + bedA2 + ",\"reason\":\"Asked for a single room\"}").andExpect(status().isForbidden());
        s.call(post("/api/hostel/allocations/" + aAlloc + "/move"), warden, "{\"bedId\":" + bedA1 + ",\"reason\":\"No change at all\"}").andExpect(jsonPath("$.code").value("SAME_BED"));
        s.call(post("/api/hostel/allocations/" + aAlloc + "/move"), warden, "{\"bedId\":" + bedS1 + ",\"reason\":\"Wrong hostel here\"}").andExpect(jsonPath("$.code").value("GENDER_MISMATCH"));
        JsonNode moved = s.body(s.call(post("/api/hostel/allocations/" + aAlloc + "/move"), warden, "{\"bedId\":" + bedA2 + ",\"reason\":\"Asked for a single room\"}")
                .andExpect(status().isOk()));
        assertThat(moved.get("room").asText()).isEqualTo("A · Room 2");
        assertThat(moved.get("bedChanges").asInt()).isEqualTo(1);
        assertThat(moved.get("fee").decimalValue()).isEqualByComparingTo("408000");
        JsonNode changes = s.body(s.call(get("/api/hostel/allocations/" + aAlloc + "/changes"), warden, null));
        assertThat(changes.at("/0/difference").decimalValue()).isEqualByComparingTo("108000");
        assertThat(changes.at("/0/invoiceNo").asText()).isNotBlank();
        assertThat(changes.at("/0/fromBed").asText()).isEqualTo("Kilimanjaro, A · Room 1, bed A");
        assertThat(s.body(s.call(get("/api/messaging/messages?size=100"), viewer, null)).get("content").findValuesAsText("templateCode")).contains("HOSTEL_BED_CHANGED");

        // and back to a standard room: the paid fee is now too high, so a refund of 108,000 waits for the bursary
        s.call(post("/api/hostel/allocations/" + aAlloc + "/move"), warden, "{\"bedId\":" + bedA3 + ",\"reason\":\"Prefers to share costs\"}").andExpect(status().isOk());
        JsonNode back = s.body(s.call(get("/api/hostel/allocations/" + aAlloc + "/changes"), warden, null)).get(0);
        assertThat(back.get("difference").decimalValue()).isEqualByComparingTo("-108000");
        assertThat(back.get("adjustmentKind").asText()).isEqualTo("REFUND");
        assertThat(back.get("adjustmentStatus").asText()).isEqualTo("PENDING");
        long refundId = s.body(s.call(get("/api/finance/adjustments"), bursar, null)).findParents("adjustmentNo").stream()
                .filter(x -> x.get("adjustmentNo").asText().equals(back.get("adjustmentNo").asText())).findFirst().orElseThrow().get("id").asLong();
        long hostelInvoice = allocation(win, st.a).get("invoiceId").asLong();
        JsonNode done = s.body(s.call(post("/api/finance/adjustments/" + refundId + "/approve"), bursar, "{}").andExpect(status().isOk()));
        assertThat(done.get("status").asText()).isEqualTo("APPROVED");
        assertThat(done.get("documentNo").asText()).contains("RFV");
        assertThat(invoiceTotals(hostelInvoice)).containsExactly(new BigDecimal("192000.00"), new BigDecimal("192000.00"));

        // checked in, then out a day later with the unused part refunded (89 of 100 days of what was paid)
        s.call(post("/api/hostel/allocations/" + aAlloc + "/check-in"), warden, "{}").andExpect(jsonPath("$.status").value("CHECKED_IN"));
        JsonNode out = s.body(s.call(post("/api/hostel/allocations/" + aAlloc + "/check-out"), warden, "{\"condition\":\"Clean\",\"refundUnused\":true}")
                .andExpect(status().isOk()));
        assertThat(out.at("/refund/amount").decimalValue()).isEqualByComparingTo("170880");
        assertThat(out.at("/refund/status").asText()).isEqualTo("PENDING");

        // b paid and is cancelled before check-in: everything paid is offered back
        long bAlloc = s.body(s.call(post("/api/hostel/allocations"), warden, "{\"applicationId\":" + apps.get(st.b).get("id").asLong() + ",\"bedId\":" + bedA1 + "}"))
                .get("id").asLong();
        s.call(post("/api/student/invoices/" + allocation(win, st.b).get("invoiceId").asLong() + "/simulate-payment"), st.bToken, "{}").andExpect(status().isOk());
        JsonNode cancelled = s.body(s.call(post("/api/hostel/allocations/" + bAlloc + "/cancel"), warden, "{\"reason\":\"Moving to family in town\"}").andExpect(status().isOk()));
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.at("/refund/amount").decimalValue()).isEqualByComparingTo("300000");

        // c has an unpaid bed and withdraws: the bed is released and its invoice cancelled
        long cAlloc = s.body(s.call(post("/api/hostel/allocations"), warden, "{\"applicationId\":" + apps.get(st.c).get("id").asLong() + ",\"bedId\":" + bedA1 + "}"))
                .get("id").asLong();
        long cId = TenantContext.callAs(inst.info(), () -> tx.execute(t -> jdbc.queryForObject("select id from students where registration_no = ?", Long.class, st.c)));
        long change = s.body(s.call(post("/api/registry/students/" + cId + "/status-changes"), registrar,
                "{\"type\":\"WITHDRAWAL\",\"reason\":\"Joined another college\",\"effectiveDate\":\"" + TODAY + "\"}")).get("id").asLong();
        s.call(post("/api/registry/status-changes/" + change + "/decision"), dpa, "{\"approve\":true}").andExpect(jsonPath("$.state").value("APPLIED"));
        JsonNode cView = allocation(win, st.c);
        assertThat(cView.get("id").asLong()).isEqualTo(cAlloc);
        assertThat(cView.get("status").asText()).isEqualTo("RELEASED");
        assertThat(cView.get("invoiceStatus").asText()).isEqualTo("CANCELLED");
    }

    private JsonNode allocation(long window, String regNo) throws Exception {
        return s.body(s.call(get("/api/hostel/windows/" + window + "/allocations"), warden, null)).findParents("registrationNo").stream()
                .filter(x -> x.get("registrationNo").asText().equals(regNo)).findFirst().orElseThrow();
    }

    private List<BigDecimal> invoiceTotals(long invoiceId) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(t -> jdbc.queryForObject("select total_amount, paid_amount from invoices where id = ?",
                (rs, n) -> List.of(rs.getBigDecimal(1), rs.getBigDecimal(2)), invoiceId)));
    }

    private long userId(String username) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(t -> jdbc.queryForObject("select id from user_accounts where username = ?", Long.class, username)));
    }

    // ------------------------------------------------------------------ scenario

    record Students(String a, String b, String c, String d, String aToken, String bToken, String cToken, String dToken) {
    }

    /** Four active students: a, b and c female, d male. */
    private Students students() throws Exception {
        Applicant a = s.apply("S0101/0001");
        Applicant b = s.applyEligibleEndingIn(5, 400);
        Applicant c = s.applyEligibleEndingIn(5, 900);
        Applicant d = s.applyEligibleEndingIn(5, 1300);
        s.admit(officer, dpa, a, b, c, d);
        JsonNode handover = s.body(s.call(post("/api/registry/windows/" + inst.windowId() + "/handover"), registrar, "{}"));
        Map<String, String> byPhone = new HashMap<>();
        Map<String, Long> ids = new HashMap<>();
        for (JsonNode sn : handover.get("students")) {
            long id = sn.get("studentId").asLong();
            JsonNode det = s.body(s.call(get("/api/registry/students/" + id), registrar, null));
            s.call(put("/api/registry/students/" + id + "/first-registration"), registrar,
                    "{\"documents\":[{\"item\":\"Original CSEE certificate\",\"verified\":true}]}").andExpect(status().isOk());
            s.call(post("/api/registry/students/" + id + "/first-registration/approve"), registrar, null).andExpect(status().isOk());
            byPhone.put(det.at("/summary/phone").asText(), det.at("/summary/registrationNo").asText());
            ids.put(det.at("/summary/registrationNo").asText(), id);
        }
        Applicant[] apps = {a, b, c, d};
        String[] regNos = new String[4];
        String[] tokens = new String[4];
        for (int i = 0; i < 4; i++) {
            String phone = s.body(s.call(get("/api/applicant/profile"), apps[i].token(), null)).get("phone").asText();
            regNos[i] = byPhone.get(phone);
            tokens[i] = s.body(s.call(post("/api/student-auth/login"), null, "{\"login\":\"" + regNos[i] + "\",\"password\":\"Kifaru#Mto47\"}")).get("accessToken").asText();
        }
        long dId = ids.get(regNos[3]);
        TenantContext.runAs(inst.info(), () -> tx.executeWithoutResult(t -> studentRepo.findById(dId).orElseThrow().setSex("M")));
        return new Students(regNos[0], regNos[1], regNos[2], regNos[3], tokens[0], tokens[1], tokens[2], tokens[3]);
    }

    private long hostel(String code, String name, String gender) throws Exception {
        return s.body(s.call(post("/api/hostel/hostels"), warden, "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"gender\":\"" + gender + "\"}")
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private static String room(long hostel, String block, String no, String type, String gender, long fee, boolean accessible, String status, int beds) {
        return "{\"hostelId\":" + hostel + ",\"block\":\"" + block + "\",\"floor\":1,\"roomNo\":\"" + no + "\",\"roomType\":\"" + type + "\","
                + (gender == null ? "" : "\"gender\":\"" + gender + "\",") + "\"feeCategoryId\":" + fee + ",\"accessible\":" + accessible
                + ",\"status\":\"" + status + "\",\"beds\":" + beds + "}";
    }

    private static String apply(long window, long hostel, boolean specialNeeds) {
        return "{\"windowId\":" + window + ",\"preference1Id\":" + hostel + ",\"specialNeeds\":" + specialNeeds
                + (specialNeeds ? ",\"specialNeedsNote\":\"Uses a wheelchair\"" : "") + "}";
    }

    private static Map<String, JsonNode> byRegNo(JsonNode arr) {
        Map<String, JsonNode> m = new HashMap<>();
        arr.forEach(x -> m.put(x.get("registrationNo").asText(), x));
        return m;
    }
}
