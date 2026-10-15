package tz.co.msingi.govmocks;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tz.co.msingi.govmocks.gepg.GepgCrypto;
import tz.co.msingi.govmocks.gepg.GepgMockService;
import tz.co.msingi.govmocks.gepg.GepgXml;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.msingi.govmocks.gepg.GepgXml.el;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "mock.gepg.delay-ms=50")
class GovMocksTest {

    /** CIMS's side of the test keys: signs as CIMS, checks GePG's signatures. */
    static final GepgCrypto CIMS = GepgCrypto.load("../dev-keys/cims-sp-test.p12", "test-only-not-secret", "cims-sp", "../dev-keys/gepg-mock-test.crt");
    static final List<String> received = new CopyOnWriteArrayList<>();
    static HttpServer fakeCims;

    @DynamicPropertySource
    static void cims(DynamicPropertyRegistry r) throws IOException {
        fakeCims = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeCims.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(ex.getRequestURI().getPath() + " " + body);
            String kind = GepgXml.kind(body);
            boolean ok = CIMS.verify(GepgXml.inner(body, kind), GepgXml.signature(body));
            String ack = CIMS.message("<" + kind + "Ack>" + el("TrxStsCode", ok ? 7101 : 7303) + "</" + kind + "Ack>");
            byte[] out = ack.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        fakeCims.start();
        r.add("mock.cims-url", () -> "http://127.0.0.1:" + fakeCims.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        fakeCims.stop(0);
    }

    @LocalServerPort
    int port;
    @Autowired
    GepgMockService gepg;

    RestClient http() {
        return RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    }

    String bill(String billId, String amount, String itemAmount) {
        return "<gepgBillSubReq><BillHdr>" + el("SpCode", "SP19921") + el("RtrRespFlg", "true") + "</BillHdr><BillTrxInf>" + el("BillId", billId)
                + el("SubSpCode", "1001") + el("SpSysId", "LCIMS001") + el("BillAmt", amount) + el("MiscAmt", "0") + el("BillExprDt", "2030-01-01T00:00:00")
                + el("PyrId", "A1") + el("PyrName", "Asha & Co <test>") + el("BillDesc", "Ada ya maombi · Application fee") + el("PyrCellNum", "255712345678") + el("Ccy", "TZS")
                + "<BillItems><BillItem>" + el("BillItemRef", "APP") + el("BillItemAmt", itemAmount) + el("GfsCode", "142202080040") + "</BillItem></BillItems>"
                + "</BillTrxInf></gepgBillSubReq>";
    }

    int code(String answer, String tag) {
        assertThat(CIMS.verify(GepgXml.inner(answer, tag), GepgXml.signature(answer))).as("GePG's answer is signed").isTrue();
        return Integer.parseInt(GepgXml.text(GepgXml.parse(answer), "TrxStsCode"));
    }

    @Test
    void gepgIssuesControlNumberAndPostsPaymentToCims() throws Exception {
        String ack = http().post().uri("/api/bill/sigqrequest").contentType(MediaType.APPLICATION_XML).body(CIMS.message(bill("7-11-1", "10000", "10000")).getBytes(StandardCharsets.UTF_8))
                .retrieve().body(String.class);
        assertThat(code(ack, "gepgBillSubReqAck")).isEqualTo(7101);
        for (int i = 0; i < 50 && received.stream().noneMatch(s -> s.contains("7-11-1")); i++) Thread.sleep(100);
        String cn = received.stream().filter(s -> s.startsWith("/api/integrations/gepg/control-number") && s.contains("7-11-1")).findFirst().orElseThrow();
        assertThat(cn).contains("<TrxSts>GS</TrxSts>").contains("<PayCntrNum>99");

        var bill = gepg.bills().stream().filter(b -> b.billId.equals("7-11-1")).findFirst().orElseThrow();
        assertThat(bill.payerName).isEqualTo("Asha & Co <test>");
        http().post().uri("/mock/gepg/bills/7-11-1/pay?amount=4000").retrieve().toBodilessEntity();
        assertThat(received).anyMatch(s -> s.startsWith("/api/integrations/gepg/payment-notification") && s.contains("<PaidAmt>4000</PaidAmt>"));
        assertThat(bill.status).isEqualTo("PARTIAL");
        assertThat(gepg.payments().get(0).delivered).isEqualTo("CIMS answered 7101");

        String day = gepg.today().toString();
        String recon = "<gepgSpReconcReq>" + el("SpReconcReqId", "7-" + day.replace("-", "") + "-1") + el("SpCode", "SP19921") + el("SpSysId", "LCIMS001")
                + el("TnxDt", day) + el("ReconcOpt", "1") + "</gepgSpReconcReq>";
        String rack = http().post().uri("/api/reconciliations/sig_sp_qrequest").contentType(MediaType.APPLICATION_XML).body(CIMS.message(recon).getBytes(StandardCharsets.UTF_8))
                .retrieve().body(String.class);
        assertThat(code(rack, "gepgSpReconcReqAck")).isEqualTo(7101);
        for (int i = 0; i < 50 && received.stream().noneMatch(s -> s.startsWith("/api/integrations/gepg/reconciliation")); i++) Thread.sleep(100);
        assertThat(received).anyMatch(s -> s.startsWith("/api/integrations/gepg/reconciliation") && s.contains("<SpBillId>7-11-1</SpBillId>"));
    }

    @Test
    void gepgRefusesBadSignatureAndBadBills() {
        String tampered = CIMS.message(bill("7-12-1", "10000", "10000")).replace("<BillAmt>10000", "<BillAmt>1");
        String a1 = http().post().uri("/api/bill/sigqrequest").contentType(MediaType.APPLICATION_XML).body(tampered.getBytes(StandardCharsets.UTF_8)).retrieve().body(String.class);
        assertThat(code(a1, "gepgBillSubReqAck")).isEqualTo(7303);
        String a2 = http().post().uri("/api/bill/sigqrequest").contentType(MediaType.APPLICATION_XML).body(CIMS.message(bill("7-12-2", "10000", "9000")).getBytes(StandardCharsets.UTF_8))
                .retrieve().body(String.class);
        assertThat(code(a2, "gepgBillSubReqAck")).isEqualTo(7242);
        String a3 = http().post().uri("/api/bill/sigqrequest").contentType(MediaType.APPLICATION_XML)
                .body("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><Gepg>&e;</Gepg>").retrieve().body(String.class);
        assertThat(code(a3, "gepgBillSubReqAck")).isEqualTo(7242);
    }

    @Test
    void nidaRules() {
        assertThat(nida("20050314-11111-00001-12", "2005-03-14")).containsEntry("status", "VALID").containsEntry("surname", "MWINYI");
        assertThat(nida("20050314111110000119", "2005-03-14")).containsEntry("status", "NOT_FOUND");
        assertThat(nida("20050314111110000112", "2004-03-14")).containsEntry("status", "MISMATCH");
        assertThat(nida("123", "2005-03-14")).containsEntry("status", "INVALID");
        assertThatThrownBy(() -> http().post().uri("/nida/api/v1/verify").header("X-Api-Key", "wrong").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("nin", "1")).retrieve().body(Map.class)).isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> nida(String nin, String dob) {
        return http().post().uri("/nida/api/v1/verify").header("X-Api-Key", "mock-nida-key").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("nin", nin, "first_name", "Asha", "surname", "Mwinyi", "date_of_birth", dob)).retrieve().body(Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void heslbBeneficiariesFromRegistrationAndDisbursement() {
        Map<String, Object> reg = http().post().uri("/heslb/api/v1/registrations").header("X-Api-Key", "mock-heslb-key").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("institution_code", "HTEST", "academic_year", "2027/2028", "kind", "REGISTRATION", "students", List.of(
                        Map.of("index_number", "S0101/0002", "registration_no", "R1", "first_name", "Asha", "surname", "Juma"),
                        Map.of("index_number", "S0101/0003", "registration_no", "R2"),
                        Map.of("registration_no", "R3")))).retrieve().body(Map.class);
        assertThat(reg).containsEntry("received", 3).containsEntry("accepted", 2);
        Map<String, Object> ben = http().get().uri("/heslb/api/v1/beneficiaries?institution_code=HTEST&academic_year=2027/2028")
                .header("X-Api-Key", "mock-heslb-key").retrieve().body(Map.class);
        List<Map<String, Object>> list = (List<Map<String, Object>>) ben.get("beneficiaries");
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).containsEntry("registration_no", "R1").containsEntry("total_to_institution", 1200000);
        http().post().uri("/mock/heslb/disburse?institutionCode=HTEST&academicYear=2027/2028").retrieve().toBodilessEntity();
        Map<String, Object> dis = http().get().uri("/heslb/api/v1/disbursements?institution_code=HTEST&academic_year=2027/2028")
                .header("X-Api-Key", "mock-heslb-key").retrieve().body(Map.class);
        assertThat((List<?>) dis.get("disbursements")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void museChecksBalanceAndRefusesDuplicates() {
        Map<String, Object> journal = Map.of("vote_code", "V1", "batch_ref", "CIMS-JV-1", "lines", List.of(
                Map.of("account", "BANK", "debit", 50000, "credit", 0), Map.of("gfs_code", "142202", "debit", 0, "credit", 50000)));
        Map<String, Object> ok = http().post().uri("/muse/api/v1/journals").header("X-Api-Key", "mock-muse-key").contentType(MediaType.APPLICATION_JSON)
                .body(journal).retrieve().body(Map.class);
        assertThat(ok).containsEntry("status", "POSTED");
        assertThatThrownBy(() -> http().post().uri("/muse/api/v1/journals").header("X-Api-Key", "mock-muse-key").contentType(MediaType.APPLICATION_JSON)
                .body(journal).retrieve().body(Map.class)).isInstanceOf(HttpClientErrorException.Conflict.class);
        assertThatThrownBy(() -> http().post().uri("/muse/api/v1/journals").header("X-Api-Key", "mock-muse-key").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("batch_ref", "CIMS-JV-2", "lines", List.of(Map.of("debit", 1, "credit", 0)))).retrieve().body(Map.class))
                .isInstanceOf(HttpClientErrorException.UnprocessableEntity.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tamisemiSelectionsAndOutage() {
        http().post().uri("/mock/tamisemi/selections?institutionCode=TTEST&programmeCode=BTCIT&year=2027&count=3").retrieve().toBodilessEntity();
        Map<String, Object> s = http().get().uri("/tamisemi/api/v1/selections?institution_code=TTEST&year=2027").header("X-Api-Key", "mock-tamisemi-key")
                .retrieve().body(Map.class);
        assertThat((List<?>) s.get("selections")).hasSize(3);
        http().post().uri("/mock/outage?system=TAMISEMI&on=true").retrieve().toBodilessEntity();
        try {
            assertThatThrownBy(() -> http().get().uri("/tamisemi/api/v1/selections?institution_code=TTEST&year=2027").header("X-Api-Key", "mock-tamisemi-key")
                    .retrieve().body(Map.class)).isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
        } finally {
            http().post().uri("/mock/outage?system=TAMISEMI&on=false").retrieve().toBodilessEntity();
        }
    }
}
