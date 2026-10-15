package tz.co.msingi.cims.finance;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tz.co.msingi.cims.finance.gepg.GepgCrypto;
import tz.co.msingi.cims.finance.gepg.GepgGateway;
import tz.co.msingi.cims.finance.gepg.GepgKeys;
import tz.co.msingi.cims.finance.gepg.GepgUnavailableException;
import tz.co.msingi.cims.finance.gepg.GepgXml;
import tz.co.msingi.cims.finance.gepg.LiveGepgGateway;
import tz.co.msingi.cims.integration.settings.IntegrationSettingsService;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The live GePG adapter against a stand-in GePG that checks CIMS's signature and signs its answers with the test GePG key. */
class LiveGepgGatewayTest {

    static final String KEYS = "../dev-keys/";
    /** GePG's side of the test keys. */
    static final GepgCrypto GEPG = GepgCrypto.load(KEYS + "gepg-mock-test.p12", "test-only-not-secret", "gepg", KEYS + "cims-sp-test.crt");
    /** Someone else's key: answers signed with it must be refused. */
    static final GepgCrypto IMPOSTOR = GepgCrypto.load(KEYS + "cims-sp-test.p12", "test-only-not-secret", "cims-sp", KEYS + "gepg-mock-test.crt");

    HttpServer server;
    final List<String> received = new CopyOnWriteArrayList<>();
    final List<String> headers = new CopyOnWriteArrayList<>();
    final AtomicInteger code = new AtomicInteger(7101);
    volatile GepgCrypto signer = GEPG;
    LiveGepgGateway gateway;

    static class Settings extends IntegrationSettingsService {
        IntegrationSettingsService.Settings s = new IntegrationSettingsService.Settings("SP19921", "1001", "LCIMS001", "140206",
                false, null, false, false, null, null, false, null);

        Settings() {
            super(null, null, null);
        }

        @Override
        public IntegrationSettingsService.Settings requireGepg() {
            return s;
        }
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(ex.getRequestURI().getPath() + " " + body);
            headers.add(ex.getRequestHeaders().getFirst("Gepg-Code") + " " + ex.getRequestHeaders().getFirst("Gepg-Com"));
            String kind = GepgXml.kind(body);
            boolean ok = GEPG.verify(GepgXml.inner(body, kind), GepgXml.signature(body));
            String ack = signer.message("<" + kind + "Ack>" + GepgXml.el("TrxStsCode", ok ? code.get() : 7303) + "</" + kind + "Ack>");
            byte[] out = ack.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/xml");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        GepgKeys keys = new GepgKeys(KEYS + "cims-sp-test.p12", "test-only-not-secret", "cims-sp", KEYS + "gepg-mock-test.crt");
        gateway = new LiveGepgGateway(keys, new Settings(), "http://127.0.0.1:" + server.getAddress().getPort(),
                "/api/bill/sigqrequest", "/api/reconciliations/sig_sp_qrequest", 5, 5);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    GepgGateway.BillRequest bill() {
        return new GepgGateway.BillRequest("12-345-1", "Asha <Juma> & Co", "0712345678", null, "Ada ya mwaka 2027/2028 · Diploma – IT", new BigDecimal("650000"), "TZS",
                OffsetDateTime.parse("2027-10-01T00:00:00+03:00"), "STUDENT-9", List.of(
                new GepgGateway.BillItem("TUITION", new BigDecimal("600000"), "142202080040"),
                new GepgGateway.BillItem("REGISTRATION", new BigDecimal("50000"), null)));
    }

    @Test
    void sendsASignedBillAndWaitsForTheControlNumber() {
        GepgGateway.ControlNumberResponse r = gateway.submitBill(bill());
        assertThat(r.issued()).isFalse();
        assertThat(r.errorMessage()).isNull();   // pending: the control number follows by callback
        String sent = received.get(0);
        assertThat(sent).startsWith("/api/bill/sigqrequest <Gepg><gepgBillSubReq>");
        assertThat(sent).contains("<SpCode>SP19921</SpCode>", "<SubSpCode>1001</SubSpCode>", "<SpSysId>LCIMS001</SpSysId>", "<BillAmt>650000.00</BillAmt>",
                "<PyrCellNum>255712345678</PyrCellNum>", "<PyrName>Asha &lt;Juma&gt; &amp; Co</PyrName>", "<BillExprDt>2027-10-01T00:00:00</BillExprDt>",
                "<GfsCode>142202080040</GfsCode>", "<GfsCode>140206</GfsCode>");   // the second item falls back to the institution's default GFS code
        assertThat(headers.get(0)).isEqualTo("SP19921 default.sp.in");
    }

    @Test
    void refusalsAndForgedAnswers() {
        code.set(7242);
        assertThat(gateway.submitBill(bill()).errorMessage()).contains("7242").contains("invalid bill data");
        code.set(7101);
        signer = IMPOSTOR;
        assertThatThrownBy(() -> gateway.submitBill(bill())).isInstanceOf(GepgUnavailableException.class).hasMessageContaining("not signed");
        signer = GEPG;
        GepgGateway.BillRequest bad = new GepgGateway.BillRequest("12-345-2", "X", null, null, "Fees", new BigDecimal("100"), "TZS", null, null,
                List.of(new GepgGateway.BillItem("A", new BigDecimal("90"), "1")));
        assertThat(gateway.submitBill(bad).errorMessage()).contains("do not add up");
        server.stop(0);
        assertThatThrownBy(() -> gateway.submitBill(bill())).isInstanceOf(GepgUnavailableException.class);
    }

    @Test
    void asksForTheDaysReconciliation() {
        assertThat(gateway.requestReconciliation("12-20271005-1", LocalDate.of(2027, 10, 5))).isTrue();
        assertThat(received.get(0)).startsWith("/api/reconciliations/sig_sp_qrequest")
                .contains("<SpReconcReqId>12-20271005-1</SpReconcReqId>", "<TnxDt>2027-10-05</TnxDt>");
    }
}
