package tz.co.msingi.cims.finance.aggregator;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tz.co.msingi.cims.common.exception.BusinessRuleException;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The three aggregator adapters against a stand-in server: what they send, and how they read the answers. */
class AggregatorClientsTest {

    HttpServer server;
    String base;
    final List<String> received = new CopyOnWriteArrayList<>();
    final Map<String, String> answers = new ConcurrentHashMap<>();
    final Map<String, Integer> statuses = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            StringBuilder h = new StringBuilder();
            for (String k : List.of("Authorization", "X-API-Key", "client-id", "api-key", "Signed-Fields", "Digest", "Timestamp")) {
                if (ex.getRequestHeaders().getFirst(k) != null) h.append(' ').append(k).append('=').append(ex.getRequestHeaders().getFirst(k));
            }
            received.add(ex.getRequestMethod() + " " + ex.getRequestURI() + h + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = answers.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(statuses.getOrDefault(path, 200), out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    AggregatorClient.Push push(String network) {
        return new AggregatorClient.Push("800100123456P1", "800100123456", "255712345678", network, new BigDecimal("30000.40"), "Tuition", "Asha Mwinyi", null);
    }

    @Test
    void selcomSignsEveryCallAndReadsItsAnswers() {
        var a = new AggregatorClient.Account(AggregatorProvider.SELCOM, false, Map.of("vendor", "TILL1", "apiKey", "key-1", "apiSecret", "secret-1"),
                base + "/v1", null, "https://bahari.cims.ac.tz/api/integrations/collections/selcom/T0K");
        answers.put("/v1/checkout/create-order-minimal", "{\"resultcode\":\"000\",\"result\":\"SUCCESS\"}");
        answers.put("/v1/checkout/wallet-payment", "{\"resultcode\":\"111\",\"result\":\"PENDING\",\"reference\":\"SR1\",\"message\":\"In progress\"}");
        var r = new SelcomClient().push(a, push(null));
        assertThat(r.state()).isEqualTo(AggregatorClient.State.PENDING);
        assertThat(r.providerRef()).isEqualTo("SR1");
        assertThat(received.get(0)).contains("Authorization=SELCOM a2V5LTE=").contains("Signed-Fields=vendor,order_id,buyer_email,buyer_name,buyer_phone,amount")
                .contains("\"amount\":\"30001\"").contains("\"webhook\":\"aHR0cHM6Ly9iYWhhcmk");
        assertThat(received.get(1)).contains("\"msisdn\":\"255712345678\"").contains("Signed-Fields=transid,order_id,msisdn");
        answers.put("/v1/checkout/order-status", "{\"result\":\"SUCCESS\",\"data\":[{\"order_id\":\"800100123456P1\",\"payment_status\":\"COMPLETED\",\"transid\":\"TX9\",\"amount\":\"30001\",\"channel\":\"AIRTELMONEY\"}]}");
        var s = new SelcomClient().status(a, "800100123456P1", null);
        assertThat(s.state()).isEqualTo(AggregatorClient.State.PAID);
        assertThat(s.transactionId()).isEqualTo("TX9");
        assertThat(s.amount()).isEqualByComparingTo("30001");
        answers.put("/v1/checkout/create-order-minimal", "{\"resultcode\":\"403\",\"result\":\"FAIL\",\"message\":\"Invalid vendor\"}");
        assertThat(new SelcomClient().push(a, push(null)).message()).contains("Invalid vendor");
        var missing = new AggregatorClient.Account(AggregatorProvider.SELCOM, false, Map.of("apiKey", "k"), base, null, null);
        assertThatThrownBy(() -> new SelcomClient().push(missing, push(null))).isInstanceOf(BusinessRuleException.class).hasMessageContaining("vendor");
    }

    @Test
    void azamPayTakesATokenNamesTheNetworkAndRenewsTheTokenOnce() {
        var a = new AggregatorClient.Account(AggregatorProvider.AZAMPAY, true, Map.of("appName", "cims", "clientId", "c1", "clientSecret", "s1", "apiKey", "k1"),
                base + "/checkout", base + "/auth", null);
        answers.put("/auth/AppRegistration/GenerateToken", "{\"data\":{\"accessToken\":\"T1\",\"expire\":\"2099-01-01T00:00:00Z\"},\"success\":true}");
        answers.put("/checkout/azampay/mno/checkout", "{\"success\":true,\"transactionId\":\"AZ1\",\"message\":\"Received\"}");
        AzamPayClient c = new AzamPayClient();
        assertThatThrownBy(() -> c.push(a, push(null))).hasMessageContaining("mobile money service");
        var r = c.push(a, push("MIXX_BY_YAS"));
        assertThat(r.state()).isEqualTo(AggregatorClient.State.PENDING);
        assertThat(r.providerRef()).isEqualTo("AZ1");
        assertThat(received.get(0)).contains("\"clientSecret\":\"s1\"");
        assertThat(received.get(1)).contains("Authorization=Bearer T1").contains("X-API-Key=k1").contains("\"provider\":\"Tigo\"").contains("\"externalId\":\"800100123456P1\"");
        statuses.put("/checkout/azampay/mno/checkout", 401);
        c.push(a, push("MPESA"));
        assertThat(received.stream().filter(x -> x.contains("GenerateToken")).count()).isEqualTo(2);
        var n = c.read(a, Map.of(), "{\"msisdn\":\"255712345678\",\"amount\":\"30001\",\"utilityref\":\"800100123456P1\",\"operator\":\"Tigo\",\"reference\":\"AZ1\",\"transactionstatus\":\"success\",\"fspReferenceId\":\"MP77\"}");
        assertThat(n.orderId()).isEqualTo("800100123456P1");
        assertThat(n.result().state()).isEqualTo(AggregatorClient.State.PAID);
        assertThat(n.result().transactionId()).isEqualTo("MP77");
        statuses.put("/auth/AppRegistration/GenerateToken", 423);
        answers.put("/auth/AppRegistration/GenerateToken", "{\"message\":\"Provided detail is not valid\",\"success\":false}");
        assertThatThrownBy(() -> c.test(a)).hasMessageContaining("not valid");
    }

    @Test
    void clickPesaTakesATokenPromptsAndIsAskedAboutThePayment() {
        var a = new AggregatorClient.Account(AggregatorProvider.CLICKPESA, false, Map.of("clientId", "c1", "apiKey", "k1"), base, null, null);
        answers.put("/third-parties/generate-token", "{\"success\":true,\"token\":\"Bearer CT1\"}");
        answers.put("/third-parties/payments/initiate-ussd-push-request", "{\"id\":\"CP1\",\"status\":\"PROCESSING\",\"orderReference\":\"800100123456P1\"}");
        ClickPesaClient c = new ClickPesaClient();
        var r = c.push(a, push(null));
        assertThat(r.state()).isEqualTo(AggregatorClient.State.PENDING);
        assertThat(received.get(0)).contains("client-id=c1").contains("api-key=k1");
        assertThat(received.get(1)).contains("Authorization=Bearer CT1").contains("\"orderReference\":\"800100123456P1\"").contains("\"amount\":\"30001\"");
        answers.put("/third-parties/payments/800100123456P1", "[{\"id\":\"CP1\",\"status\":\"SUCCESS\",\"paymentReference\":\"PR5\",\"collectedAmount\":\"30001\",\"channel\":\"M-PESA\"}]");
        var s = c.status(a, "800100123456P1", "CP1");
        assertThat(s.state()).isEqualTo(AggregatorClient.State.PAID);
        assertThat(s.transactionId()).isEqualTo("PR5");
        statuses.put("/third-parties/payments/800100123456P2", 404);
        assertThat(c.status(a, "800100123456P2", null).state()).isEqualTo(AggregatorClient.State.PENDING);
        var n = c.read(a, Map.of(), "{\"event\":\"PAYMENT RECEIVED\",\"data\":{\"orderReference\":\"800100123456P1\",\"status\":\"SUCCESS\"}}");
        assertThat(n.orderId()).isEqualTo("800100123456P1");
    }

    @Test
    void phoneNumbersAreReadTheTanzanianWay() {
        assertThat(PhonePaymentService.phone("0712 345 678")).isEqualTo("255712345678");
        assertThat(PhonePaymentService.phone("+255 652 345 678")).isEqualTo("255652345678");
        assertThat(PhonePaymentService.phone("712345678")).isEqualTo("255712345678");
        assertThatThrownBy(() -> PhonePaymentService.phone("0222 123 456")).isInstanceOf(BusinessRuleException.class);
        assertThat(AzamPayClient.network("airtel_money")).isEqualTo("Airtel");
        assertThatThrownBy(() -> AzamPayClient.network("TPESA")).hasMessageContaining("does not take");
    }
}
