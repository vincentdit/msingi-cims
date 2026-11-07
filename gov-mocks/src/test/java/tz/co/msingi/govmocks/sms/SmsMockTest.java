package tz.co.msingi.govmocks.sms;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** The SMS provider mock: NextSMS and Beem sending under registered names only, reports asked for and posted, balances. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"mock.sms.deliver-seconds=1"})
class SmsMockTest {

    @LocalServerPort
    int port;

    RestClient http() {
        return RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    }

    static String basic(String u, String p) {
        return "Basic " + Base64.getEncoder().encodeToString((u + ":" + p).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsUnderRegisteredNamesAndReportsDelivery() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        HttpServer cims = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cims.createContext("/", ex -> {
            got.add(ex.getRequestURI().getPath() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"status\":\"ACCEPTED\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        cims.start();
        try {
            String base = "http://127.0.0.1:" + cims.getAddress().getPort();
            http().post().uri("/mock/sms/reset").retrieve().toBodilessEntity();
            http().post().uri("/mock/sms/callback?provider=NEXTSMS&url=" + base + "/api/integrations/sms/nextsms/tok").retrieve().toBodilessEntity();
            String next = "Bearer " + SmsMockController.NEXT_TOKEN;

            // wrong password refused; an unregistered name rejected; a registered one accepted
            int bad = http().get().uri("/nextsms/api/v2/balance").header("Authorization", "Bearer nope")
                    .exchange((req, res) -> res.getStatusCode().value());
            assertThat(bad).isEqualTo(401);
            Map<String, Object> r = http().post().uri("/nextsms/api/sms/v2/text/single").header("Authorization", next).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("from", "NOBODY", "to", "255712345678", "text", "x", "reference", "1-1")).retrieve().body(Map.class);
            assertThat(r.toString()).contains("groupName=REJECTED", "REJECTED_SOURCE").doesNotContain("messageId");
            r = http().post().uri("/nextsms/api/sms/v2/text/single").header("Authorization", next).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("from", "BAHARI", "to", "255712345678", "text", "Karibu", "reference", "1-2")).retrieve().body(Map.class);
            String id = (String) ((Map<String, Object>) ((List<?>) r.get("messages")).get(0)).get("messageId");
            Thread.sleep(2000);
            Map<String, Object> rep = http().get().uri("/nextsms/api/v2/reports?messageId=" + id).header("Authorization", next).retrieve().body(Map.class);
            assertThat(rep.toString()).contains("groupName=DELIVERED");
            assertThat(got).anyMatch(g -> g.startsWith("/api/integrations/sms/nextsms/tok") && g.contains(id) && g.contains("DELIVERED"));

            // Beem: to a number ending 000 the report says undelivered
            String beem = basic(SmsMockController.BEEM_KEY, SmsMockController.BEEM_SECRET);
            Map<String, Object> b = http().post().uri("/beem/v1/send").header("Authorization", beem).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("source_addr", "BIHAS", "encoding", 0, "message", "Hi", "recipients", List.of(Map.of("recipient_id", 9, "dest_addr", "255712000000"))))
                    .retrieve().body(Map.class);
            assertThat(b.get("successful")).isEqualTo(true);
            Thread.sleep(2000);
            List<Map<String, Object>> dl = http().get().uri("/beem-dlr/public/v1/delivery-reports?dest_addr=255712000000&request_id=" + b.get("request_id"))
                    .header("Authorization", beem).retrieve().body(List.class);
            assertThat(dl).singleElement().satisfies(x -> assertThat(x.get("status")).isEqualTo("UNDELIVERED"));
            assertThat(http().get().uri("/beem/public/v1/vendors/balance").header("Authorization", beem).retrieve().body(Map.class).toString())
                    .contains("credit_balance");
        } finally {
            cims.stop(0);
        }
    }
}
