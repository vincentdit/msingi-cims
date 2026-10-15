package tz.co.msingi.cims.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.msingi.cims.finance.domain.ControlNumber;
import tz.co.msingi.cims.finance.domain.FinanceRepositories.ControlNumberRepository;
import tz.co.msingi.cims.finance.domain.Invoice;
import tz.co.msingi.cims.finance.gepg.GepgCrypto;
import tz.co.msingi.cims.finance.gepg.GepgXml;
import tz.co.msingi.cims.finance.service.BillingService;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static tz.co.msingi.cims.finance.gepg.GepgXml.el;

/**
 * GePG's signed callbacks (FR-INT-03, live GePG): control number, payment and reconciliation, each checked against GePG's
 * certificate and acknowledged with a signed 7101; forged messages refused with 7303.
 */
@IntegrationTest
class GepgLiveCallbacksIT {

    static final GepgCrypto GEPG = GepgCrypto.load("../dev-keys/gepg-mock-test.p12", "test-only-not-secret", "gepg", "../dev-keys/cims-sp-test.crt");
    static final GepgCrypto IMPOSTOR = GepgCrypto.load("../dev-keys/cims-sp-test.p12", "test-only-not-secret", "cims-sp", "../dev-keys/gepg-mock-test.crt");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;
    @Autowired BillingService billing;
    @Autowired ControlNumberRepository controlNumbers;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    TestData.Institution inst;
    AdmissionScenario s;

    @BeforeEach
    void setUp() {
        String tenant = "L" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, BigDecimal.TEN);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        s = new AdmissionScenario(mvc, json, tenant, inst);
    }

    <T> T db(Supplier<T> work) {
        return TenantContext.callAs(inst.info(), () -> tx.execute(x -> work.get()));
    }

    /** A bill sent to GePG whose control number has not arrived yet. */
    ControlNumber pendingBill() {
        return db(() -> {
            Invoice inv = billing.createInvoice(new BillingService.NewInvoice(Invoice.PayerType.APPLICANT, 1L, "Test Payer", "255700000001", null,
                    "TEST", null, "Test bill", List.of(new BillingService.NewInvoice.Line("TEST", "Test fee", new BigDecimal("5000")))));
            ControlNumber cn = new ControlNumber();
            cn.setInvoice(inv);
            cn.setBillId(inst.tenant().getId() + "-" + inv.getId() + "-1");
            cn.setStatus(ControlNumber.Status.REQUESTED);
            return controlNumbers.save(cn);
        });
    }

    String send(String path, String content, GepgCrypto signer) throws Exception {
        return mvc.perform(post("/api/integrations/gepg/" + path).contentType(MediaType.APPLICATION_XML).content(signer.message(content)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    int code(String answer, String tag) {
        assertThat(GEPG.verify(GepgXml.inner(answer, tag), GepgXml.signature(answer))).as("CIMS signs its acknowledgement").isTrue();
        return Integer.parseInt(GepgXml.text(GepgXml.parse(answer), "TrxStsCode"));
    }

    static String controlNumber(String billId, String cn) {
        return "<gepgBillSubResp><BillTrxInf>" + el("BillId", billId) + el("TrxSts", "GS") + el("PayCntrNum", cn) + el("TrxStsCode", "7101")
                + "</BillTrxInf></gepgBillSubResp>";
    }

    static String payment(String billId, String cn, String receipt, String amount, String at) {
        return "<gepgPmtSpInfo><PymtTrxInf>" + el("TrxId", "T" + receipt) + el("SpCode", "SP1") + el("PayRefId", receipt) + el("BillId", billId)
                + el("PayCtrNum", cn) + el("BillAmt", "5000") + el("PaidAmt", amount) + el("BillPayOpt", "3") + el("CCy", "TZS") + el("TrxDtTm", at)
                + el("UsdPayChnl", "MOBILE") + el("PyrCellNum", "255700000001") + el("PyrName", "Zawadi Mwakyusa · Ndugu") + el("PyrEmail", "")
                + el("PspReceiptNumber", "MP" + receipt) + el("PspName", "M-PESA") + el("CtrAccNum", "0150") + "</PymtTrxInf></gepgPmtSpInfo>";
    }

    @Test
    void controlNumberThenPaymentArriveSignedAndArePostedOnce() throws Exception {
        ControlNumber cn = pendingBill();
        String number = "99" + String.format("%010d", Math.abs(UUID.randomUUID().getMostSignificantBits()) % 10_000_000_000L);

        assertThat(code(send("control-number", controlNumber(cn.getBillId(), number), IMPOSTOR), "gepgBillSubRespAck")).isEqualTo(7303);
        assertThat(db(() -> controlNumbers.findById(cn.getId()).orElseThrow().getStatus())).isEqualTo(ControlNumber.Status.REQUESTED);

        assertThat(code(send("control-number", controlNumber(cn.getBillId(), number), GEPG), "gepgBillSubRespAck")).isEqualTo(7101);
        ControlNumber after = db(() -> controlNumbers.findById(cn.getId()).orElseThrow());
        assertThat(after.getStatus()).isEqualTo(ControlNumber.Status.ACTIVE);
        assertThat(after.getControlNo()).isEqualTo(number);
        List<String> sms = db(() -> jdbc.queryForList("select body from messages where template_code = 'CONTROL_NUMBER'", String.class));
        assertThat(sms).hasSize(1).allMatch(b -> b.contains(number));
        // GePG sends the same control number again: acknowledged, nothing changes, no second SMS
        assertThat(code(send("control-number", controlNumber(cn.getBillId(), number), GEPG), "gepgBillSubRespAck")).isEqualTo(7101);
        Integer smsAfter = db(() -> jdbc.queryForObject("select count(*) from messages where template_code = 'CONTROL_NUMBER'", Integer.class));
        assertThat(smsAfter).isEqualTo(1);

        String receipt = "EC" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String today = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")) + "T09:30:00";
        for (int i = 0; i < 2; i++) {
            assertThat(code(send("payment-notification", payment(cn.getBillId(), number, receipt, "5000", today), GEPG), "gepgPmtSpInfoAck")).isEqualTo(7101);
        }
        Invoice inv = db(() -> billing.get(cn.getInvoice().getId()));
        assertThat(inv.getStatus()).isEqualTo(Invoice.Status.PAID);
        Integer payments = db(() -> billing.paymentsFor(inv.getId()).size());
        assertThat(payments).isEqualTo(1);

        // a payment for a bill CIMS does not know is acknowledged (resending would not help) and logged as a failure
        assertThat(code(send("payment-notification", payment("999999-1-1", "990000000001", "ECX1", "100", today), GEPG), "gepgPmtSpInfoAck"))
                .isEqualTo(7101);
        // a control number for an unknown bill is not acknowledged, so GePG sends it again
        assertThat(code(send("control-number", controlNumber(inst.tenant().getId() + "-0-9", "990000000002"), GEPG), "gepgBillSubRespAck"))
                .isEqualTo(7201);
        List<Map<String, Object>> log = db(() -> jdbc.queryForList("select operation, outcome, mode from integration_calls where system = 'GEPG' and direction = 'IN'"));
        assertThat(log).anyMatch(r -> "controlNumber".equals(r.get("operation")) && "SUCCESS".equals(r.get("outcome")))
                .anyMatch(r -> "paymentNotification".equals(r.get("operation")) && "SUCCESS".equals(r.get("outcome")));

        // reconciliation by API: the simulator cannot be asked, so the request is refused with advice to import the file
        String accountant = s.staffToken("accountant");
        s.call(post("/api/finance/reconciliations/request-gepg?date=" + LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"))), accountant, null)
                .andExpect(jsonPath("$.code").value("GEPG_FILE_ONLY"));
        // GePG's answer to a request made while live: matched like an imported file
        LocalDate day = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));
        String reqId = inst.tenant().getId() + "-" + day.toString().replace("-", "") + "-1";
        db(() -> jdbc.update("insert into gepg_recon_requests (tenant_id, request_id, recon_date, requested_by_name) values (cims_current_tenant(), ?, ?, 'Accountant')",
                reqId, day));
        String recon = "<gepgSpReconcResp><ReconcBatchInfo>" + el("SpReconcReqId", reqId) + el("SpCode", "SP1") + el("ReconcStsCode", "7101")
                + "</ReconcBatchInfo><ReconcTrans><ReconcTrxInf>" + el("SpBillId", cn.getBillId()) + el("BillCtrNum", number) + el("pspTrxId", "MP" + receipt)
                + el("PaidAmt", "5000") + el("CCy", "TZS") + el("PayRefId", receipt) + el("TrxDtTm", today) + el("UsdPayChnl", "MOBILE") + el("PspName", "M-PESA")
                + el("DptCellNum", "255700000001") + "</ReconcTrxInf><ReconcTrxInf>" + el("SpBillId", "0-0-0") + el("BillCtrNum", "990000000003")
                + el("pspTrxId", "TP1") + el("PaidAmt", "15000") + el("CCy", "TZS") + el("PayRefId", "ECSTRAY" + receipt) + el("TrxDtTm", today)
                + el("UsdPayChnl", "MOBILE") + el("PspName", "TIGO-PESA") + "</ReconcTrxInf></ReconcTrans></gepgSpReconcResp>";
        assertThat(code(send("reconciliation", recon, GEPG), "gepgSpReconcRespAck")).isEqualTo(7101);
        Map<String, Object> r = db(() -> jdbc.queryForMap("select source, matched_count, exception_count, imported_by_name from gepg_reconciliations where recon_date = ?", day));
        assertThat(r).containsEntry("source", "API").containsEntry("matched_count", 1).containsEntry("exception_count", 1).containsEntry("imported_by_name", "GePG");
        assertThat(db(() -> jdbc.queryForObject("select status from gepg_recon_requests where request_id = ?", String.class, reqId))).isEqualTo("RECEIVED");
    }
}
