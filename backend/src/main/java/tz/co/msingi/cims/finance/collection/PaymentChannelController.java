package tz.co.msingi.cims.finance.collection;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.finance.collection.PaymentChannelService.ChannelRequest;
import tz.co.msingi.cims.finance.collection.PaymentChannelService.ChannelView;
import tz.co.msingi.cims.finance.collection.PaymentChannelService.PaymentSetup;

/** Payment methods of a private or faith-based institution: its mobile money business numbers and bank accounts. */
@Tag(name = "Finance — payment methods")
@RestController
@RequestMapping("/api/finance/payment-channels")
@RequiredArgsConstructor
public class PaymentChannelController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','BURSAR','ACCOUNTANT','MANAGEMENT_VIEWER','AUDITOR')";
    private static final String MANAGE = "hasAnyRole('INSTITUTION_ADMIN','BURSAR')";

    private final PaymentChannelService service;

    @Operation(summary = "How fees are collected (GePG or the institution's own accounts) and the accounts listed")
    @GetMapping
    @PreAuthorize(READ)
    public PaymentSetup setup() {
        return service.setup();
    }

    @PostMapping
    @PreAuthorize(MANAGE)
    public ChannelView create(@Valid @RequestBody ChannelRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize(MANAGE)
    public ChannelView update(@PathVariable Long id, @Valid @RequestBody ChannelRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(MANAGE)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
