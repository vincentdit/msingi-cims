package tz.co.msingi.cims.notification.sms;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The NextSMS and Beem adapters against a stand-in server: what they send, and how they read the answers and reports. */
class SmsClientsTest {

    SmsStandIn p;

    @BeforeEach
    void start() throws Exception {
        p = new SmsStandIn();
    }

    @AfterEach
    void stop() {
        p.close();
    }

    static String basic(String u, String pw) {
        return "Basic " + Base64.getEncoder().encodeToString((u + ":" + pw).getBytes(StandardCharsets.UTF_8));
    }

    SmsProviderClient.Account nextsms(boolean live) {
        return new SmsProviderClient.Account(SmsProvider.NEXTSMS, live, Map.of("username", "msingi"), Map.of("password", "pw-1"), p.base, null);
    }

    SmsProviderClient.Account beem() {
        return new SmsProviderClient.Account(SmsProvider.BEEM, true, Map.of("apiKey", "key-1"), Map.of("secretKey", "sec-1"), p.base, p.base + "/dlr");
    }

    @Test
    void nextSmsSendsUnderTheSenderNameAndReadsReports() {
        NextSmsClient c = new NextSmsClient();
        p.answers.put("/api/sms/v1/test/text/single", """
                {"messages":[{"to":"255712345678","status":{"groupId":18,"groupName":"PENDING","id":51,"name":"ENROUTE (SENT)"},"messageId":"NX-1001","smsCount":1}]}""");
        var sent = c.send(nextsms(false), "255712345678", "Karibu BIHAS", "BAHARI", "12-345", 345);
        assertThat(sent.accepted()).isTrue();
        assertThat(sent.providerRef()).isEqualTo("NX-1001");
        assertThat(p.last()).startsWith("POST /api/sms/v1/test/text/single Authorization=" + basic("msingi", "pw-1"))
                .contains("\"from\":\"BAHARI\"", "\"to\":\"255712345678\"", "\"text\":\"Karibu BIHAS\"", "\"reference\":\"12-345\"");

        // live: the real address; a refusal is reported with the provider's words
        p.answers.put("/api/sms/v1/text/single", """
                {"messages":[{"to":"255712345678","status":{"groupName":"REJECTED","description":"Sender name not registered"},"messageId":"NX-1002"}]}""");
        var refused = c.send(nextsms(true), "255712345678", "Hello", "UNKNOWN", "12-346", 346);
        assertThat(refused.accepted()).isFalse();
        assertThat(refused.error()).contains("Sender name not registered");
        // as NextSMS really answers an unregistered sender name: HTTP 200, REJECTED_SOURCE and no message id
        p.answers.put("/api/sms/v1/text/single", """
                {"messages":[{"to":"255677444100","status":{"groupId":19,"groupName":"REJECTED","id":56,"name":"REJECTED_SOURCE",
                "description":"Sender ID in the request not registered on your account."}}],"sendReference":"12-348"}""");
        assertThat(c.send(nextsms(true), "255677444100", "Hello", "MSINGI", "12-348", 348).error())
                .startsWith("Refused by NextSMS: Sender ID in the request not registered on your account.")
                .contains("MSINGI is not");
        p.statuses.put("/api/sms/v1/text/single", 401);
        p.answers.put("/api/sms/v1/text/single", "{\"message\":\"Invalid credentials\"}");
        assertThat(c.send(nextsms(true), "255712345678", "Hello", "BAHARI", "12-347", 347).error()).isEqualTo("Invalid credentials (HTTP 401)");

        p.answers.put("/api/sms/v1/reports", """
                {"results":[{"messageId":"NX-1001","to":"255712345678","status":{"groupName":"DELIVERED","name":"DELIVERED_TO_HANDSET"}}]}""");
        assertThat(c.status(nextsms(true), "NX-1001", "255712345678")).get().satisfies(r -> assertThat(r.status()).isEqualTo("DELIVERED"));
        assertThat(p.last()).startsWith("GET /api/sms/v1/reports?messageId=NX-1001");
        p.answers.put("/api/sms/v1/reports", "{\"results\":[{\"messageId\":\"NX-1001\",\"status\":{\"groupName\":\"PENDING\"}}]}");
        assertThat(c.status(nextsms(true), "NX-1001", "255712345678")).isEmpty();

        p.answers.put("/api/sms/v1/balance", "{\"sms_balance\":4520}");
        assertThat(c.balance(nextsms(true))).isEqualTo("4520 SMS");

        var reports = c.parseNotification("""
                {"results":[{"messageId":"NX-1","to":"255712345678","status":{"groupName":"UNDELIVERABLE","name":"UNDELIVERABLE_NOT_DELIVERED","description":"Handset off"}}]}""");
        assertThat(reports).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo("FAILED");
            assertThat(r.error()).isEqualTo("Handset off");
        });
    }

    @Test
    void beemSendsWithTheMessageNumberAndReadsReportsFromItsReportAddress() {
        BeemClient c = new BeemClient();
        p.answers.put("/v1/send", "{\"successful\":true,\"request_id\":778899,\"code\":100,\"message\":\"Message Submitted Successfully\",\"valid\":1,\"invalid\":0}");
        var sent = c.send(beem(), "255712345678", "Ada imelipwa", "BAHARI", "12-345", 345);
        assertThat(sent.accepted()).isTrue();
        assertThat(sent.providerRef()).isEqualTo("778899");
        assertThat(p.last()).startsWith("POST /v1/send Authorization=" + basic("key-1", "sec-1"))
                .contains("\"source_addr\":\"BAHARI\"", "\"message\":\"Ada imelipwa\"", "\"recipient_id\":345", "\"dest_addr\":\"255712345678\"");

        p.answers.put("/v1/send", "{\"successful\":false,\"code\":111,\"message\":\"Invalid Sender Id\"}");
        assertThat(c.send(beem(), "255712345678", "x", "NOPE", "12-1", 1).error()).contains("Invalid Sender Id");

        p.answers.put("/dlr/public/v1/delivery-reports", "[{\"dest_addr\":\"255712345678\",\"status\":\"UNDELIVERED\",\"request_id\":778899}]");
        assertThat(c.status(beem(), "778899", "255712345678")).get().satisfies(r -> assertThat(r.status()).isEqualTo("FAILED"));
        assertThat(p.last()).startsWith("GET /dlr/public/v1/delivery-reports?dest_addr=255712345678&request_id=778899");

        p.answers.put("/public/v1/vendors/balance", "{\"data\":{\"credit_balance\":15000}}");
        assertThat(c.balance(beem())).isEqualTo("15000 credits");
        p.statuses.put("/public/v1/vendors/balance", 401);
        p.answers.put("/public/v1/vendors/balance", "{\"data\":{\"message\":\"Invalid api_key or secret_key\"}}");
        assertThatThrownBy(() -> c.balance(beem())).hasMessageContaining("Invalid api_key");

        assertThat(c.parseNotification("{\"status\":\"DELIVERED\",\"dest_addr\":\"255712345678\",\"request_id\":\"778899\",\"recipient_id\":\"345\"}"))
                .singleElement().satisfies(r -> {
                    assertThat(r.providerRef()).isEqualTo("778899");
                    assertThat(r.status()).isEqualTo("DELIVERED");
                });
    }
}
