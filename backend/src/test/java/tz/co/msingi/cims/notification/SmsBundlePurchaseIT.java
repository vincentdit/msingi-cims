package tz.co.msingi.cims.notification;

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
import tz.co.msingi.cims.console.billing.MsingiPhonePaymentJob;
import tz.co.msingi.cims.finance.web.GepgCallbackController;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An institution buys an SMS bundle itself: the invoice is issued at once with Msingi's payment reference, it is paid by Msingi's
 * collector (bank or mobile money quoting the reference) or by a prompt on a phone through Msingi's aggregator, and the SMS are added
 * to the institution's bundle the moment it is paid, once only, with nobody at Msingi acting.
 */
@IntegrationTest
class SmsBundlePurchaseIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired PlatformUserRepository platformUsers;
    @Autowired PasswordEncoder encoder;
    @Autowired MsingiPhonePaymentJob phoneJob;

    AdmissionScenario s;
    String tenant, admin, bursar, platform, suffix;
    long tenantId;

    @BeforeEach
    void setUp() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 6);
        tenant = "S" + suffix.toUpperCase();
        TestData.Institution inst = data.institution(tenant, BigDecimal.ZERO);
        tenantId = inst.tenant().getId();
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        bursar = s.staffToken("bursar");
        tx.executeWithoutResult(x -> {
            PlatformUser u = new PlatformUser();
            u.setUsername("sms" + suffix);
            u.setFullName("Finance " + suffix);
            u.setEmail("sms" + suffix + "@msingi.co.tz");
            u.setPasswordHash(encoder.encode("Fin@2026x"));
            u.setMustChangePassword(false);
            platformUsers.save(u);
        });
        platform = s.body(mvc.perform(post("/api/platform/auth/login").contentType("application/json")
                .content("{\"username\":\"sms" + suffix + "\",\"password\":\"Fin@2026x\"}"))).get("accessToken").asText();
    }

    ResultActions p(MockHttpServletRequestBuilder req, String body) throws Exception {
        req.header("Authorization", "Bearer " + platform);
        if (body != null) req.contentType("application/json").content(body);
        return mvc.perform(req);
    }

    long balance() throws Exception {
        return s.body(s.call(get("/api/messaging/overview"), admin, null)).get("balance").asLong();
    }

    @Test
    void boughtInvoicedPaidAndDeliveredAutomatically() throws Exception {
        p(put("/api/platform/billing/sms-pricing"), "{\"pricePerSms\":20,\"minUnits\":500,\"vatPercent\":18}").andExpect(status().isOk())
                .andExpect(jsonPath("$.pricePerSms").value(20));
        p(put("/api/platform/billing/settings"), """
                {"companyName":"Msingi Technologies Ltd","email":"billing@msingi.co.tz",
                 "channels":[{"provider":"CRDB","accountName":"Msingi Technologies Ltd","accountNumber":"0150123456700","branch":"Dodoma"}]}""")
                .andExpect(status().isOk());

        JsonNode offer = s.body(s.call(get("/api/msingi-billing/sms-offer"), bursar, null).andExpect(status().isOk()));
        assertThat(offer.get("pricePerSms").decimalValue()).isEqualByComparingTo("20");
        assertThat(offer.get("phonePrompt").asBoolean()).isFalse();
        assertThat(offer.get("payTo").size()).isEqualTo(1);

        s.call(post("/api/msingi-billing/sms-bundles"), bursar, "{\"units\":100}").andExpect(status().isUnprocessableEntity());
        long before = balance();

        // 1,000 SMS: TZS 20,000 + 18% VAT, issued at once with a payment reference, due in a week
        JsonNode inv = s.body(s.call(post("/api/msingi-billing/sms-bundles"), bursar, "{\"units\":1000}").andExpect(status().isOk())).get("invoice");
        assertThat(inv.get("status").asText()).isEqualTo("ISSUED");
        assertThat(inv.get("kind").asText()).isEqualTo("SMS");
        assertThat(inv.get("total").decimalValue()).isEqualByComparingTo("23600");
        String ref = inv.get("paymentReference").asText();
        assertThat(ref).hasSize(12).startsWith("7");
        assertThat(balance()).isEqualTo(before);
        assertThat(s.body(s.call(get("/api/msingi-billing/sms-offer"), admin, null)).get("waiting").size()).isEqualTo(1);

        // the bank reports the transfer quoting the reference (through Msingi's collector): the bundle arrives, once
        String note = "{\"reference\":\"" + ref + "\",\"transactionId\":\"FT" + suffix + "\",\"provider\":\"CRDB\",\"amount\":23600}";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/integrations/msingi-billing/payment-notification").contentType("application/json").content(note)
                    .header("X-Billing-Signature", GepgCallbackController.sign(note, "test-billing-secret"))).andExpect(status().isOk());
        }
        assertThat(balance()).isEqualTo(before + 1000);
        JsonNode overview = s.body(s.call(get("/api/messaging/overview"), admin, null));
        assertThat(overview.get("pricePerSms").decimalValue()).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("select fulfilled_at is not null from billing_invoices where payment_reference = ?", Boolean.class, ref)).isTrue();

        // Msingi's aggregator (rehearsal) is switched on: the bursar pays the next bundle by a prompt on a phone
        JsonNode col = s.body(p(put("/api/platform/billing/collection"), "{\"provider\":\"SIMULATED\",\"environment\":\"SANDBOX\",\"active\":true}")
                .andExpect(status().isOk()));
        String notifyUrl = col.get("notifyUrl").asText();
        assertThat(notifyUrl).contains("/api/integrations/msingi-collections/simulated/");
        assertThat(s.body(s.call(get("/api/msingi-billing/sms-offer"), bursar, null)).get("phonePrompt").asBoolean()).isTrue();

        long inv2 = s.body(s.call(post("/api/msingi-billing/sms-bundles"), bursar, "{\"units\":500}").andExpect(status().isOk())).at("/invoice/id").asLong();
        JsonNode pay = s.body(s.call(post("/api/msingi-billing/invoices/" + inv2 + "/phone-payment"), bursar, "{\"phone\":\"0712 345 678\"}")
                .andExpect(status().isOk()));
        assertThat(pay.get("status").asText()).isEqualTo("PENDING");
        assertThat(pay.get("amount").decimalValue()).isEqualByComparingTo("11800");
        String orderId = jdbc.queryForObject("select order_id from msingi_phone_payments where id = ?", String.class, pay.get("id").asLong());

        // a notification at the wrong address is refused; at Msingi's address it pays the invoice and delivers the bundle (once)
        String paid = "{\"orderId\":\"" + orderId + "\",\"status\":\"PAID\",\"transactionId\":\"MP" + suffix + "\",\"amount\":11800}";
        mvc.perform(post("/api/integrations/msingi-collections/simulated/wrong").contentType("application/json").content(paid)).andExpect(status().isNotFound());
        String path = notifyUrl.substring(notifyUrl.indexOf("/api/"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(path).contentType("application/json").content(paid)).andExpect(status().isOk());
        }
        JsonNode after = s.body(s.call(get("/api/msingi-billing/phone-payments/" + pay.get("id").asLong()), bursar, null));
        assertThat(after.get("status").asText()).isEqualTo("PAID");
        assertThat(after.get("invoiceStatus").asText()).isEqualTo("PAID");
        assertThat(balance()).isEqualTo(before + 1500);

        // a prompt nobody confirms by notification is settled by the sweep (the rehearsal pays after a few seconds)
        long inv3 = s.body(s.call(post("/api/msingi-billing/sms-bundles"), admin, "{\"units\":500}").andExpect(status().isOk())).at("/invoice/id").asLong();
        long pay3 = s.body(s.call(post("/api/msingi-billing/invoices/" + inv3 + "/phone-payment"), admin, "{\"phone\":\"0754111222\"}")).get("id").asLong();
        jdbc.update("update msingi_phone_payments set created_at = now() - interval '1 minute' where id = ?", pay3);
        Thread.sleep(4500);
        phoneJob.runNow();
        assertThat(s.body(s.call(get("/api/msingi-billing/phone-payments/" + pay3), admin, null)).get("status").asText()).isEqualTo("PAID");
        assertThat(balance()).isEqualTo(before + 2000);

        // each paid invoice is one entry in the bundle's history, under the invoice number
        JsonNode topups = s.body(s.call(get("/api/messaging/overview"), admin, null)).get("topups");
        assertThat(topups.size()).isEqualTo(3);
        assertThat(topups.get(0).get("reference").asText()).startsWith("MSG-INV-");
    }
}
