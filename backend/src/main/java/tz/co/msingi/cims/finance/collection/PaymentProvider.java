package tz.co.msingi.cims.finance.collection;

import java.util.Arrays;
import java.util.List;

/**
 * Where a private or faith-based institution can be paid: Tanzania's mobile money services and banks. Each channel the
 * institution lists names one provider and its own business number or account number.
 */
public enum PaymentProvider {

    MPESA("M-Pesa", "Vodacom", Kind.MOBILE_MONEY, "*150*00#"),
    MIXX_BY_YAS("Mixx by Yas", "Yas (formerly Tigo Pesa)", Kind.MOBILE_MONEY, "*150*01#"),
    AIRTEL_MONEY("Airtel Money", "Airtel", Kind.MOBILE_MONEY, "*150*60#"),
    HALOPESA("HaloPesa", "Halotel", Kind.MOBILE_MONEY, "*150*88#"),
    EZYPESA("Ezy Pesa", "Zantel", Kind.MOBILE_MONEY, "*150*02#"),
    TPESA("T-Pesa", "TTCL", Kind.MOBILE_MONEY, "*150*71#"),
    CRDB("CRDB Bank", "CRDB Bank Plc", Kind.BANK, null),
    NMB("NMB Bank", "NMB Bank Plc", Kind.BANK, null),
    NBC("NBC Bank", "National Bank of Commerce", Kind.BANK, null),
    STANBIC("Stanbic Bank", "Stanbic Bank Tanzania", Kind.BANK, null),
    EXIM("Exim Bank", "Exim Bank Tanzania", Kind.BANK, null),
    ABSA("Absa Bank", "Absa Bank Tanzania", Kind.BANK, null),
    AZANIA("Azania Bank", "Azania Bank", Kind.BANK, null),
    DTB("DTB", "Diamond Trust Bank", Kind.BANK, null),
    EQUITY("Equity Bank", "Equity Bank Tanzania", Kind.BANK, null),
    KCB("KCB Bank", "KCB Bank Tanzania", Kind.BANK, null),
    TCB("TCB Bank", "Tanzania Commercial Bank", Kind.BANK, null),
    PBZ("PBZ", "People's Bank of Zanzibar", Kind.BANK, null),
    AMANA("Amana Bank", "Amana Bank", Kind.BANK, null),
    MKOMBOZI("Mkombozi Bank", "Mkombozi Commercial Bank", Kind.BANK, null),
    OTHER_MOBILE("Other mobile money", null, Kind.MOBILE_MONEY, null),
    OTHER_BANK("Other bank", null, Kind.BANK, null);

    public enum Kind { MOBILE_MONEY, BANK }

    private final String label;
    private final String operator;
    private final Kind kind;
    private final String ussd;

    PaymentProvider(String label, String operator, Kind kind, String ussd) {
        this.label = label;
        this.operator = operator;
        this.kind = kind;
        this.ussd = ussd;
    }

    public String label() {
        return label;
    }

    public String operator() {
        return operator;
    }

    public Kind kind() {
        return kind;
    }

    /** The USSD menu code payers dial, for mobile money. */
    public String ussd() {
        return ussd;
    }

    public record View(String code, String label, String operator, String kind, String ussd) {
    }

    public View view() {
        return new View(name(), label, operator, kind.name(), ussd);
    }

    public static List<View> all() {
        return Arrays.stream(values()).map(PaymentProvider::view).toList();
    }
}
