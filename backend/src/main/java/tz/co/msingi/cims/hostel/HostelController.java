package tz.co.msingi.cims.hostel;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.msingi.cims.hostel.HostelDtos.AllocateResult;
import tz.co.msingi.cims.hostel.HostelDtos.AllocationView;
import tz.co.msingi.cims.hostel.HostelDtos.ApplicationView;
import tz.co.msingi.cims.hostel.HostelDtos.ApplyRequest;
import tz.co.msingi.cims.hostel.HostelDtos.BedStatusRequest;
import tz.co.msingi.cims.hostel.HostelDtos.CheckOutRequest;
import tz.co.msingi.cims.hostel.HostelDtos.FeeCategoryRequest;
import tz.co.msingi.cims.hostel.HostelDtos.FeeCategoryView;
import tz.co.msingi.cims.hostel.HostelDtos.FreeBed;
import tz.co.msingi.cims.hostel.HostelDtos.HostelDetail;
import tz.co.msingi.cims.hostel.HostelDtos.HostelRequest;
import tz.co.msingi.cims.hostel.HostelDtos.HostelSummary;
import tz.co.msingi.cims.hostel.HostelDtos.ManualAllocation;
import tz.co.msingi.cims.hostel.HostelDtos.MyHostel;
import tz.co.msingi.cims.hostel.HostelDtos.NoteRequest;
import tz.co.msingi.cims.hostel.HostelDtos.ReasonRequest;
import tz.co.msingi.cims.hostel.HostelDtos.ReleaseResult;
import tz.co.msingi.cims.hostel.HostelDtos.RoomRequest;
import tz.co.msingi.cims.hostel.HostelDtos.RoomView;
import tz.co.msingi.cims.hostel.HostelDtos.WindowRequest;
import tz.co.msingi.cims.hostel.HostelDtos.WindowView;

import java.util.List;

/** Hostels and accommodation (FR-HST-01..05, BP-12, UC-16). */
@Tag(name = "Hostels")
@RestController
@RequiredArgsConstructor
public class HostelController {

    private static final String VIEWERS = "hasAnyRole('INSTITUTION_ADMIN','HOSTEL_WARDEN','REGISTRAR','BURSAR','ACCOUNTANT','MANAGEMENT_VIEWER')";
    private static final String WARDENS = "hasAnyRole('INSTITUTION_ADMIN','HOSTEL_WARDEN')";

    private final InventoryService inventory;
    private final AllocationService allocation;
    private final RepairService repairs;

    // ------------------------------------------------------------------ repairs (FR-HST-06)

    @GetMapping("/api/hostel/repairs")
    @PreAuthorize(VIEWERS)
    public List<RepairService.RepairView> repairs(@RequestParam(defaultValue = "false") boolean open) {
        return repairs.list(open);
    }

    @PostMapping("/api/hostel/repairs")
    @PreAuthorize(WARDENS)
    public RepairService.RepairView logRepair(@Valid @RequestBody RepairService.RepairRequest req) {
        return repairs.log(req);
    }

    @PostMapping("/api/hostel/repairs/{id}/status")
    @PreAuthorize(WARDENS)
    public RepairService.RepairView repairStatus(@PathVariable Long id, @Valid @RequestBody RepairService.StatusRequest req) {
        return repairs.update(id, req);
    }

    @GetMapping("/api/student/hostel/repairs")
    @PreAuthorize("hasRole('STUDENT')")
    public List<RepairService.RepairView> myRepairs() {
        return repairs.mine();
    }

    @PostMapping("/api/student/hostel/repairs")
    @PreAuthorize("hasRole('STUDENT')")
    public RepairService.RepairView reportRepair(@Valid @RequestBody RepairService.RepairRequest req) {
        return repairs.report(req);
    }

    // ------------------------------------------------------------------ inventory

    @GetMapping("/api/hostel/fee-categories")
    @PreAuthorize(VIEWERS)
    public List<FeeCategoryView> categories() {
        return inventory.categories();
    }

    @PostMapping("/api/hostel/fee-categories")
    @PreAuthorize(WARDENS)
    @ResponseStatus(HttpStatus.CREATED)
    public FeeCategoryView createCategory(@Valid @RequestBody FeeCategoryRequest req) {
        return inventory.saveCategory(null, req);
    }

    @PutMapping("/api/hostel/fee-categories/{id}")
    @PreAuthorize(WARDENS)
    public FeeCategoryView updateCategory(@PathVariable Long id, @Valid @RequestBody FeeCategoryRequest req) {
        return inventory.saveCategory(id, req);
    }

    @GetMapping("/api/hostel/hostels")
    @PreAuthorize(VIEWERS)
    public List<HostelSummary> hostels() {
        return inventory.hostels();
    }

    @PostMapping("/api/hostel/hostels")
    @PreAuthorize(WARDENS)
    @ResponseStatus(HttpStatus.CREATED)
    public HostelSummary createHostel(@Valid @RequestBody HostelRequest req) {
        return inventory.saveHostel(null, req);
    }

    @PutMapping("/api/hostel/hostels/{id}")
    @PreAuthorize(WARDENS)
    public HostelSummary updateHostel(@PathVariable Long id, @Valid @RequestBody HostelRequest req) {
        return inventory.saveHostel(id, req);
    }

    @GetMapping("/api/hostel/hostels/{id}")
    @PreAuthorize(VIEWERS)
    public HostelDetail hostel(@PathVariable Long id) {
        return inventory.hostel(id);
    }

    @PutMapping("/api/hostel/hostels/{id}/wardens")
    @PreAuthorize("hasRole('INSTITUTION_ADMIN')")
    public HostelDetail wardens(@PathVariable Long id, @Valid @RequestBody WardensRequest req) {
        return inventory.assignWardens(id, req.userIds());
    }

    public record WardensRequest(@jakarta.validation.constraints.NotNull List<Long> userIds) {
    }

    @PostMapping("/api/hostel/rooms")
    @PreAuthorize(WARDENS)
    @ResponseStatus(HttpStatus.CREATED)
    public RoomView createRoom(@Valid @RequestBody RoomRequest req) {
        return inventory.saveRoom(null, req);
    }

    @PutMapping("/api/hostel/rooms/{id}")
    @PreAuthorize(WARDENS)
    public RoomView updateRoom(@PathVariable Long id, @Valid @RequestBody RoomRequest req) {
        return inventory.saveRoom(id, req);
    }

    @PutMapping("/api/hostel/beds/{id}/status")
    @PreAuthorize(WARDENS)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void bedStatus(@PathVariable Long id, @Valid @RequestBody BedStatusRequest req) {
        inventory.bedStatus(id, req.status());
    }

    // ------------------------------------------------------------------ windows, applications and allocation

    @GetMapping("/api/hostel/windows")
    @PreAuthorize(VIEWERS)
    public List<WindowView> windows() {
        return allocation.windows();
    }

    @PostMapping("/api/hostel/windows")
    @PreAuthorize(WARDENS)
    @ResponseStatus(HttpStatus.CREATED)
    public WindowView createWindow(@Valid @RequestBody WindowRequest req) {
        return allocation.saveWindow(null, req);
    }

    @PutMapping("/api/hostel/windows/{id}")
    @PreAuthorize(WARDENS)
    public WindowView updateWindow(@PathVariable Long id, @Valid @RequestBody WindowRequest req) {
        return allocation.saveWindow(id, req);
    }

    @GetMapping("/api/hostel/windows/{id}/applications")
    @PreAuthorize(VIEWERS)
    public List<ApplicationView> applications(@PathVariable Long id) {
        return allocation.applications(id);
    }

    @GetMapping("/api/hostel/windows/{id}/allocations")
    @PreAuthorize(VIEWERS)
    public List<AllocationView> allocations(@PathVariable Long id) {
        return allocation.allocationsOf(id);
    }

    @PostMapping("/api/hostel/windows/{id}/allocate")
    @PreAuthorize(WARDENS)
    public AllocateResult allocate(@PathVariable Long id) {
        return allocation.allocate(id);
    }

    @GetMapping("/api/hostel/applications/{id}/free-beds")
    @PreAuthorize(WARDENS)
    public List<FreeBed> freeBeds(@PathVariable Long id) {
        return allocation.freeBeds(id);
    }

    @PostMapping("/api/hostel/allocations")
    @PreAuthorize(WARDENS)
    public AllocationView allocateManually(@Valid @RequestBody ManualAllocation req) {
        return allocation.allocateManually(req.applicationId(), req.bedId());
    }

    @PostMapping("/api/hostel/allocations/release-unpaid")
    @PreAuthorize(WARDENS)
    public ReleaseResult releaseUnpaid() {
        return allocation.releaseUnpaid();
    }

    @PostMapping("/api/hostel/allocations/{id}/cancel")
    @PreAuthorize(WARDENS)
    public AllocationView cancel(@PathVariable Long id, @Valid @RequestBody ReasonRequest req) {
        return allocation.cancel(id, req.reason());
    }

    @PostMapping("/api/hostel/allocations/{id}/check-in")
    @PreAuthorize(WARDENS)
    public AllocationView checkIn(@PathVariable Long id, @Valid @RequestBody(required = false) NoteRequest req) {
        return allocation.checkIn(id, req == null ? null : req.note());
    }

    @PostMapping("/api/hostel/allocations/{id}/check-out")
    @PreAuthorize(WARDENS)
    public AllocationView checkOut(@PathVariable Long id, @Valid @RequestBody CheckOutRequest req) {
        return allocation.checkOut(id, req);
    }

    // ------------------------------------------------------------------ student portal

    @GetMapping("/api/student/hostel")
    @PreAuthorize("hasRole('STUDENT')")
    public MyHostel mine() {
        return allocation.mine();
    }

    @PostMapping("/api/student/hostel/applications")
    @PreAuthorize("hasRole('STUDENT')")
    public MyHostel apply(@Valid @RequestBody ApplyRequest req) {
        return allocation.apply(req);
    }

    @DeleteMapping("/api/student/hostel/applications/{id}")
    @PreAuthorize("hasRole('STUDENT')")
    public MyHostel cancelMine(@PathVariable Long id) {
        return allocation.cancelMine(id);
    }
}
