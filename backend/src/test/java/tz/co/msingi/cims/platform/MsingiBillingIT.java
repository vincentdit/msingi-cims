package tz.co.msingi.cims.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.console.auth.PlatformUser;
import tz.co.msingi.cims.console.auth.PlatformUserRepository;
import tz.co.msingi.cims.finance.web.GepgCallbackController;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Msingi billing: the contract, invoices prepared by the job (licence in advance, usage in arrears from the institution's own data),
 * drafts hidden from the institution, issuing with a payment reference and the PDF by email, payments recorded by Msingi and reported
 * by the collector (signed, once only), credit carried to the next invoice, reversal, the statement, reminders and suspension.
 */
@IntegrationTest
class MsingiBillingIT {

    static final ZoneId TZ = ZoneId.of("Africa/Dar_es_Salaam");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired PlatformUserRepository platformUsers;
    @Autowired PasswordEncoder encoder;

    TestData.Institution inst;
    AdmissionScenario s;
    String tenant, admin, platform, suffix;
    long tenantId;
    LocalDate today = LocalDate.now(TZ);

    @BeforeEach
    void setUp() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 6);
        tenant = "B" + suffix.toUpperCase();
        inst = data.institution(tenant, BigDecimal.ZERO);
        tenantId = inst.tenant().getId();
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        tx.executeWithoutResult(x -> {
            PlatformUser u = new PlatformUser();
            u.setUsername("fin" + suffix);
            u.setFullName("Finance " + suffix);
            u.setEmail("fin" + suffix + "@msingi.co.tz");
            u.setPasswordHash(encoder.encode("Fin@2026x"));
            u.setMustChangePassword(false);
            platformUsers.save(u);
        });
        platform = s.body(mvc.perform(post("/api/platform/auth/login").contentType("application/json")
                .content("{\"username\":\"fin" + suffix + "\",\"password\":\"Fin@2026x\"}"))).get("accessToken").asText();
    }

    <T> T db(Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    ResultActions p(MockHttpServletRequestBuilder req, String body) throws Exception {
        req.header("Authorization", "Bearer " + platform);
        if (body != null) req.contentType("application/json").content(body);
        return mvc.perform(req);
    }

    JsonNode body(ResultActions r) throws Exception {
        return s.body(r);
    }

    /** Three active students and two application fees of TZS 10,000 paid ten days into the contract. */
    void usageData(LocalDate start) {
        db(() -> {
            Long year = jdbc.queryForObject("select id from academic_years limit 1", Long.class);
            Long intake = jdbc.queryForObject("select id from intakes limit 1", Long.class);
            for (int i = 1; i <= 3; i++) {
                jdbc.update("""
                        insert into students (tenant_id, registration_no, first_name, surname, sex, date_of_birth, nationality, phone, programme_id,
                          entry_academic_year_id, intake_id, status, admitted_at, admitted_by_name)
                        values (cims_current_tenant(), ?, 'Stu', 'Dent', 'F', date '2006-01-01', 'Tanzanian', '0712000000', ?, ?, ?, 'ACTIVE', now(), 'Test')""",
                        tenant + "/" + i, inst.programmeId(), year, intake);
            }
            Timestamp paid = Timestamp.from(start.plusDays(10).atTime(10, 0).atZone(TZ).toInstant());
            for (int i = 1; i <= 2; i++) {
                Long inv = jdbc.queryForObject("""
                        insert into invoices (tenant_id, invoice_no, payer_type, payer_id, payer_name, purpose, description, total_amount, paid_amount, status)
                        values (cims_current_tenant(), ?, 'APPLICANT', ?, 'Applicant', 'APPLICATION_FEE', 'Application fee', 10000, 10000, 'PAID') returning id""",
                        Long.class, "APP-" + suffix + "-" + i, i);
                jdbc.update("""
                        insert into payments (tenant_id, invoice_id, control_no, gepg_receipt_no, amount, paid_at, status)
                        values (cims_current_tenant(), ?, '991000000001', ?, 10000, ?, 'ALLOCATED')""", inv, "R" + suffix + i, paid);
            }
            return null;
        });
    }

    @Test
    void contractToInvoicesToPaymentsStatementRemindersAndSuspension() throws Exception {
        // Msingi's details and the accounts institutions pay into
        p(put("/api/platform/billing/settings"), """
                {"companyName":"Msingi Technologies Ltd","tin":"123-456-789","vrn":"40-012345-A","address":"Dodoma","phone":"0754 000 111",
                 "email":"billing@msingi.co.tz","channels":[{"provider":"CRDB","accountName":"Msingi Technologies Ltd","accountNumber":"0150123456700","branch":"Dodoma"},
                 {"provider":"MPESA","accountName":"MSINGI","accountNumber":"555111"}]}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.channels.length()").value(2));

        // the contract started 100 days ago: the first quarter has ended
        LocalDate start = today.minusDays(100);
        String contract = """
                {"reference":"MSG/CIMS/2026/007","startDate":"%s","setupFee":2000000,"annualFee":6000000,"studentFee":12000,"applicationFee":1000,
                 "applicationPercent":5,"usageCycle":"QUARTERLY","vatPercent":18,"paymentTermsDays":30,"billingContactName":"Bursar","billingEmail":"bursar@%s.ac.tz"}"""
                .formatted(start, suffix);
        p(put("/api/platform/billing/contracts/" + tenantId), contract).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        usageData(start);
        JsonNode usage = body(p(get("/api/platform/billing/usage/" + tenantId + "?from=" + start + "&to=" + start.plusMonths(3).minusDays(1)), null));
        assertThat(usage.get("activeStudents").asLong()).isEqualTo(3);
        assertThat(usage.get("paidApplications").asLong()).isEqualTo(2);
        assertThat(usage.get("applicationFeesCollected").decimalValue()).isEqualByComparingTo("20000");

        // the job prepares the licence year (with the set-up fee) and the first quarter's usage, once
        p(post("/api/platform/billing/run"), null).andExpect(jsonPath("$.prepared").value(2)).andExpect(jsonPath("$.issued").value(0));
        p(post("/api/platform/billing/run"), null).andExpect(jsonPath("$.prepared").value(0));
        JsonNode drafts = body(p(get("/api/platform/billing/invoices?status=draft&tenantId=" + tenantId), null)).get("rows");
        assertThat(drafts.size()).isEqualTo(2);
        long lic0 = 0, use0 = 0;
        for (JsonNode d : drafts) if (d.get("kind").asText().equals("LICENCE")) lic0 = d.get("id").asLong(); else use0 = d.get("id").asLong();
        final long licence = lic0, usageInv = use0;
        JsonNode lic = body(p(get("/api/platform/billing/invoices/" + licence), null));
        assertThat(lic.get("lines").findValuesAsText("kind")).containsExactly("SETUP", "LICENCE");
        assertThat(lic.at("/invoice/total").decimalValue()).isEqualByComparingTo("9440000");   // 8,000,000 + 18% VAT
        JsonNode use = body(p(get("/api/platform/billing/invoices/" + usageInv), null));
        assertThat(use.get("lines").findValuesAsText("kind")).containsExactly("STUDENTS", "APPLICATIONS", "APPLICATION_SHARE");
        assertThat(use.at("/lines/0/quantity").decimalValue()).isEqualByComparingTo("3");
        assertThat(use.at("/lines/0/unitPrice").decimalValue()).isEqualByComparingTo("3000");   // 12,000 a year × 3/12
        assertThat(use.at("/subtotal").decimalValue()).isEqualByComparingTo("12000");           // 9,000 + 2,000 + 5% of 20,000
        assertThat(use.at("/invoice/total").decimalValue()).isEqualByComparingTo("14160");

        // drafts are Msingi's: the institution sees none
        assertThat(s.body(s.call(get("/api/msingi-billing"), admin, null).andExpect(status().isOk())).get("invoices").size()).isZero();
        s.call(get("/api/msingi-billing/invoices/" + licence), admin, null).andExpect(status().isNotFound());
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        s.call(get("/api/msingi-billing"), s.staffToken("bursar"), null).andExpect(status().isForbidden());

        // a discount on the usage draft, then both are issued
        p(put("/api/platform/billing/invoices/" + usageInv), """
                {"lines":[{"kind":"STUDENTS","description":"Active students","quantity":3,"unitPrice":3000},
                          {"kind":"APPLICATIONS","description":"Applications","quantity":2,"unitPrice":1000},
                          {"kind":"APPLICATION_SHARE","description":"5% of application fees","quantity":1,"unitPrice":1000},
                          {"kind":"DISCOUNT","description":"Pilot discount","quantity":1,"unitPrice":2000}],"notes":"Pilot institution"}""")
                .andExpect(jsonPath("$.subtotal").value(10000.0)).andExpect(jsonPath("$.invoice.total").value(11800.0));
        JsonNode issued = body(p(post("/api/platform/billing/invoices/" + licence + "/issue"), null).andExpect(status().isOk()));
        assertThat(issued.at("/invoice/invoiceNo").asText()).startsWith("MSG-INV-" + today.getYear() + "-");
        String ref = issued.at("/invoice/paymentReference").asText();
        assertThat(ref).matches("7\\d{11}");
        assertThat(issued.at("/invoice/dueDate").asText()).isEqualTo(today.plusDays(30).toString());
        String usageRef = body(p(post("/api/platform/billing/invoices/" + usageInv + "/issue"), null)).at("/invoice/paymentReference").asText();
        p(post("/api/platform/billing/invoices/" + usageInv + "/issue"), null).andExpect(jsonPath("$.code").value("NOT_DRAFT"));
        assertThat(db(() -> jdbc.queryForList("select attachment_name from email_messages where to_address = ? and subject like 'Invoice MSG-INV-%'",
                String.class, "bursar@" + suffix + ".ac.tz"))).hasSize(2).allMatch(n -> n.endsWith(".pdf"));

        // a bank transfer recorded by Msingi against the licence
        JsonNode bank = body(p(post("/api/platform/billing/payments"), """
                {"tenantId":%d,"amount":5000000,"paidOn":"%s","method":"BANK","provider":"CRDB","reference":"FT2610ABC","invoiceId":%d}"""
                .formatted(tenantId, today, licence)).andExpect(status().isOk()));
        assertThat(bank.get("receiptNo").asText()).startsWith("MSG-RCT-");
        assertThat(body(p(get("/api/platform/billing/invoices/" + licence), null)).at("/invoice/status").asText()).isEqualTo("PARTLY_PAID");

        // the collector reports a mobile money payment quoting the usage invoice; more than due goes to the licence; a repeat changes nothing
        String note = "{\"reference\":\"" + usageRef + "\",\"transactionId\":\"QX81\",\"provider\":\"MPESA\",\"amount\":20000}";
        mvc.perform(post("/api/integrations/msingi-billing/payment-notification").contentType("application/json").content(note)
                .header("X-Billing-Signature", "bad")).andExpect(status().isUnauthorized());
        String sig = GepgCallbackController.sign(note, "test-billing-secret");
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/integrations/msingi-billing/payment-notification").contentType("application/json").content(note)
                    .header("X-Billing-Signature", sig)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        }
        String unknown = "{\"reference\":\"700000000000\",\"transactionId\":\"QX82\",\"provider\":\"MPESA\",\"amount\":1}";
        mvc.perform(post("/api/integrations/msingi-billing/payment-notification").contentType("application/json").content(unknown)
                .header("X-Billing-Signature", GepgCallbackController.sign(unknown, "test-billing-secret"))).andExpect(status().isBadRequest());
        assertThat(body(p(get("/api/platform/billing/invoices/" + usageInv), null)).at("/invoice/status").asText()).isEqualTo("PAID");
        assertThat(body(p(get("/api/platform/billing/invoices/" + licence), null)).at("/invoice/paidAmount").decimalValue()).isEqualByComparingTo("5008200");
        assertThat(body(p(get("/api/platform/billing/payments?tenantId=" + tenantId), null)).size()).isEqualTo(2);

        // the institution's administrators see their account, invoices and receipts, and download them
        JsonNode account = s.body(s.call(get("/api/msingi-billing"), admin, null));
        assertThat(account.get("balance").decimalValue()).isEqualByComparingTo("4431800");
        assertThat(account.get("invoices").size()).isEqualTo(2);
        assertThat(account.at("/payTo/channels/0/accountNumber").asText()).isEqualTo("0150123456700");
        assertThat(account.at("/contract/notes").isNull() || account.at("/contract/notes").isMissingNode()).isTrue();
        byte[] pdf = s.call(get("/api/msingi-billing/invoices/" + licence + "/pdf"), admin, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        s.call(get("/api/msingi-billing/payments/" + bank.get("id").asLong() + "/receipt"), admin, null).andExpect(status().isOk());
        s.call(get("/api/msingi-billing/statement/pdf"), admin, null).andExpect(status().isOk());
        s.call(get("/api/platform/billing/overview"), admin, null).andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(401, 403));
        // another institution sees none of it
        TestData.Institution other = data.institution("C" + suffix.toUpperCase(), BigDecimal.ZERO);
        data.staff(other.tenant(), "admin", Role.INSTITUTION_ADMIN);
        AdmissionScenario os = new AdmissionScenario(mvc, json, "C" + suffix.toUpperCase(), other);
        os.call(get("/api/msingi-billing/invoices/" + licence), os.staffToken("admin"), null).andExpect(status().isNotFound());
        os.call(get("/api/msingi-billing/payments/" + bank.get("id").asLong() + "/receipt"), os.staffToken("admin"), null).andExpect(status().isNotFound());

        // a payment recorded by mistake is reversed: the licence goes back to what the collector paid
        p(post("/api/platform/billing/payments/" + bank.get("id").asLong() + "/reverse"), "{\"reason\":\"Cheque bounced\"}")
                .andExpect(jsonPath("$.status").value("REVERSED"));
        assertThat(body(p(get("/api/platform/billing/invoices/" + licence), null)).at("/invoice/paidAmount").decimalValue()).isEqualByComparingTo("8200");
        JsonNode st = body(p(get("/api/platform/billing/statements/" + tenantId), null));
        assertThat(st.get("balance").decimalValue()).isEqualByComparingTo("9431800");
        assertThat(st.get("entries").findValuesAsText("kind")).contains("INVOICE", "PAYMENT", "REVERSAL");
        assertThat(st.at("/aging/current").decimalValue()).isEqualByComparingTo("9431800");

        // overdue: one reminder for the highest level reached, never twice
        db(() -> jdbc.update("update billing_invoices set due_date = ? where id = ?", Date.valueOf(today.minusDays(15)), licence));
        p(post("/api/platform/billing/run"), null).andExpect(jsonPath("$.reminders").value(1));
        p(post("/api/platform/billing/run"), null).andExpect(jsonPath("$.reminders").value(0));
        JsonNode reminded = body(p(get("/api/platform/billing/invoices/" + licence), null));
        assertThat(reminded.at("/reminders/0/kind").asText()).isEqualTo("OVERDUE");
        assertThat(reminded.at("/reminders/0/level").asInt()).isEqualTo(3);
        assertThat(body(p(get("/api/platform/billing/overview"), null)).at("/aging/days1to30").decimalValue()).isGreaterThanOrEqualTo(new BigDecimal("9431800"));

        // cancelling needs a reason; what was paid goes back to credit
        p(post("/api/platform/billing/invoices/" + licence + "/cancel"), "{\"reason\":\"\"}").andExpect(jsonPath("$.code").value("REASON_REQUIRED"));

        // long overdue with a contract that allows it: the institution is suspended and Msingi is told
        p(put("/api/platform/billing/contracts/" + tenantId), contract.replace("\"paymentTermsDays\":30", "\"paymentTermsDays\":30,\"autoSuspend\":true,\"suspendAfterDays\":60")).andExpect(status().isOk());
        db(() -> jdbc.update("update billing_invoices set due_date = ? where id = ?", Date.valueOf(today.minusDays(61)), licence));
        p(post("/api/platform/billing/run"), null).andExpect(jsonPath("$.suspended").value(1));
        assertThat(jdbc.queryForObject("select status from tenants where id = ?", String.class, tenantId)).isEqualTo("SUSPENDED");
        assertThat(db(() -> jdbc.queryForList("select subject from email_messages where to_address = ?", String.class, "fin" + suffix + "@msingi.co.tz")))
                .anyMatch(x -> x.startsWith("Suspended for non-payment"));
    }

    @Test
    void contractRulesAndInvoicesByHand() throws Exception {
        p(put("/api/platform/billing/contracts/" + tenantId), "{\"startDate\":null}").andExpect(jsonPath("$.code").value("START_REQUIRED"));
        p(put("/api/platform/billing/contracts/" + tenantId), "{\"startDate\":\"" + today + "\",\"applicationPercent\":120}").andExpect(jsonPath("$.code").value("INVALID_PERCENT"));
        p(put("/api/platform/billing/contracts/" + tenantId), "{\"startDate\":\"" + today + "\",\"annualFee\":1200000,\"usageCycle\":\"MONTHLY\",\"autoIssue\":true}")
                .andExpect(status().isOk());
        // auto-issue: the licence is issued straight away
        p(post("/api/platform/billing/run"), null).andExpect(jsonPath("$.issued").value(1));
        // once invoiced, the start date is fixed
        p(put("/api/platform/billing/contracts/" + tenantId), "{\"startDate\":\"" + today.minusDays(5) + "\",\"annualFee\":1200000,\"usageCycle\":\"MONTHLY\"}")
                .andExpect(jsonPath("$.code").value("START_LOCKED"));
        // an invoice for other work, typed by hand; empty ones cannot be issued; a discarded draft needs no reason
        long other = body(p(post("/api/platform/billing/invoices"), "{\"tenantId\":" + tenantId + ",\"kind\":\"OTHER\"}")).at("/invoice/id").asLong();
        p(post("/api/platform/billing/invoices/" + other + "/issue"), null).andExpect(jsonPath("$.code").value("EMPTY_INVOICE"));
        p(put("/api/platform/billing/invoices/" + other), "{\"lines\":[{\"kind\":\"OTHER\",\"description\":\"Extra training day\",\"quantity\":2,\"unitPrice\":-5}]}")
                .andExpect(jsonPath("$.code").value("INVALID_PRICE"));
        p(put("/api/platform/billing/invoices/" + other), "{\"lines\":[{\"kind\":\"OTHER\",\"description\":\"Extra training day\",\"quantity\":2,\"unitPrice\":350000}],\"vatPercent\":0}")
                .andExpect(jsonPath("$.invoice.total").value(700000.0));
        // testing period: the administrator pays the licence as if by mobile money
        long lic = body(p(get("/api/platform/billing/invoices?status=open&tenantId=" + tenantId), null)).at("/rows/0/id").asLong();
        s.call(post("/api/msingi-billing/invoices/" + lic + "/simulate-payment"), admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.source").value("ONLINE"));
        assertThat(body(p(get("/api/platform/billing/invoices/" + lic), null)).at("/invoice/status").asText()).isEqualTo("PAID");
        p(post("/api/platform/billing/invoices/" + other + "/cancel"), "{}").andExpect(jsonPath("$.invoice.status").value("CANCELLED"));
        p(post("/api/platform/billing/contracts/" + tenantId + "/end"), "{\"endDate\":\"" + today + "\"}").andExpect(jsonPath("$.status").value("ENDED"));
        assertThat(body(p(get("/api/platform/billing/overview"), null)).get("institutions").findValuesAsText("code")).contains(tenant);
    }

    @Test
    void msingiLogoOnDocumentsBuiltInOrUploaded() throws Exception {
        // the built-in Msingi logo is there from the start
        assertThat(body(p(get("/api/platform/billing/settings"), null)).get("customLogo").asBoolean()).isFalse();
        byte[] builtIn = p(get("/api/platform/billing/settings/logo"), null).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png")).andReturn().getResponse().getContentAsByteArray();
        assertThat(builtIn.length).isGreaterThan(1000);
        try {
            // too small, or not an image, is refused; a proper PNG replaces it
            p(logo(InstitutionPhotoIT.png(60, 40)), null).andExpect(jsonPath("$.code").value("LOGO_TOO_SMALL"));
            p(logo("not an image".getBytes()), null).andExpect(jsonPath("$.code").value("LOGO_TYPE"));
            byte[] mine = InstitutionPhotoIT.png(400, 200);
            p(logo(mine), null).andExpect(status().isOk());
            assertThat(body(p(get("/api/platform/billing/settings"), null)).get("customLogo").asBoolean()).isTrue();
            p(get("/api/platform/billing/settings/logo"), null).andExpect(content().bytes(mine));
            // institutions cannot change it
            s.call(logo(mine), admin, null).andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(401, 403));
            // documents still render with it
            p(put("/api/platform/billing/contracts/" + tenantId), "{\"startDate\":\"" + today + "\",\"annualFee\":1200000,\"usageCycle\":\"MONTHLY\",\"autoIssue\":true}")
                    .andExpect(status().isOk());
            p(post("/api/platform/billing/run"), null).andExpect(status().isOk());
            long lic = body(p(get("/api/platform/billing/invoices?status=open&tenantId=" + tenantId), null)).at("/rows/0/id").asLong();
            byte[] pdf = s.call(get("/api/msingi-billing/invoices/" + lic + "/pdf"), admin, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        } finally {
            p(delete("/api/platform/billing/settings/logo"), null).andExpect(status().is2xxSuccessful());
        }
        assertThat(body(p(get("/api/platform/billing/settings"), null)).get("customLogo").asBoolean()).isFalse();
    }

    static org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder logo(byte[] bytes) {
        var b = multipart("/api/platform/billing/settings/logo");
        b.file(new org.springframework.mock.web.MockMultipartFile("file", "logo.png", "image/png", bytes));
        b.with(r -> { r.setMethod("PUT"); return r; });
        return b;
    }
}

