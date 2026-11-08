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
import java.nio.charset.StandardCharsets;
import org.springframework.mock.web.MockMultipartFile;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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

    @Test
    void msingiChoosesHowEachKindOfInvoiceIsPaid() throws Exception {
        p(put("/api/platform/billing/settings"), """
                {"companyName":"Msingi Technologies Ltd","email":"billing@msingi.co.tz",
                 "channels":[{"provider":"CRDB","accountName":"Msingi Technologies Ltd","accountNumber":"0150123456700","branch":"Dodoma"}]}""")
                .andExpect(status().isOk());
        // mobile money only is refused while Msingi lists no mobile money number
        p(put("/api/platform/billing/pay-methods"), "{\"SMS\":\"MOBILE\"}").andExpect(jsonPath("$.code").value("NO_MOBILE_ACCOUNT"));
        p(put("/api/platform/billing/settings"), """
                {"companyName":"Msingi Technologies Ltd","email":"billing@msingi.co.tz",
                 "channels":[{"provider":"CRDB","accountName":"Msingi Technologies Ltd","accountNumber":"0150123456700","branch":"Dodoma"},
                 {"provider":"MPESA","accountName":"MSINGI","accountNumber":"555111"}]}""").andExpect(status().isOk());
        p(put("/api/platform/billing/pay-methods"), "{\"SMS\":\"BANK\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.SMS").value("BANK")).andExpect(jsonPath("$.LICENCE").value("BOTH"));
        p(put("/api/platform/billing/collection"), "{\"provider\":\"SIMULATED\",\"environment\":\"SANDBOX\",\"active\":true}").andExpect(status().isOk());
        try {
            // SMS bundles by bank only: no phone prompt, only the bank account shown, a prompt refused
            JsonNode offer = s.body(s.call(get("/api/msingi-billing/sms-offer"), bursar, null));
            assertThat(offer.get("phonePrompt").asBoolean()).isFalse();
            assertThat(offer.get("payTo").findValuesAsText("provider")).containsExactly("CRDB");
            JsonNode v = s.body(s.call(post("/api/msingi-billing/sms-bundles"), bursar, "{\"units\":500}").andExpect(status().isOk()));
            assertThat(v.get("payMethods").asText()).isEqualTo("BANK");
            assertThat(v.get("payTo").findValuesAsText("provider")).containsExactly("CRDB");
            s.call(post("/api/msingi-billing/invoices/" + v.at("/invoice/id").asLong() + "/phone-payment"), bursar, "{\"phone\":\"0712345678\"}")
                    .andExpect(jsonPath("$.code").value("MOBILE_NOT_ALLOWED"));
            s.call(get("/api/msingi-billing/pay-options"), bursar, null).andExpect(jsonPath("$.phonePrompt").value(true));
            // back to either: both accounts and the prompt
            p(put("/api/platform/billing/pay-methods"), "{\"SMS\":\"BOTH\"}").andExpect(status().isOk());
            assertThat(s.body(s.call(get("/api/msingi-billing/sms-offer"), bursar, null)).get("payTo").size()).isEqualTo(2);
        } finally {
            p(put("/api/platform/billing/pay-methods"), "{\"SMS\":\"BOTH\"}");
            p(put("/api/platform/billing/collection"), "{\"provider\":\"SIMULATED\",\"environment\":\"SANDBOX\",\"active\":false}");
        }
    }

    @Test
    void billPayAndBankStatementsRecordPaymentsWithoutAnyone() throws Exception {
        p(put("/api/platform/billing/sms-pricing"), "{\"pricePerSms\":20,\"minUnits\":500,\"vatPercent\":18}").andExpect(status().isOk());
        p(put("/api/platform/billing/settings"), """
                {"companyName":"Msingi Technologies Ltd","email":"billing@msingi.co.tz",
                 "channels":[{"provider":"CRDB","accountName":"Msingi Technologies Ltd","accountNumber":"0150123456700","branch":"Dodoma"}]}""")
                .andExpect(status().isOk());
        // a BillPay-Namba only with ClickPesa (or the rehearsal), 4 digits
        p(put("/api/platform/billing/collection"), "{\"provider\":\"SIMULATED\",\"environment\":\"SANDBOX\",\"active\":true,\"billPayNamba\":\"11a2\"}")
                .andExpect(jsonPath("$.code").value("BILLPAY_NAMBA"));
        JsonNode col = s.body(p(put("/api/platform/billing/collection"),
                "{\"provider\":\"SIMULATED\",\"environment\":\"SANDBOX\",\"active\":true,\"billPayNamba\":\"1122\"}").andExpect(status().isOk()));
        String path = col.get("notifyUrl").asText().substring(col.get("notifyUrl").asText().indexOf("/api/"));
        try {
            long before = balance();
            JsonNode v = s.body(s.call(post("/api/msingi-billing/sms-bundles"), bursar, "{\"units\":500}").andExpect(status().isOk()));
            String ref = v.at("/invoice/paymentReference").asText();
            assertThat(v.at("/payTo/0/provider").asText()).isEqualTo("BILLPAY");
            assertThat(v.at("/payTo/0/accountNumber").asText()).isEqualTo("1122" + ref);
            // paid from a phone or CRDB with the BillPay control number: ClickPesa reports it, CIMS records it once
            String note = "{\"orderId\":\"1122" + ref + "\",\"status\":\"PAID\",\"transactionId\":\"BP" + suffix + "\",\"amount\":11800}";
            for (int i = 0; i < 2; i++) mvc.perform(post(path).contentType("application/json").content(note)).andExpect(status().isOk());
            assertThat(balance()).isEqualTo(before + 500);
            assertThat(jdbc.queryForObject("select status from billing_invoices where payment_reference = ?", String.class, ref)).isEqualTo("PAID");

            // a transfer straight to Msingi's bank account, quoting the reference in the narration: the bank statement import records it
            String ref2 = s.body(s.call(post("/api/msingi-billing/sms-bundles"), bursar, "{\"units\":500}")).at("/invoice/paymentReference").asText();
            String grouped = ref2.replaceAll("(\\d{4})(?=\\d)", "$1 ");
            String csv = "Value Date,Narration,Debit,Credit,Bank Reference\n"
                    + "08/11/2026,TRANSFER FROM BAHARI REF " + grouped + ",,11800,FT26312A" + suffix + "\n"
                    + "08/11/2026,BANK CHARGES,2500,,CHG1\n"
                    + "08/11/2026,DEPOSIT JOHN,,50000,FT26312B" + suffix + "\n";
            MockMultipartFile file = new MockMultipartFile("file", "crdb.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
            JsonNode imp = s.body(mvc.perform(multipart("/api/platform/billing/statements").file(file).param("provider", "CRDB")
                    .header("Authorization", "Bearer " + platform)).andExpect(status().isOk()));
            assertThat(imp.get("recorded").asInt()).isEqualTo(1);
            assertThat(imp.get("unmatched").asInt()).isEqualTo(1);
            assertThat(imp.at("/lines/0/reference").asText()).isEqualTo(ref2);
            assertThat(balance()).isEqualTo(before + 1000);
            // the same statement again changes nothing
            JsonNode again = s.body(mvc.perform(multipart("/api/platform/billing/statements").file(file).param("provider", "CRDB")
                    .header("Authorization", "Bearer " + platform)).andExpect(status().isOk()));
            assertThat(again.get("already").asInt()).isEqualTo(1);
            assertThat(again.get("recorded").asInt()).isZero();
            assertThat(balance()).isEqualTo(before + 1000);
        } finally {
            p(put("/api/platform/billing/collection"), "{\"provider\":\"SIMULATED\",\"environment\":\"SANDBOX\",\"active\":false}");
        }
    }

    @Test
    void servicesAreRequestedInvoicedAndDeliveredOnPayment() throws Exception {
        p(put("/api/platform/billing/settings"), """
                {"companyName":"Msingi Technologies Ltd","email":"billing@msingi.co.tz",
                 "channels":[{"provider":"CRDB","accountName":"Msingi Technologies Ltd","accountNumber":"0150123456700","branch":"Dodoma"},
                 {"provider":"MPESA","accountName":"MSINGI","accountNumber":"555111"}]}""").andExpect(status().isOk());
        String code = "PACK" + suffix.toUpperCase();
        p(post("/api/platform/billing/services"), "{\"code\":\"" + code + "\",\"name\":\"SMS pack 10,000\",\"unitLabel\":\"pack\",\"unitPrice\":180000,"
                + "\"payMethods\":\"BANK\",\"delivery\":\"SMS_CREDIT\"}").andExpect(jsonPath("$.code").value("SERVICE_SMS"));
        long pack = s.body(p(post("/api/platform/billing/services"), "{\"code\":\"" + code + "\",\"name\":\"SMS pack 10,000\",\"unitLabel\":\"pack\","
                + "\"unitPrice\":180000,\"minQuantity\":1,\"maxQuantity\":3,\"vatPercent\":18,\"payMethods\":\"BANK\",\"delivery\":\"SMS_CREDIT\",\"smsPerUnit\":10000}")
                .andExpect(status().isOk())).get("id").asLong();
        long training = s.body(p(post("/api/platform/billing/services"), "{\"code\":\"TRN" + suffix.toUpperCase() + "\",\"name\":\"Training day at the college\","
                + "\"unitLabel\":\"day\",\"unitPrice\":500000,\"minQuantity\":1,\"maxQuantity\":5,\"delivery\":\"MSINGI_DELIVERS\"}")
                .andExpect(status().isOk())).get("id").asLong();

        // the college sees the services and requests an SMS pack: invoice at once, payable by bank only
        assertThat(s.body(s.call(get("/api/msingi-billing/services"), bursar, null)).findValuesAsText("id")).contains(String.valueOf(pack), String.valueOf(training));
        s.call(post("/api/msingi-billing/service-orders"), bursar, "{\"serviceId\":" + pack + ",\"quantity\":4}").andExpect(jsonPath("$.code").value("SERVICE_QUANTITY"));
        JsonNode inv = s.body(s.call(post("/api/msingi-billing/service-orders"), bursar, "{\"serviceId\":" + pack + ",\"quantity\":1}").andExpect(status().isOk()));
        assertThat(inv.at("/invoice/kind").asText()).isEqualTo("SERVICE");
        assertThat(inv.at("/invoice/total").decimalValue()).isEqualByComparingTo("212400");
        assertThat(inv.get("payMethods").asText()).isEqualTo("BANK");
        assertThat(inv.get("payTo").findValuesAsText("provider")).containsExactly("CRDB");
        String ref = inv.at("/invoice/paymentReference").asText();
        long before = balance();
        String note = "{\"reference\":\"" + ref + "\",\"transactionId\":\"SV" + suffix + "\",\"provider\":\"CRDB\",\"amount\":212400}";
        mvc.perform(post("/api/integrations/msingi-billing/payment-notification").contentType("application/json").content(note)
                .header("X-Billing-Signature", GepgCallbackController.sign(note, "test-billing-secret"))).andExpect(status().isOk());
        assertThat(balance()).isEqualTo(before + 10000);
        JsonNode orders = s.body(s.call(get("/api/msingi-billing/service-orders"), bursar, null));
        assertThat(orders.at("/0/status").asText()).isEqualTo("DELIVERED");

        // a training day: paid, handed to Msingi to deliver, then marked delivered in the console
        JsonNode t = s.body(s.call(post("/api/msingi-billing/service-orders"), admin, "{\"serviceId\":" + training + ",\"quantity\":2,\"note\":\"For the bursary staff\"}"));
        String tref = t.at("/invoice/paymentReference").asText();
        String tn = "{\"reference\":\"" + tref + "\",\"transactionId\":\"TR" + suffix + "\",\"provider\":\"MPESA\",\"amount\":" + t.at("/invoice/total").decimalValue().toPlainString() + "}";
        mvc.perform(post("/api/integrations/msingi-billing/payment-notification").contentType("application/json").content(tn)
                .header("X-Billing-Signature", GepgCallbackController.sign(tn, "test-billing-secret"))).andExpect(status().isOk());
        JsonNode toDeliver = s.body(p(get("/api/platform/billing/service-orders?status=PAID"), null));
        long orderId = -1;
        for (JsonNode o : toDeliver) if (o.get("paymentReference").asText().equals(tref)) orderId = o.get("id").asLong();
        assertThat(orderId).isPositive();
        p(post("/api/platform/billing/service-orders/" + orderId + "/delivered"), "{\"note\":\"Held on 20 November\"}").andExpect(jsonPath("$.status").value("DELIVERED"));
        p(post("/api/platform/billing/service-orders/" + orderId + "/delivered"), null).andExpect(jsonPath("$.code").value("NOT_PAID"));
    }

    @Test
    void selcomPayByReferenceIsLookedUpValidatedAndRecorded() throws Exception {
        p(put("/api/platform/billing/sms-pricing"), "{\"pricePerSms\":20,\"minUnits\":500,\"vatPercent\":18}").andExpect(status().isOk());
        String selcom = "{\"provider\":\"SELCOM\",\"environment\":\"SANDBOX\",\"active\":true,\"fields\":{\"vendor\":\"TILL60012\",\"apiKey\":\"K\",\"apiSecret\":\"S\"},"
                + "\"billPayNamba\":\"%s\"}";
        p(put("/api/platform/billing/collection"), selcom.formatted("12a")).andExpect(jsonPath("$.code").value("BILLPAY_NAMBA"));
        JsonNode col = s.body(p(put("/api/platform/billing/collection"), selcom.formatted("60012345")).andExpect(status().isOk()));
        assertThat(col.get("c2bUrl").asText()).endsWith("/api/integrations/msingi-collections/selcom-c2b");
        String bearer = "Bearer " + col.get("c2bToken").asText();
        try {
            long before = balance();
            JsonNode v = s.body(s.call(post("/api/msingi-billing/sms-bundles"), bursar, "{\"units\":500}").andExpect(status().isOk()));
            String ref = v.at("/invoice/paymentReference").asText();
            assertThat(v.at("/payTo/0/provider").asText()).isEqualTo("SELCOM_PAY");
            assertThat(v.at("/payTo/0/accountName").asText()).isEqualTo("60012345");
            assertThat(v.at("/payTo/0/accountNumber").asText()).isEqualTo(ref);
            String typed = ref.replaceAll("(\\d{4})(?=\\d)", "$1 ");   // the payer types it in groups
            String body = "{\"operator\":\"MPESA-TZ\",\"transid\":\"SC" + suffix + "\",\"reference\":\"R" + suffix + "\",\"utilityref\":\"" + typed
                    + "\",\"amount\":\"11800\",\"msisdn\":\"255712345678\"}";
            String base = "/api/integrations/msingi-collections/selcom-c2b/";
            mvc.perform(post(base + "lookup").contentType("application/json").content(body).header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
            mvc.perform(post(base + "lookup").contentType("application/json").content(body).header("Authorization", bearer))
                    .andExpect(jsonPath("$.resultcode").value("000")).andExpect(jsonPath("$.amount").value("11800"))
                    .andExpect(jsonPath("$.reference").value("R" + suffix));
            mvc.perform(post(base + "lookup").contentType("application/json").content(body.replace(typed, "700000000000")).header("Authorization", bearer))
                    .andExpect(jsonPath("$.resultcode").value("010"));
            // an SMS bundle is paid in full: less is refused before the payer's money moves
            mvc.perform(post(base + "validation").contentType("application/json").content(body.replace("\"11800\"", "\"5000\"")).header("Authorization", bearer))
                    .andExpect(jsonPath("$.resultcode").value("015"));
            mvc.perform(post(base + "validation").contentType("application/json").content(body).header("Authorization", bearer)).andExpect(jsonPath("$.resultcode").value("000"));
            for (int i = 0; i < 2; i++) {
                mvc.perform(post(base + "notification").contentType("application/json").content(body).header("Authorization", bearer)).andExpect(jsonPath("$.resultcode").value("000"));
            }
            assertThat(balance()).isEqualTo(before + 500);
            assertThat(jdbc.queryForObject("select method from billing_payments where transaction_key = ?", String.class, "SELCOM:SC" + suffix)).isEqualTo("MOBILE_MONEY");
            // paid now: a second lookup no longer offers it
            mvc.perform(post(base + "lookup").contentType("application/json").content(body).header("Authorization", bearer)).andExpect(jsonPath("$.resultcode").value("010"));
        } finally {
            p(put("/api/platform/billing/collection"), "{\"provider\":\"SIMULATED\",\"environment\":\"SANDBOX\",\"active\":false}");
        }
    }
}
