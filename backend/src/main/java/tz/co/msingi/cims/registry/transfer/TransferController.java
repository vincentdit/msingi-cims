package tz.co.msingi.cims.registry.transfer;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.registry.transfer.TransferService.DecisionRequest;
import tz.co.msingi.cims.registry.transfer.TransferService.NoteRequest;
import tz.co.msingi.cims.registry.transfer.TransferService.Options;
import tz.co.msingi.cims.registry.transfer.TransferService.TransferRequest;
import tz.co.msingi.cims.registry.transfer.TransferService.TransferView;

import java.util.List;

/** Programme and campus transfers: Registry, the Head of the receiving department, the Deputy Principal (Academic) and the student. */
@Tag(name = "Registry — transfers")
@RestController
@RequiredArgsConstructor
public class TransferController {

    private static final String FOLLOW = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','DEPUTY_PRINCIPAL_ACADEMIC','HEAD_OF_DEPARTMENT','MANAGEMENT_VIEWER')";
    private static final String REGISTRY = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR')";
    private static final String STUDENT = "hasRole('STUDENT')";

    private final TransferService service;

    @GetMapping("/api/registry/transfers")
    @PreAuthorize(FOLLOW)
    public List<TransferView> list(@RequestParam(defaultValue = "true") boolean open) {
        return service.list(open);
    }

    @GetMapping("/api/registry/students/{id}/transfers")
    @PreAuthorize(FOLLOW)
    public List<TransferView> forStudent(@PathVariable Long id) {
        return service.forStudent(id);
    }

    @GetMapping("/api/registry/students/{id}/transfer-options")
    @PreAuthorize(FOLLOW)
    public Options options(@PathVariable Long id) {
        return service.options(id);
    }

    @PostMapping("/api/registry/students/{id}/transfers")
    @PreAuthorize(REGISTRY)
    public TransferView request(@PathVariable Long id, @Valid @RequestBody TransferRequest req) {
        return service.request(id, req);
    }

    @PostMapping("/api/registry/transfers/{id}/recommend")
    @PreAuthorize(FOLLOW)
    public TransferView recommend(@PathVariable Long id, @Valid @RequestBody(required = false) NoteRequest req) {
        return service.recommend(id, req == null ? null : req.note());
    }

    @PostMapping("/api/registry/transfers/{id}/decide")
    @PreAuthorize(FOLLOW)
    public TransferView decide(@PathVariable Long id, @Valid @RequestBody DecisionRequest req) {
        return service.decide(id, req.approve(), req.note());
    }

    @PostMapping("/api/registry/transfers/{id}/cancel")
    @PreAuthorize(REGISTRY)
    public TransferView cancel(@PathVariable Long id, @Valid @RequestBody(required = false) NoteRequest req) {
        return service.cancel(id, req == null ? null : req.note());
    }

    @GetMapping("/api/student/transfers")
    @PreAuthorize(STUDENT)
    public List<TransferView> mine() {
        return service.mine();
    }

    @GetMapping("/api/student/transfer-options")
    @PreAuthorize(STUDENT)
    public Options myOptions() {
        return service.myOptions();
    }

    @PostMapping("/api/student/transfers")
    @PreAuthorize(STUDENT)
    public TransferView requestMine(@Valid @RequestBody TransferRequest req) {
        return service.requestMine(req);
    }

    @PostMapping("/api/student/transfers/{id}/cancel")
    @PreAuthorize(STUDENT)
    public TransferView cancelMine(@PathVariable Long id) {
        return service.cancelMine(id);
    }
}
