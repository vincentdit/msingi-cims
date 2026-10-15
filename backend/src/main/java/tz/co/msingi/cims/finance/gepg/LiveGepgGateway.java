package tz.co.msingi.cims.finance.gepg;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.integration.settings.IntegrationSettingsService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static tz.co.msingi.cims.finance.gepg.GepgXml.el;

/**
 * GePG over HTTPS in the shape of the GePG service provider specification (FR-INT-03): XML messages
 * {@code <Gepg><content/><gepgSignature/></Gepg>} signed SHA1withRSA with the institution's key, sent with the
 * {@code Gepg-Com} and {@code Gepg-Code} headers.
 * <ul>
 *   <li><b>Bill</b> ({@code gepgBillSubReq}): acknowledged at once with 7101; the control number follows by callback
 *       ({@code gepgBillSubResp} to {@code /api/integrations/gepg/control-number}), so the control number stays REQUESTED until then.</li>
 *   <li><b>Reconciliation</b> ({@code gepgSpReconcReq}): acknowledged at once; GePG posts the day's transactions to
 *       {@code /api/integrations/gepg/reconciliation}.</li>
 * </ul>
 * The SP code, sub SP code, system id and default GFS code are the institution's (Integrations page); the keys are the
 * deployment's ({@link GepgKeys}). Select with {@code CIMS_GEPG_MODE=live}; the {@code gov-mocks} project answers for development.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cims.gepg", name = "mode", havingValue = "live")
public class LiveGepgGateway implements GepgGateway {

    static final ZoneId EAT = ZoneId.of("Africa/Dar_es_Salaam");
    static final MediaType XML_UTF8 = new MediaType("application", "xml", java.nio.charset.StandardCharsets.UTF_8);
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final RestClient http;
    private final GepgKeys keys;
    private final IntegrationSettingsService settings;
    private final String billPath;
    private final String reconPath;

    public LiveGepgGateway(GepgKeys keys, IntegrationSettingsService settings,
                           @Value("${cims.gepg.live.base-url}") String baseUrl,
                           @Value("${cims.gepg.live.bill-path:/api/bill/sigqrequest}") String billPath,
                           @Value("${cims.gepg.live.reconciliation-path:/api/reconciliations/sig_sp_qrequest}") String reconPath,
                           @Value("${cims.gepg.live.connect-timeout-seconds:8}") int connectTimeout,
                           @Value("${cims.gepg.live.read-timeout-seconds:30}") int readTimeout) {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeout)).build());
        f.setReadTimeout(Duration.ofSeconds(readTimeout));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
        this.keys = keys;
        this.settings = settings;
        this.billPath = billPath;
        this.reconPath = reconPath;
        log.info("GePG: live at {}", baseUrl);
    }

    @Override
    public ControlNumberResponse submitBill(BillRequest bill) {
        IntegrationSettingsService.Settings s;
        try {
            s = settings.requireGepg();
        } catch (BusinessRuleException e) {
            return ControlNumberResponse.error(e.getMessage());
        }
        StringBuilder items = new StringBuilder("<BillItems>");
        BigDecimal sum = BigDecimal.ZERO;
        for (BillItem it : bill.items()) {
            String gfs = it.gfsCode() != null ? it.gfsCode() : s.gepgGfsCode();
            if (gfs == null) return ControlNumberResponse.error("Fee item " + it.ref() + " has no GFS code and no default GFS code is set (Integrations)");
            items.append("<BillItem>").append(el("BillItemRef", it.ref())).append(el("UseItemRefOnPay", "N")).append(el("BillItemAmt", money(it.amount())))
                    .append(el("BillItemEqvAmt", money(it.amount()))).append(el("BillItemMiscAmt", "0")).append(el("GfsCode", gfs)).append("</BillItem>");
            sum = sum.add(it.amount());
        }
        if (bill.items().isEmpty()) {
            if (s.gepgGfsCode() == null) return ControlNumberResponse.error("No default GFS code is set for GePG (Integrations)");
            items.append("<BillItem>").append(el("BillItemRef", "FEES")).append(el("UseItemRefOnPay", "N")).append(el("BillItemAmt", money(bill.amount())))
                    .append(el("BillItemEqvAmt", money(bill.amount()))).append(el("BillItemMiscAmt", "0")).append(el("GfsCode", s.gepgGfsCode())).append("</BillItem>");
            sum = bill.amount();
        }
        items.append("</BillItems>");
        if (sum.compareTo(bill.amount()) != 0) return ControlNumberResponse.error("The bill items do not add up to the bill amount");
        String now = OffsetDateTime.now(EAT).format(TS);
        String content = "<gepgBillSubReq><BillHdr>" + el("SpCode", s.gepgSpCode()) + el("RtrRespFlg", "true") + "</BillHdr><BillTrxInf>"
                + el("BillId", bill.billId()) + el("SubSpCode", s.gepgSubSpCode()) + el("SpSysId", s.gepgSystemId())
                + el("BillAmt", money(bill.amount())) + el("MiscAmt", "0")
                + el("BillExprDt", bill.expiresAt() == null ? "" : bill.expiresAt().atZoneSameInstant(EAT).format(TS))
                + el("PyrId", bill.payerId() == null ? bill.billId() : bill.payerId()) + el("PyrName", cut(bill.payerName(), 100))
                + el("BillDesc", cut(bill.description(), 200)) + el("BillGenDt", now) + el("BillGenBy", "CIMS") + el("BillApprBy", cut(CurrentUser.name(), 50))
                + el("PyrCellNum", phone(bill.payerPhone())) + el("PyrEmail", bill.payerEmail() == null ? "" : bill.payerEmail())
                + el("Ccy", bill.currency() == null ? "TZS" : bill.currency()) + el("BillEqvAmt", money(bill.amount()))
                + el("RemFlag", "true") + el("BillPayOpt", "3") + items + "</BillTrxInf></gepgBillSubReq>";
        String answer;
        try {
            answer = post(billPath, content, s.gepgSpCode());
        } catch (RestClientException e) {
            log.warn("GePG: bill {} not delivered: {}", bill.billId(), e.getMessage());
            throw new GepgUnavailableException("GePG could not be reached. The control number can be requested again shortly.", e);
        }
        int code = ack(answer, "gepgBillSubReqAck");
        if (code == 7101) {
            log.info("GePG: bill {} accepted; control number to follow", bill.billId());
            return ControlNumberResponse.pending();
        }
        log.warn("GePG: bill {} refused with {}", bill.billId(), code);
        return ControlNumberResponse.error("GePG refused the bill (" + code + " " + describe(code) + ")");
    }

    @Override
    public boolean requestReconciliation(String requestId, LocalDate date) {
        IntegrationSettingsService.Settings s = settings.requireGepg();
        String content = "<gepgSpReconcReq>" + el("SpReconcReqId", requestId) + el("SpCode", s.gepgSpCode()) + el("SpSysId", s.gepgSystemId())
                + el("TnxDt", date.toString()) + el("ReconcOpt", "1") + "</gepgSpReconcReq>";
        String answer;
        try {
            answer = post(reconPath, content, s.gepgSpCode());
        } catch (RestClientException e) {
            throw new GepgUnavailableException("GePG could not be reached. Please try again shortly, or import GePG's file.", e);
        }
        int code = ack(answer, "gepgSpReconcReqAck");
        if (code != 7101) throw new BusinessRuleException("GEPG_REFUSED", "GePG refused the reconciliation request (" + code + " " + describe(code) + ")");
        return true;
    }

    private String post(String path, String content, String spCode) {
        // sent as UTF-8 bytes: the signature covers exactly these bytes (a String body would go out as ISO-8859-1)
        return http.post().uri(path).contentType(XML_UTF8).accept(MediaType.APPLICATION_XML, MediaType.TEXT_XML)
                .header("Gepg-Com", "default.sp.in").header("Gepg-Code", spCode)
                .body(keys.signed(content).getBytes(java.nio.charset.StandardCharsets.UTF_8)).retrieve().body(String.class);
    }

    /** The status code of GePG's acknowledgement, after checking GePG signed it. */
    private int ack(String answer, String tag) {
        if (GepgXml.inner(answer, tag) == null) throw new GepgUnavailableException("GePG gave an answer CIMS could not read. Please try again.", null);
        if (!keys.fromGepg(answer, tag)) throw new GepgUnavailableException("GePG's answer is not signed with GePG's certificate; it was ignored.", null);
        String c = GepgXml.text(GepgXml.parse(answer), "TrxStsCode");
        try {
            return Integer.parseInt(c == null ? "" : c.split(";")[0].trim());
        } catch (NumberFormatException e) {
            return 7201;
        }
    }

    static String describe(int code) {
        return Map.of(7101, "success", 7201, "failure", 7242, "invalid bill data", 7303, "signature not valid", 7279, "duplicate bill")
                .getOrDefault(code, "see the GePG status codes");
    }

    static String money(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** GePG wants the payer's number as 255XXXXXXXXX. */
    static String phone(String p) {
        if (p == null) return "";
        String d = p.replaceAll("\\D", "");
        if (d.startsWith("0") && d.length() == 10) return "255" + d.substring(1);
        return d;
    }

    private static String cut(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max);
    }
}
