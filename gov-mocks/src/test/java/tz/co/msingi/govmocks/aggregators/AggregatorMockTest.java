package tz.co.msingi.govmocks.aggregators;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** The aggregator mock: Selcom's signed calls, AzamPay's token and checkout, ClickPesa's token, push and status; and the notifications. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"mock.aggregators.complete-seconds=1"})
class AggregatorMockTest {

    @LocalServerPort
    int port;

    RestClient http() {
        return RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void threeAggregatorsPromptPhonesAndNotifyTheInstitution() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        HttpServer inst = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        inst.createContext("/", ex -> {
            got.add(ex.getRequestURI().getPath() + " " + ex.getRequestHeaders().getFirst("Digest") + " "
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"status\":\"ACCEPTED\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        inst.start();
        try {
            String base = "http://127.0.0.1:" + inst.getAddress().getPort();
            http().post().uri("/mock/aggregators/reset").retrieve().toBodilessEntity();

            // Selcom: wrong digest refused; a signed order and push; the webhook signed and sent to the order's address
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("vendor", AggregatorMockController.SELCOM_VENDOR);
            order.put("order_id", "800100123456P1");
            order.put("buyer_phone", "255712345678");
            order.put("amount", "30000");
            order.put("webhook", Base64.getEncoder().encodeToString((base + "/selcom").getBytes(StandardCharsets.UTF_8)));
            Map<String, Object> bad = http().post().uri("/selcom/v1/checkout/create-order-minimal").contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> selcomHeaders(order, "wrong").forEach(h::set)).body(order).exchange((rq, rs) -> rs.bodyTo(Map.class));
            assertThat(bad).containsEntry("message", "Invalid digest");
            assertThat(selcom("/selcom/v1/checkout/create-order-minimal", order)).containsEntry("resultcode", "000");
            Map<String, Object> push = new LinkedHashMap<>();
            push.put("transid", "800100123456P1");
            push.put("order_id", "800100123456P1");
            push.put("msisdn", "255712345678");
            assertThat(selcom("/selcom/v1/checkout/wallet-payment", push)).containsEntry("resultcode", "111");

            // AzamPay: token, checkout to a phone ending in 9 (refused), notification to the address set on the dashboard
            http().post().uri("/mock/aggregators/callback?provider=AZAMPAY&url={u}", base + "/azampay").retrieve().toBodilessEntity();
            Map<String, Object> tok = http().post().uri("/azampay-auth/AppRegistration/GenerateToken").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("appName", "cims", "clientId", "mock-azam-client", "clientSecret", "mock-azam-secret")).retrieve().body(Map.class);
            String azam = (String) ((Map<String, Object>) tok.get("data")).get("accessToken");
            Map<String, Object> co = http().post().uri("/azampay/azampay/mno/checkout").contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + azam).header("X-API-Key", "mock-azam-key")
                    .body(Map.of("accountNumber", "255652345679", "amount", "5000", "currency", "TZS", "externalId", "800100123456P2", "provider", "Tigo"))
                    .retrieve().body(Map.class);
            assertThat(co).containsEntry("success", true);

            // ClickPesa: token, push, status
            http().post().uri("/mock/aggregators/callback?provider=CLICKPESA&url={u}", base + "/clickpesa").retrieve().toBodilessEntity();
            Map<String, Object> ct = http().post().uri("/clickpesa/third-parties/generate-token").header("client-id", "mock-clickpesa-client")
                    .header("api-key", "mock-clickpesa-key").retrieve().body(Map.class);
            String click = (String) ct.get("token");
            assertThat(http().post().uri("/clickpesa/third-parties/payments/initiate-ussd-push-request").contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", click).body(Map.of("amount", "10000", "currency", "TZS", "orderReference", "800100123456P3", "phoneNumber", "255782345678"))
                    .retrieve().body(Map.class)).containsEntry("status", "PROCESSING");

            long until = System.currentTimeMillis() + 8000;
            while (got.size() < 3 && System.currentTimeMillis() < until) Thread.sleep(200);
            assertThat(got).hasSize(3);
            assertThat(got).anySatisfy(g -> assertThat(g).startsWith("/selcom ").contains("\"payment_status\":\"COMPLETED\"").doesNotContain(" null "));
            assertThat(got).anySatisfy(g -> assertThat(g).startsWith("/azampay ").contains("\"transactionstatus\":\"failure\"").contains("800100123456P2"));
            assertThat(got).anySatisfy(g -> assertThat(g).startsWith("/clickpesa ").contains("PAYMENT RECEIVED").contains("\"status\":\"SUCCESS\""));
            List<Map<String, Object>> st = http().get().uri("/clickpesa/third-parties/payments/800100123456P3").header("Authorization", click).retrieve().body(List.class);
            assertThat(st.get(0)).containsEntry("status", "SUCCESS");
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("order_id", "800100123456P1");
            Map<String, Object> s = http().get().uri("/selcom/v1/checkout/order-status?order_id=800100123456P1").headers(h -> selcomHeaders(q, AggregatorMockController.SELCOM_SECRET).forEach(h::set))
                    .retrieve().body(Map.class);
            assertThat(((List<Map<String, Object>>) s.get("data")).get(0)).containsEntry("payment_status", "COMPLETED");
        } finally {
            inst.stop(0);
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> selcom(String path, Map<String, Object> body) {
        return http().post().uri(path).contentType(MediaType.APPLICATION_JSON).headers(h -> selcomHeaders(body, AggregatorMockController.SELCOM_SECRET).forEach(h::set))
                .body(body).retrieve().body(Map.class);
    }

    static Map<String, String> selcomHeaders(Map<String, Object> fields, String secret) {
        String ts = OffsetDateTime.now(ZoneId.of("Africa/Dar_es_Salaam")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "SELCOM " + Base64.getEncoder().encodeToString(AggregatorMockController.SELCOM_KEY.getBytes(StandardCharsets.UTF_8)));
        h.put("Digest-Method", "HS256");
        h.put("Timestamp", ts);
        h.put("Signed-Fields", String.join(",", fields.keySet()));
        h.put("Digest", AggregatorMockController.digest(secret, ts, fields));
        return h;
    }
}
