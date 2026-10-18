package tz.co.msingi.cims.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.finance.collection.PaymentReference;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.domain.Payment;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.finance.web.GepgCallbackController;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.Ownership;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Private and faith-based institutions do not use GePG: CIMS issues its own payment reference, the Bursar lists the mobile money and
 * bank accounts it is paid into, and payments arrive from the collector or from the day's statement.
 */
@IntegrationTest
class CollectionPaymentsIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired TransactionTemplate tx;
    @Autowired BillingService billing;
    @Autowired TenantRepository tenants;

    private TestData.Institution institution(String ownership) {
        TestData.Institution inst = data.institution("C" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), BigDecimal.TEN);
        tx.executeWithoutResult(s -> tenants.findById(inst.tenant().getId()).ifPresent(t -> t.setOwnership(ownership)));
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        return inst;
    }

    private ControlNumber bill(TestData.Institution inst, String amount) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(s -> {
            Invoice inv = billing.createInvoice(new BillingService.NewInvoice(Invoice.PayerType.APPLICANT, 1L, "Asha Juma",
                    "255700000002", null, "TEST", null, "Tuition 2026/2027",
                    List.of(new BillingService.NewInvoice.Line("TUITION", "Tuition", new BigDecimal(amount)))));
            return billing.ensureControlNumber(inv.getId());
        }));
    }

    @Test
    void nhifContributionsGoThroughGepgEvenInAPrivateInstitution() {
        TestData.Institution inst = institution(Ownership.PRIVATE);
        ControlNumber fees = bill(inst, "150000");
        assertThat(fees.getIssuer()).isEqualTo(ControlNumber.Issuer.COLLECTION);
        ControlNumber nhif = TenantContext.callAs(inst.info(), () -> tx.execute(s -> {
            Invoice inv = billing.createInvoice(new BillingService.NewInvoice(Invoice.PayerType.STUDENT, 1L, "Asha Juma",
                    "255700000002", null, "NHIF_CONTRIBUTION", null, "NHIF student health insurance, 2026/2027",
                    List.of(new BillingService.NewInvoice.Line("NHIF", "NHIF", new BigDecimal("50400")))));
            return billing.ensureControlNumber(inv.getId());
        }));
        assertThat(nhif.getIssuer()).isEqualTo(ControlNumber.Issuer.GEPG);
        assertThat(PaymentReference.isValid(nhif.getControlNo())).isFalse();
        assertThat(nhif.getControlNo()).isNotBlank();
    }

    @Test
    void privateInstitutionIsPaidIntoItsOwnAccounts() throws Exception {
        TestData.Institution inst = institution(Ownership.PRIVATE);
        AdmissionScenario s = new AdmissionScenario(mvc, json, inst.tenant().getCode(), inst);
        String bursar = s.staffToken("bursar"), accountant = s.staffToken("accountant");

        // The Bursar lists where fees are paid; the Accountant can see but not change them.
        s.call(post("/api/finance/payment-channels"), bursar, """
                {"provider":"MPESA","accountName":"Test College","accountNumber":"555 001"}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountNumber").value("555001")).andExpect(jsonPath("$.kind").value("MOBILE_MONEY"));
        JsonNode crdb = s.body(s.call(post("/api/finance/payment-channels"), bursar, """
                {"provider":"CRDB","accountName":"Test College","accountNumber":"0150123456700","branch":"Azikiwe"}""").andExpect(status().isOk()));
        s.call(post("/api/finance/payment-channels"), bursar, """
                {"provider":"MPESA","accountName":"Test College","accountNumber":"55-50-01"}""")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CHANNEL_EXISTS"));
        s.call(post("/api/finance/payment-channels"), bursar, """
                {"provider":"HALOPESA","accountName":"Test College","accountNumber":"12ab"}""")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_BUSINESS_NUMBER"));
        s.call(post("/api/finance/payment-channels"), accountant, """
                {"provider":"NMB","accountName":"Test College","accountNumber":"20110012345"}""").andExpect(status().isForbidden());
        s.call(put("/api/finance/payment-channels/" + crdb.get("id").asLong()), bursar, """
                {"provider":"CRDB","accountName":"Test College","accountNumber":"0150123456700","active":false}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        JsonNode setup = s.body(s.call(get("/api/finance/payment-channels"), accountant, null).andExpect(status().isOk()));
        assertThat(setup.get("mode").asText()).isEqualTo("COLLECTION");
        assertThat(setup.get("channels")).hasSize(2);
        assertThat(setup.get("providers").size()).isGreaterThan(15);

        // Payers see only the active accounts.
        JsonNode pub = s.body(s.call(get("/api/public/institution"), null, null).andExpect(status().isOk()));
        assertThat(pub.at("/payment/mode").asText()).isEqualTo("COLLECTION");
        assertThat(pub.at("/payment/channels")).hasSize(1);
        assertThat(pub.at("/payment/channels/0/label").asText()).isEqualTo("M-Pesa");
        assertThat(pub.at("/payment/channels/0/ussd").asText()).isEqualTo("*150*00#");

        // No GePG wording for a private institution: its SMS templates offer the payment reference, not the control number.
        data.staff(inst.tenant(), "admin", tz.co.msingi.cims.platform.security.Role.INSTITUTION_ADMIN);
        JsonNode templates = s.body(s.call(get("/api/messaging/templates"), s.staffToken("admin"), null).andExpect(status().isOk()));
        assertThat(templates.findValuesAsText("code")).contains("PAYMENT_REFERENCE").doesNotContain("CONTROL_NUMBER");
        assertThat(templates.toString()).doesNotContain("GePG");

        // The invoice gets a CIMS reference that names the institution, not a GePG control number.
        ControlNumber cn = bill(inst, "150000");
        assertThat(cn.getIssuer()).isEqualTo(ControlNumber.Issuer.COLLECTION);
        assertThat(PaymentReference.isValid(cn.getControlNo())).isTrue();
        assertThat(PaymentReference.tenantOf(cn.getControlNo())).isEqualTo(inst.tenant().getId());

        // The collector reports the payment with the reference only; a repeat is ignored; a bad signature is refused.
        String body = """
                {"reference":"%s","transactionId":"QK%s","provider":"MPESA","amount":150000,"payerPhone":"255754000111",
                 "paidAt":"2026-10-14T08:00:00+03:00"}""".formatted(cn.getControlNo(), UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        mvc.perform(post("/api/integrations/collections/payment-notification").contentType(MediaType.APPLICATION_JSON)
                .header("X-Collection-Signature", "bad").content(body)).andExpect(status().isUnauthorized());
        String sig = GepgCallbackController.sign(body, "test-collection-secret");
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/integrations/collections/payment-notification").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Collection-Signature", sig).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        }
        List<Payment> paid = TenantContext.callAs(inst.info(), () -> tx.execute(x -> billing.paymentsFor(cn.getInvoice().getId())));
        assertThat(paid).hasSize(1);
        assertThat(paid.get(0).getSource()).isEqualTo(Payment.Source.COLLECTION);
        assertThat(paid.get(0).getPspName()).isEqualTo("M-Pesa");
        Invoice.Status invoiceStatus = TenantContext.callAs(inst.info(), () -> tx.execute(x -> billing.get(cn.getInvoice().getId()).getStatus()));
        assertThat(invoiceStatus).isEqualTo(Invoice.Status.PAID);

        // A reference with a wrong check digit belongs to nobody.
        String wrong = cn.getControlNo().substring(0, 11) + ((cn.getControlNo().charAt(11) - '0' + 1) % 10);
        String bad = """
                {"reference":"%s","transactionId":"QX1","provider":"MPESA","amount":1000}""".formatted(wrong);
        mvc.perform(post("/api/integrations/collections/payment-notification").contentType(MediaType.APPLICATION_JSON)
                .header("X-Collection-Signature", GepgCallbackController.sign(bad, "test-collection-secret")).content(bad)).andExpect(status().isBadRequest());
    }

    @Test
    void faithBasedInstitutionConfirmsPaymentsFromTheBankStatement() throws Exception {
        TestData.Institution inst = institution(Ownership.FBO);
        AdmissionScenario s = new AdmissionScenario(mvc, json, inst.tenant().getCode(), inst);
        String accountant = s.staffToken("accountant");
        ControlNumber cn = bill(inst, "80000");
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));
        String csv = "TransactionId,Reference,Amount,TransactionDate,Bank,MSISDN\nCRDB778812," + cn.getControlNo() + ",80000," + today + "T09:15:00,CRDB Bank,\n";
        JsonNode rec = s.body(s.call(post("/api/finance/reconciliations"), accountant,
                json.writeValueAsString(java.util.Map.of("date", today.toString(), "fileName", "crdb-statement.csv", "csv", csv))).andExpect(status().isOk()));
        JsonNode item = rec.get("items").get(0);
        assertThat(item.get("kind").asText()).isEqualTo("MISSING_IN_CIMS");
        s.call(post("/api/finance/reconciliations/items/" + item.get("id").asLong() + "/post"), accountant, "{}").andExpect(status().isOk());
        List<Payment> paid = TenantContext.callAs(inst.info(), () -> tx.execute(x -> billing.paymentsFor(cn.getInvoice().getId())));
        assertThat(paid).hasSize(1);
        assertThat(paid.get(0).getSource()).isEqualTo(Payment.Source.COLLECTION);
    }

    @Test
    void publicInstitutionUsesGepgAndListsNoAccounts() throws Exception {
        TestData.Institution inst = institution(Ownership.PUBLIC);
        AdmissionScenario s = new AdmissionScenario(mvc, json, inst.tenant().getCode(), inst);
        s.call(post("/api/finance/payment-channels"), s.staffToken("bursar"), """
                {"provider":"NMB","accountName":"Test College","accountNumber":"20110012345"}""")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("USES_GEPG"));
        assertThat(s.body(s.call(get("/api/public/institution"), null, null)).at("/payment/mode").asText()).isEqualTo("GEPG");
        ControlNumber cn = bill(inst, "5000");
        assertThat(cn.getIssuer()).isEqualTo(ControlNumber.Issuer.GEPG);
        assertThat(cn.getControlNo()).startsWith("99");

        // If the institution turns out to be private, the GePG number is replaced by a CIMS reference on the next request.
        tx.executeWithoutResult(x -> tenants.findById(inst.tenant().getId()).ifPresent(t -> t.setOwnership(Ownership.PRIVATE)));
        Long invoiceId = cn.getInvoice().getId();
        ControlNumber shown = TenantContext.callAs(inst.info(), () -> tx.execute(x -> billing.currentControlNumber(invoiceId)));
        assertThat(shown).isNull();
        ControlNumber again = TenantContext.callAs(inst.info(), () -> tx.execute(x -> billing.ensureControlNumber(invoiceId)));
        assertThat(again.getIssuer()).isEqualTo(ControlNumber.Issuer.COLLECTION);
        assertThat(PaymentReference.isValid(again.getControlNo())).isTrue();
    }

    @Test
    void nactvetOwnershipMapsToCategories() {
        assertThat(Ownership.fromNactvet("Government")).isEqualTo(Ownership.PUBLIC);
        assertThat(Ownership.fromNactvet("Public")).isEqualTo(Ownership.PUBLIC);
        assertThat(Ownership.fromNactvet("Parastatal")).isEqualTo(Ownership.PUBLIC);
        assertThat(Ownership.fromNactvet("FBO")).isEqualTo(Ownership.FBO);
        assertThat(Ownership.fromNactvet("Faith Based")).isEqualTo(Ownership.FBO);
        assertThat(Ownership.fromNactvet("Roman Catholic Church")).isEqualTo(Ownership.FBO);
        assertThat(Ownership.fromNactvet("Private")).isEqualTo(Ownership.PRIVATE);
        assertThat(Ownership.fromNactvet(null)).isEqualTo(Ownership.PRIVATE);
        String ref = PaymentReference.issue(42);
        assertThat(ref).hasSize(12).startsWith("80042");
        assertThat(PaymentReference.isValid(ref)).isTrue();
        assertThat(PaymentReference.isValid(ref.substring(0, 11) + ((ref.charAt(11) - '0' + 1) % 10))).isFalse();
    }
}
