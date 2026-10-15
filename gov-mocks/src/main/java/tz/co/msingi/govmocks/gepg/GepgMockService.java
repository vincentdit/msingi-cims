package tz.co.msingi.govmocks.gepg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static tz.co.msingi.govmocks.gepg.GepgXml.el;
import static tz.co.msingi.govmocks.gepg.GepgXml.text;

/**
 * GePG's side, in memory. A bill submission is checked (signature, required fields) and acknowledged with 7101; the control
 * number follows a moment later as a signed {@code gepgBillSubResp} posted to CIMS, retried until CIMS acknowledges it with
 * 7101. A payment made from the dashboard is posted to CIMS as a signed {@code gepgPmtSpInfo}; a reconciliation request is
 * answered with a signed {@code gepgSpReconcResp} listing that day's payments.
 * <p>
 * Status codes used: 7101 success; 7201 general failure; 7242 invalid bill data; 7303 signature verification failed; 7279
 * duplicate bill (answered with the same control number).
 */
@Service
public class GepgMockService {

    private static final Logger log = LoggerFactory.getLogger(GepgMockService.class);
    static final ZoneId EAT = ZoneId.of("Africa/Dar_es_Salaam");
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public static final class Bill {
        public String billId, spCode, subSpCode, systemId, payerId, payerName, payerPhone, payerEmail, description, currency, controlNo, status;
        public BigDecimal amount, paid = BigDecimal.ZERO;
        public String expiresAt, receivedAt, callback;
        public List<Map<String, Object>> items = new ArrayList<>();
    }

    public static final class Payment {
        public String payRefId, billId, controlNo, spCode, currency, paidAt, channel, pspName, pspTrxId, phone, payerName, delivered;
        public BigDecimal amount, billAmount;
        public boolean unknownToCims;
    }

    public static final class Reconciliation {
        public String requestId, spCode, date, receivedAt, delivered;
        public int transactions;
    }

    private final Map<String, Bill> bills = new ConcurrentHashMap<>();
    private final List<Payment> payments = new CopyOnWriteArrayList<>();
    private final List<Reconciliation> reconciliations = new CopyOnWriteArrayList<>();
    private final AtomicInteger seq = new AtomicInteger(1000);
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2);

    private final GepgCrypto crypto;
    private final RestClient cims;
    private final String controlNumberPath;
    private final String paymentPath;
    private final String reconciliationPath;
    private final long delayMs;
    private volatile boolean rejectBills;
    private volatile boolean strayPaymentInNextRecon;

    public GepgMockService(@Value("${mock.gepg.keystore}") String keystore, @Value("${mock.gepg.keystore-password}") String password,
                           @Value("${mock.gepg.key-alias}") String alias, @Value("${mock.gepg.sp-certificate}") String spCertificate,
                           @Value("${mock.cims-url}") String cimsUrl,
                           @Value("${mock.gepg.control-number-path}") String controlNumberPath,
                           @Value("${mock.gepg.payment-path}") String paymentPath,
                           @Value("${mock.gepg.reconciliation-path}") String reconciliationPath,
                           @Value("${mock.gepg.delay-ms:1500}") long delayMs) {
        this.crypto = GepgCrypto.load(keystore, password, alias, spCertificate);
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        f.setReadTimeout(Duration.ofSeconds(20));
        this.cims = RestClient.builder().baseUrl(cimsUrl).requestFactory(f).build();
        this.controlNumberPath = controlNumberPath;
        this.paymentPath = paymentPath;
        this.reconciliationPath = reconciliationPath;
        this.delayMs = delayMs;
        log.info("[GePG mock] signing with {} (alias {}); CIMS callbacks to {}", keystore, alias, cimsUrl);
    }

    // ------------------------------------------------------------------ inbound from CIMS

    /** A bill submission ({@code gepgBillSubReq}); returns the signed acknowledgement. */
    public String submitBill(String xml) {
        String content = GepgXml.inner(xml, "gepgBillSubReq");
        if (content == null) return ack("gepgBillSubReqAck", 7242);
        if (!crypto.verify(content, GepgXml.signature(xml))) {
            log.warn("[GePG mock] bill submission with an invalid signature refused");
            return ack("gepgBillSubReqAck", 7303);
        }
        Element root = GepgXml.parse(xml);
        Element trx = GepgXml.all(root, "BillTrxInf").stream().findFirst().orElse(null);
        String sp = text(root, "SpCode");
        if (trx == null || sp == null || text(trx, "BillId") == null || text(trx, "BillAmt") == null) return ack("gepgBillSubReqAck", 7242);
        String billId = text(trx, "BillId");
        Bill existing = bills.get(billId);
        if (existing != null) {
            log.info("[GePG mock] duplicate bill {}: control number sent again", billId);
            if (existing.controlNo != null) timer.schedule(() -> sendControlNumber(existing, 0), delayMs, TimeUnit.MILLISECONDS);
            return ack("gepgBillSubReqAck", 7101);
        }
        Bill b = new Bill();
        b.billId = billId;
        b.spCode = sp;
        b.subSpCode = text(trx, "SubSpCode");
        b.systemId = text(trx, "SpSysId");
        b.payerId = text(trx, "PyrId");
        b.payerName = text(trx, "PyrName");
        b.payerPhone = text(trx, "PyrCellNum");
        b.payerEmail = text(trx, "PyrEmail");
        b.description = text(trx, "BillDesc");
        b.currency = Optional.ofNullable(text(trx, "Ccy")).orElse("TZS");
        b.expiresAt = text(trx, "BillExprDt");
        b.receivedAt = now();
        try {
            b.amount = new BigDecimal(text(trx, "BillAmt"));
        } catch (NumberFormatException e) {
            return ack("gepgBillSubReqAck", 7242);
        }
        BigDecimal itemsTotal = BigDecimal.ZERO;
        for (Element it : GepgXml.all(trx, "BillItem")) {
            String gfs = text(it, "GfsCode");
            BigDecimal amt = new BigDecimal(Optional.ofNullable(text(it, "BillItemAmt")).orElse("0"));
            itemsTotal = itemsTotal.add(amt);
            b.items.add(Map.of("ref", String.valueOf(text(it, "BillItemRef")), "gfsCode", String.valueOf(gfs), "amount", amt));
            if (gfs == null) return ack("gepgBillSubReqAck", 7242);
        }
        if (b.items.isEmpty() || itemsTotal.compareTo(b.amount) != 0) {
            log.warn("[GePG mock] bill {} refused: items {} do not add up to {}", billId, itemsTotal, b.amount);
            return ack("gepgBillSubReqAck", 7242);
        }
        if (rejectBills) {
            b.status = "REJECTED";
        } else {
            b.status = "ISSUED";
            // random, so control numbers stay unique in CIMS across restarts of the mock
            b.controlNo = "99" + String.format("%010d", Math.floorMod(random.nextLong(), 10_000_000_000L));
        }
        bills.put(billId, b);
        log.info("[GePG mock] bill {} for {} {} received from {}", billId, b.currency, b.amount, sp);
        timer.schedule(() -> sendControlNumber(b, 0), delayMs, TimeUnit.MILLISECONDS);
        return ack("gepgBillSubReqAck", 7101);
    }

    /** A reconciliation request ({@code gepgSpReconcReq}); the transactions follow by callback. */
    public String requestReconciliation(String xml) {
        String content = GepgXml.inner(xml, "gepgSpReconcReq");
        if (content == null) return ack("gepgSpReconcReqAck", 7242);
        if (!crypto.verify(content, GepgXml.signature(xml))) return ack("gepgSpReconcReqAck", 7303);
        Element root = GepgXml.parse(xml);
        Reconciliation r = new Reconciliation();
        r.requestId = text(root, "SpReconcReqId");
        r.spCode = text(root, "SpCode");
        r.date = text(root, "TnxDt");
        r.receivedAt = now();
        if (r.requestId == null || r.spCode == null || r.date == null) return ack("gepgSpReconcReqAck", 7242);
        reconciliations.add(0, r);
        timer.schedule(() -> sendReconciliation(r, 0), delayMs, TimeUnit.MILLISECONDS);
        return ack("gepgSpReconcReqAck", 7101);
    }

    // ------------------------------------------------------------------ outbound to CIMS

    private void sendControlNumber(Bill b, int attempt) {
        StringBuilder c = new StringBuilder("<gepgBillSubResp><BillTrxInf>");
        c.append(el("BillId", b.billId)).append(el("TrxSts", b.controlNo == null ? "GF" : "GS"))
                .append(el("PayCntrNum", b.controlNo == null ? "0" : b.controlNo))
                .append(el("TrxStsCode", b.controlNo == null ? "7242" : "7101")).append("</BillTrxInf></gepgBillSubResp>");
        Integer code = post(controlNumberPath, c.toString(), "gepgBillSubRespAck");
        b.callback = code == null ? "no answer (attempt " + (attempt + 1) + ")" : "CIMS answered " + code;
        if ((code == null || code != 7101) && attempt < 4) {
            timer.schedule(() -> sendControlNumber(b, attempt + 1), 3, TimeUnit.SECONDS);
        }
    }

    private Integer postPayment(Payment p) {
        String c = "<gepgPmtSpInfo><PymtTrxInf>" + el("TrxId", p.pspTrxId) + el("SpCode", p.spCode) + el("PayRefId", p.payRefId)
                + el("BillId", p.billId) + el("PayCtrNum", p.controlNo) + el("BillAmt", plain(p.billAmount)) + el("PaidAmt", plain(p.amount))
                + el("BillPayOpt", "3") + el("CCy", p.currency) + el("TrxDtTm", p.paidAt) + el("UsdPayChnl", p.channel) + el("PyrCellNum", p.phone)
                + el("PyrName", p.payerName) + el("PyrEmail", "") + el("PspReceiptNumber", p.pspTrxId) + el("PspName", p.pspName)
                + el("CtrAccNum", "0150000000000") + "</PymtTrxInf></gepgPmtSpInfo>";
        Integer code = post(paymentPath, c, "gepgPmtSpInfoAck");
        p.delivered = code == null ? "no answer" : "CIMS answered " + code;
        return code;
    }

    private void sendReconciliation(Reconciliation r, int attempt) {
        List<Payment> day = payments.stream().filter(p -> p.spCode.equals(r.spCode) && p.paidAt.startsWith(r.date)).toList();
        List<Payment> list = new ArrayList<>(day);
        if (strayPaymentInNextRecon) {
            strayPaymentInNextRecon = false;
            Payment stray = new Payment();
            stray.payRefId = "EC" + seq.incrementAndGet() + "X";
            stray.billId = "0-0-0";
            stray.controlNo = "990000000000";
            stray.spCode = r.spCode;
            stray.amount = new BigDecimal("15000");
            stray.currency = "TZS";
            stray.paidAt = r.date + "T10:15:00";
            stray.channel = "MOBILE";
            stray.pspName = "TIGO-PESA";
            stray.pspTrxId = "TP" + seq.incrementAndGet();
            stray.phone = "255700000000";
            stray.unknownToCims = true;
            list.add(stray);
        }
        StringBuilder c = new StringBuilder("<gepgSpReconcResp><ReconcBatchInfo>").append(el("SpReconcReqId", r.requestId)).append(el("SpCode", r.spCode))
                .append(el("SpName", "")).append(el("ReconcStsCode", "7101")).append("</ReconcBatchInfo><ReconcTrans>");
        for (Payment p : list) {
            c.append("<ReconcTrxInf>").append(el("SpBillId", p.billId)).append(el("BillCtrNum", p.controlNo)).append(el("pspTrxId", p.pspTrxId))
                    .append(el("PaidAmt", plain(p.amount))).append(el("CCy", p.currency)).append(el("PayRefId", p.payRefId))
                    .append(el("TrxDtTm", p.paidAt)).append(el("CtrAccNum", "0150000000000")).append(el("UsdPayChnl", p.channel))
                    .append(el("PspName", p.pspName)).append(el("PspCode", "PSP001")).append(el("DptCellNum", p.phone))
                    .append(el("DptName", p.payerName == null ? "" : p.payerName)).append(el("DptEmailAddr", "")).append(el("Remarks", ""))
                    .append("</ReconcTrxInf>");
        }
        c.append("</ReconcTrans></gepgSpReconcResp>");
        r.transactions = list.size();
        Integer code = post(reconciliationPath, c.toString(), "gepgSpReconcRespAck");
        r.delivered = code == null ? "no answer (attempt " + (attempt + 1) + ")" : "CIMS answered " + code;
        if ((code == null || code != 7101) && attempt < 2) timer.schedule(() -> sendReconciliation(r, attempt + 1), 3, TimeUnit.SECONDS);
    }

    /** Posts a signed message to CIMS; returns CIMS's TrxStsCode, or null when there was no usable answer. */
    private Integer post(String path, String content, String ackTag) {
        try {
            String answer = cims.post().uri(path).contentType(new MediaType("application", "xml", java.nio.charset.StandardCharsets.UTF_8))
                    .header("Gepg-Com", "default.sp.in").header("Gepg-Code", "GEPG")
                    .body(crypto.message(content).getBytes(java.nio.charset.StandardCharsets.UTF_8)).retrieve().body(String.class);
            String ack = GepgXml.inner(answer, ackTag);
            if (ack == null) {
                log.warn("[GePG mock] CIMS answered {} without {}", path, ackTag);
                return null;
            }
            if (!crypto.verify(ack, GepgXml.signature(answer))) {
                log.warn("[GePG mock] CIMS's acknowledgement on {} is not signed correctly", path);
                return null;
            }
            String code = text(GepgXml.parse(answer), "TrxStsCode");
            log.info("[GePG mock] {} -> CIMS {}", path, code);
            return code == null ? null : Integer.parseInt(code);
        } catch (Exception e) {
            log.warn("[GePG mock] could not reach CIMS at {}: {}", path, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ developer actions

    /** Pays a bill from the dashboard: the full outstanding amount unless an amount is given. */
    public Payment pay(String billId, BigDecimal amount, String channel) {
        Bill b = bills.get(billId);
        if (b == null) throw new IllegalArgumentException("No bill " + billId);
        if (b.controlNo == null) throw new IllegalArgumentException("Bill " + billId + " has no control number");
        BigDecimal due = b.amount.subtract(b.paid);
        BigDecimal amt = amount == null || amount.signum() <= 0 ? due : amount;
        if (amt.signum() <= 0) throw new IllegalArgumentException("Bill " + billId + " is already paid");
        Payment p = new Payment();
        p.payRefId = "EC" + seq.incrementAndGet() + String.format("%04d", random.nextInt(10_000));
        p.billId = b.billId;
        p.controlNo = b.controlNo;
        p.spCode = b.spCode;
        p.amount = amt;
        p.billAmount = b.amount;
        p.currency = b.currency;
        p.paidAt = OffsetDateTime.now(EAT).format(TS);
        p.channel = channel == null || channel.isBlank() ? "MOBILE" : channel;
        p.pspName = "MOBILE".equals(p.channel) ? "M-PESA" : "CRDB BANK";
        p.pspTrxId = ("MOBILE".equals(p.channel) ? "MP" : "CR") + seq.incrementAndGet();
        p.phone = b.payerPhone;
        p.payerName = b.payerName;
        payments.add(0, p);
        b.paid = b.paid.add(amt);
        b.status = b.paid.compareTo(b.amount) >= 0 ? "PAID" : "PARTIAL";
        postPayment(p);
        return p;
    }

    /** Sends a payment notification again (GePG resends until acknowledged; CIMS must not post it twice). */
    public Payment resend(String payRefId) {
        Payment p = payments.stream().filter(x -> x.payRefId.equals(payRefId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No payment " + payRefId));
        postPayment(p);
        return p;
    }

    public List<Bill> bills() {
        return bills.values().stream().sorted(Comparator.comparing((Bill b) -> b.receivedAt).reversed()).toList();
    }

    public List<Payment> payments() {
        return payments;
    }

    public List<Reconciliation> reconciliations() {
        return reconciliations;
    }

    public boolean rejectBills() {
        return rejectBills;
    }

    public void setRejectBills(boolean on) {
        rejectBills = on;
    }

    public void setStrayPayment(boolean on) {
        strayPaymentInNextRecon = on;
    }

    public boolean strayPayment() {
        return strayPaymentInNextRecon;
    }

    public void reset() {
        bills.clear();
        payments.clear();
        reconciliations.clear();
    }

    public String ack(String tag, int code) {
        return crypto.message("<" + tag + ">" + el("TrxStsCode", code) + "</" + tag + ">");
    }

    public LocalDate today() {
        return LocalDate.now(EAT);
    }

    private static String now() {
        return OffsetDateTime.now(EAT).format(TS);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
