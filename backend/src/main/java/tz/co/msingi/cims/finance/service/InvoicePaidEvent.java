package tz.co.msingi.cims.finance.service;

/** Published (within the payment transaction) when an invoice becomes fully paid. */
public record InvoicePaidEvent(Long invoiceId, String purpose, Long referenceId) {
}
