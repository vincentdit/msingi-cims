package tz.co.msingi.cims.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.finance.web.GepgCallbackController;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GePG notifications: signature checked, institution resolved from bill id, idempotent posting. */
@IntegrationTest
class GepgCallbackIT {

    @Autowired MockMvc mvc;
    @Autowired TestData data;
    @Autowired BillingService billing;
    @Autowired TransactionTemplate tx;

    @Test
    void signedNotificationIsPostedOnce() throws Exception {
        TestData.Institution inst = data.institution("G" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), BigDecimal.TEN);
        ControlNumber cn = TenantContext.callAs(inst.info(), () -> tx.execute(s -> {
            Invoice inv = billing.createInvoice(new BillingService.NewInvoice(Invoice.PayerType.APPLICANT, 1L, "Test Payer",
                    "255700000001", null, "TEST", null, "Test bill",
                    List.of(new BillingService.NewInvoice.Line("TEST", "Test fee", new BigDecimal("5000")))));
            return billing.ensureControlNumber(inv.getId());
        }));

        String body = """
                {"billId":"%s","controlNo":"%s","gepgReceiptNo":"GEPG-%s","pspReceiptNo":"PSP1","pspName":"Bank",
                 "channel":"BANK","amount":5000,"payerPhone":"255700000001","paidAt":"2026-10-06T10:00:00Z"}"""
                .formatted(cn.getBillId(), cn.getControlNo(), UUID.randomUUID().toString().substring(0, 12));
        String signature = GepgCallbackController.sign(body, "test-secret");

        mvc.perform(post("/api/integrations/gepg/payment-notification").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Gepg-Signature", "bad").content(body))
                .andExpect(status().isUnauthorized());

        for (int i = 0; i < 2; i++) { // second delivery must be ignored
            mvc.perform(post("/api/integrations/gepg/payment-notification").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Gepg-Signature", signature).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACCEPTED"));
        }

        Invoice inv = TenantContext.callAs(inst.info(), () -> tx.execute(s -> billing.get(cn.getInvoice().getId())));
        assertThat(inv.getStatus()).isEqualTo(Invoice.Status.PAID);
        assertThat(inv.getPaidAmount()).isEqualByComparingTo("5000");
        int payments = TenantContext.callAs(inst.info(), () -> tx.execute(s -> billing.paymentsFor(inv.getId()).size()));
        assertThat(payments).isEqualTo(1);
    }
}
