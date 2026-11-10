package tz.co.msingi.cims.assets;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.common.web.UploadedFile;

import java.time.LocalDate;
import java.util.List;

/** Assets and stores (FR-AST-01..04). */
@Tag(name = "Assets and stores")
@RestController
@RequiredArgsConstructor
public class AssetController {

    static final String WRITE = "hasAnyRole('INSTITUTION_ADMIN','STORES_OFFICER')";
    static final String READ = "hasAnyRole('INSTITUTION_ADMIN','STORES_OFFICER','BURSAR','ACCOUNTANT','MANAGEMENT_VIEWER','AUDITOR')";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final AssetService assets;
    private final StoreService stores;

    public record VerificationRequest(String name, Long campusId) {
    }

    public record ScanRequest(String tagNo, String condition) {
    }

    public record CloseRequest(boolean markMissingLost) {
    }

    @GetMapping("/api/assets/overview")
    @PreAuthorize(READ)
    public AssetService.Overview overview() {
        return assets.overview();
    }

    @GetMapping("/api/assets")
    @PreAuthorize(READ)
    public List<AssetService.AssetView> list(@RequestParam(required = false) String q, @RequestParam(required = false) String category,
                                             @RequestParam(required = false) Long campusId, @RequestParam(required = false) Long departmentId,
                                             @RequestParam(required = false) String status) {
        return assets.list(q, category, campusId, departmentId, status);
    }

    @GetMapping("/api/assets/{id}")
    @PreAuthorize(READ)
    public AssetService.AssetDetail get(@PathVariable Long id) {
        return assets.get(id);
    }

    @GetMapping("/api/assets/by-tag")
    @PreAuthorize(READ)
    public AssetService.AssetDetail byTag(@RequestParam String tag) {
        return assets.byTag(tag);
    }

    @PostMapping("/api/assets")
    @PreAuthorize(WRITE)
    public AssetService.AssetDetail add(@RequestBody AssetService.AssetRequest r) {
        return assets.add(r);
    }

    @PutMapping("/api/assets/{id}")
    @PreAuthorize(WRITE)
    public AssetService.AssetDetail update(@PathVariable Long id, @RequestBody AssetService.AssetRequest r) {
        return assets.update(id, r);
    }

    @PostMapping("/api/assets/{id}/move")
    @PreAuthorize(WRITE)
    public AssetService.AssetDetail move(@PathVariable Long id, @RequestBody AssetService.MoveRequest r) {
        return assets.move(id, r);
    }

    @PostMapping("/api/assets/{id}/condition")
    @PreAuthorize(WRITE)
    public AssetService.AssetDetail condition(@PathVariable Long id, @RequestBody AssetService.ConditionRequest r) {
        return assets.setCondition(id, r);
    }

    @PostMapping("/api/assets/{id}/dispose")
    @PreAuthorize(WRITE)
    public AssetService.AssetDetail dispose(@PathVariable Long id, @RequestBody AssetService.DisposeRequest r) {
        return assets.dispose(id, r);
    }

    @GetMapping("/api/assets/register")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> register(@RequestParam(required = false) String status) {
        return UploadedFile.download("asset-register-" + LocalDate.now() + ".xlsx", XLSX, assets.register(status));
    }

    // ------------------------------------------------------------------ verification

    @GetMapping("/api/asset-verifications")
    @PreAuthorize(READ)
    public List<AssetService.VerificationView> verifications() {
        return assets.verifications();
    }

    @PostMapping("/api/asset-verifications")
    @PreAuthorize(WRITE)
    public AssetService.VerificationDetail start(@RequestBody VerificationRequest r) {
        return assets.startVerification(r.name(), r.campusId());
    }

    @GetMapping("/api/asset-verifications/{id}")
    @PreAuthorize(READ)
    public AssetService.VerificationDetail verification(@PathVariable Long id) {
        return assets.verification(id);
    }

    @PutMapping("/api/asset-verification-lines/{id}")
    @PreAuthorize(WRITE)
    public AssetService.VerificationLine check(@PathVariable Long id, @RequestBody AssetService.CheckRequest r) {
        return assets.check(id, r);
    }

    @PostMapping("/api/asset-verifications/{id}/scan")
    @PreAuthorize(WRITE)
    public AssetService.VerificationLine scan(@PathVariable Long id, @RequestBody ScanRequest r) {
        return assets.scan(id, r.tagNo(), r.condition());
    }

    @PostMapping("/api/asset-verifications/{id}/close")
    @PreAuthorize(WRITE)
    public AssetService.VerificationDetail close(@PathVariable Long id, @RequestBody CloseRequest r) {
        return assets.closeVerification(id, r.markMissingLost());
    }

    @GetMapping("/api/asset-verifications/{id}/report")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> verificationReport(@PathVariable Long id) {
        return UploadedFile.download("asset-verification-" + id + ".xlsx", XLSX, assets.verificationReport(id));
    }

    // ------------------------------------------------------------------ stores

    @GetMapping("/api/stores/items")
    @PreAuthorize(READ)
    public List<StoreService.ItemView> items(@RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean low) {
        return stores.items(q, low);
    }

    @PostMapping("/api/stores/items")
    @PreAuthorize(WRITE)
    public StoreService.ItemView addItem(@RequestBody StoreService.ItemRequest r) {
        return stores.saveItem(null, r);
    }

    @PutMapping("/api/stores/items/{id}")
    @PreAuthorize(WRITE)
    public StoreService.ItemView saveItem(@PathVariable Long id, @RequestBody StoreService.ItemRequest r) {
        return stores.saveItem(id, r);
    }

    @PostMapping("/api/stores/items/{id}/movements")
    @PreAuthorize(WRITE)
    public StoreService.Movement move(@PathVariable Long id, @RequestBody StoreService.MovementRequest r) {
        return stores.move(id, r);
    }

    @GetMapping("/api/stores/movements")
    @PreAuthorize(READ)
    public List<StoreService.Movement> movements(@RequestParam(required = false) Long itemId, @RequestParam(required = false) LocalDate from,
                                                 @RequestParam(required = false) LocalDate to) {
        return stores.movements(itemId, from, to);
    }

    @GetMapping("/api/stores/report")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> stockReport() {
        return UploadedFile.download("stock-report-" + LocalDate.now() + ".xlsx", XLSX, stores.stockReport());
    }
}
