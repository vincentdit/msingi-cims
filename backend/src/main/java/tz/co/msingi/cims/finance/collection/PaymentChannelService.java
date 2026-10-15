package tz.co.msingi.cims.finance.collection;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.tenancy.Tenant;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.platform.tenancy.TenantRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The accounts a private or faith-based institution is paid into (mobile money and banks), kept by the Bursar. Public institutions
 * collect through GePG and keep none.
 */
@Service
@RequiredArgsConstructor
public class PaymentChannelService {

    private final PaymentChannelRepository channels;
    private final TenantRepository tenants;
    private final AuditService audit;

    public record ChannelRequest(@NotNull PaymentProvider provider, @NotBlank @Size(max = 150) String accountName,
                                 @NotBlank @Size(max = 40) String accountNumber, @Size(max = 100) String branch,
                                 @Size(max = 500) String instructions, Boolean active, Integer sortOrder) {
    }

    public record ChannelView(Long id, String provider, String label, String operator, String kind, String ussd, String accountName,
                              String accountNumber, String branch, String instructions, boolean active, int sortOrder) {
    }

    /** How this institution is paid: GEPG (control numbers) or COLLECTION (CIMS references into its own accounts). */
    public record PaymentSetup(String mode, String ownership, List<ChannelView> channels, List<PaymentProvider.View> providers) {
    }

    /** What payers see in the portals. */
    public record PublicPayment(String mode, List<ChannelView> channels) {
    }

    @Transactional(readOnly = true)
    public boolean usesGepg() {
        return tenant().usesGepg();
    }

    @Transactional(readOnly = true)
    public PaymentSetup setup() {
        Tenant t = tenant();
        return new PaymentSetup(t.usesGepg() ? "GEPG" : "COLLECTION", t.getOwnership(),
                channels.findAllByOrderBySortOrderAscIdAsc().stream().map(PaymentChannelService::view).toList(), PaymentProvider.all());
    }

    @Transactional(readOnly = true)
    public PublicPayment forPayers() {
        Tenant t = tenant();
        return new PublicPayment(t.usesGepg() ? "GEPG" : "COLLECTION",
                t.usesGepg() ? List.of() : channels.findByActiveTrueOrderBySortOrderAscIdAsc().stream().map(PaymentChannelService::view).toList());
    }

    /** Short list for SMS: "M-Pesa 123456, CRDB Bank 0150…". */
    @Transactional(readOnly = true)
    public String summary() {
        Map<String, String> byLabel = new LinkedHashMap<>();
        for (PaymentChannel c : channels.findByActiveTrueOrderBySortOrderAscIdAsc()) {
            byLabel.putIfAbsent(c.getProvider().label(), c.getProvider().label() + " " + c.getAccountNumber());
        }
        return byLabel.isEmpty() ? "mobile money or bank" : byLabel.values().stream().limit(4).collect(Collectors.joining(", "));
    }

    @Transactional
    public ChannelView create(ChannelRequest req) {
        requireCollection();
        String number = clean(req.accountNumber());
        check(req.provider(), number);
        if (channels.existsByProviderAndAccountNumberIgnoreCase(req.provider(), number)) {
            throw new BusinessRuleException("CHANNEL_EXISTS", req.provider().label() + " " + number + " is already listed");
        }
        PaymentChannel c = new PaymentChannel();
        apply(c, req, number);
        c.setSortOrder(req.sortOrder() != null ? req.sortOrder() : channels.findAll().size() + 1);
        channels.save(c);
        audit.record("CREATE", "PaymentChannel", c.getId(), Map.of("provider", c.getProvider().name(), "account", number));
        return view(c);
    }

    @Transactional
    public ChannelView update(Long id, ChannelRequest req) {
        requireCollection();
        PaymentChannel c = channels.findById(id).orElseThrow(() -> new NotFoundException("Payment method", id));
        String number = clean(req.accountNumber());
        check(req.provider(), number);
        boolean renamed = c.getProvider() != req.provider() || !c.getAccountNumber().equalsIgnoreCase(number);
        if (renamed && channels.existsByProviderAndAccountNumberIgnoreCase(req.provider(), number)) {
            throw new BusinessRuleException("CHANNEL_EXISTS", req.provider().label() + " " + number + " is already listed");
        }
        Map<String, Object> before = Map.of("provider", c.getProvider().name(), "account", c.getAccountNumber(), "active", c.isActive());
        apply(c, req, number);
        if (req.sortOrder() != null) c.setSortOrder(req.sortOrder());
        audit.record("UPDATE", "PaymentChannel", c.getId(), Map.of("before", before,
                "after", Map.of("provider", c.getProvider().name(), "account", number, "active", c.isActive())));
        return view(c);
    }

    @Transactional
    public void delete(Long id) {
        PaymentChannel c = channels.findById(id).orElseThrow(() -> new NotFoundException("Payment method", id));
        channels.delete(c);
        audit.record("DELETE", "PaymentChannel", id, Map.of("provider", c.getProvider().name(), "account", c.getAccountNumber()));
    }

    private void apply(PaymentChannel c, ChannelRequest req, String number) {
        c.setProvider(req.provider());
        c.setKind(req.provider().kind());
        c.setAccountName(req.accountName().trim());
        c.setAccountNumber(number);
        c.setBranch(blank(req.branch()));
        c.setInstructions(blank(req.instructions()));
        if (req.active() != null) c.setActive(req.active());
    }

    private static void check(PaymentProvider p, String number) {
        if (p.kind() == PaymentProvider.Kind.MOBILE_MONEY && !number.matches("\\d{4,12}")) {
            throw new BusinessRuleException("INVALID_BUSINESS_NUMBER", "A mobile money business (pay-bill) number has 4 to 12 digits");
        }
        if (p.kind() == PaymentProvider.Kind.BANK && !number.matches("[A-Za-z0-9]{6,30}")) {
            throw new BusinessRuleException("INVALID_ACCOUNT_NUMBER", "A bank account number has 6 to 30 letters and digits, without spaces");
        }
    }

    private void requireCollection() {
        if (tenant().usesGepg()) {
            throw new BusinessRuleException("USES_GEPG", "Public institutions are paid through GePG control numbers, so no payment accounts are listed");
        }
    }

    private Tenant tenant() {
        return tenants.findById(TenantContext.requireTenantId()).orElseThrow(() -> new NotFoundException("Institution not found"));
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\s-]", "");
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static ChannelView view(PaymentChannel c) {
        PaymentProvider p = c.getProvider();
        return new ChannelView(c.getId(), p.name(), p.label(), p.operator(), p.kind().name(), p.ussd(), c.getAccountName(), c.getAccountNumber(),
                c.getBranch(), c.getInstructions(), c.isActive(), c.getSortOrder());
    }
}
