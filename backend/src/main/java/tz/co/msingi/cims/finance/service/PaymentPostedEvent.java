package tz.co.msingi.cims.finance.service;

/** Published (within the payment transaction) after any payment is posted; clearance can be re-evaluated. */
public record PaymentPostedEvent(Long invoiceId, String payerType, Long payerId, Long paymentId) {
}
