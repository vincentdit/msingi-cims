package tz.co.msingi.cims.hr;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.common.web.UploadedFile;
import tz.co.msingi.cims.platform.security.StaffAccess;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** Staff records and leave (FR-HR-01..04). */
@Tag(name = "Staff records and leave")
@RestController
@RequiredArgsConstructor
public class HrController {

    static final String WRITE = "hasAnyRole('INSTITUTION_ADMIN','HR_OFFICER')";
    static final String READ = "hasAnyRole('INSTITUTION_ADMIN','HR_OFFICER','MANAGEMENT_VIEWER','AUDITOR')";

    private final StaffService staff;
    private final LeaveService leave;

    public record LeftRequest(LocalDate leftOn, String reason) {
    }

    public record DecisionRequest(boolean approve, String note) {
    }

    public record AdjustRequest(Long leaveTypeId, int year, int days, String reason) {
    }

    public record HolidayRequest(LocalDate day, String name) {
    }

    // ------------------------------------------------------------------ staff records

    @GetMapping("/api/hr/overview")
    @PreAuthorize(READ)
    public StaffService.Overview overview() {
        return staff.overview();
    }

    @GetMapping("/api/hr/staff")
    @PreAuthorize(READ)
    public List<StaffService.StaffSummary> list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "ACTIVE") String status,
                                                @RequestParam(required = false) String category, @RequestParam(required = false) Long departmentId) {
        return staff.list(q, status, category, departmentId);
    }

    @GetMapping("/api/hr/staff/{id}")
    @PreAuthorize(READ)
    public StaffService.StaffDetail get(@PathVariable Long id) {
        return staff.get(id);
    }

    @PostMapping("/api/hr/staff")
    @PreAuthorize(WRITE)
    public StaffService.StaffDetail add(@RequestBody StaffService.StaffRequest r) {
        return staff.save(null, r);
    }

    @PutMapping("/api/hr/staff/{id}")
    @PreAuthorize(WRITE)
    public StaffService.StaffDetail save(@PathVariable Long id, @RequestBody StaffService.StaffRequest r) {
        return staff.save(id, r);
    }

    @Operation(summary = "Record that the person left (reason null: they are back)")
    @PutMapping("/api/hr/staff/{id}/left")
    @PreAuthorize(WRITE)
    public StaffService.StaffDetail left(@PathVariable Long id, @RequestBody LeftRequest r) {
        return staff.setLeft(id, r.leftOn(), r.reason());
    }

    @GetMapping("/api/hr/accounts")
    @PreAuthorize(WRITE)
    public List<StaffService.Account> accounts() {
        return staff.accounts();
    }

    @Operation(summary = "Add a qualification (multipart: level, award, institution, year and an optional PDF or picture of the certificate, up to 5 MB)")
    @PostMapping(value = "/api/hr/staff/{id}/qualifications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(WRITE)
    public StaffService.StaffDetail addQualification(@PathVariable Long id, @RequestParam String level, @RequestParam String award,
                                                     @RequestParam String institution, @RequestParam Integer year,
                                                     @RequestPart(required = false) MultipartFile file) {
        return staff.addQualification(id, level, award, institution, year, file);
    }

    @PostMapping("/api/hr/qualifications/{id}/verify")
    @PreAuthorize(WRITE)
    public StaffService.StaffDetail verify(@PathVariable Long id) {
        return staff.verifyQualification(id);
    }

    @DeleteMapping("/api/hr/qualifications/{id}")
    @PreAuthorize(WRITE)
    public StaffService.StaffDetail deleteQualification(@PathVariable Long id) {
        return staff.deleteQualification(id);
    }

    @GetMapping("/api/hr/qualifications/{id}/file")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> qualificationFile(@PathVariable Long id) {
        StaffService.FileContent f = staff.qualificationFile(id);
        return UploadedFile.download(f.fileName(), f.contentType(), f.data());
    }

    @Operation(summary = "Import staff from CSV or Excel")
    @PostMapping(value = "/api/hr/staff/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(WRITE)
    public StaffService.ImportResult importStaff(@RequestPart MultipartFile file) throws IOException {
        return staff.importStaff(file.getBytes());
    }

    @Operation(summary = "Staffing return for NACTVET and the Board (Excel)")
    @GetMapping("/api/hr/staffing-return")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> staffingReturn() {
        return UploadedFile.download("staffing-return-" + LocalDate.now() + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", staff.staffingReturn());
    }

    // ------------------------------------------------------------------ leave: settings

    @GetMapping("/api/leave/types")
    @PreAuthorize(StaffAccess.ANY)
    public List<LeaveService.LeaveType> types() {
        return leave.types();
    }

    @PostMapping("/api/leave/types")
    @PreAuthorize(WRITE)
    public List<LeaveService.LeaveType> addType(@RequestBody LeaveService.LeaveType t) {
        return leave.saveType(null, t);
    }

    @PutMapping("/api/leave/types/{id}")
    @PreAuthorize(WRITE)
    public List<LeaveService.LeaveType> saveType(@PathVariable Long id, @RequestBody LeaveService.LeaveType t) {
        return leave.saveType(id, t);
    }

    @GetMapping("/api/leave/holidays")
    @PreAuthorize(StaffAccess.ANY)
    public List<LeaveService.Holiday> holidays(@RequestParam(required = false) Integer year) {
        return leave.holidays(year == null ? LocalDate.now().getYear() : year);
    }

    @PostMapping("/api/leave/holidays")
    @PreAuthorize(WRITE)
    public List<LeaveService.Holiday> addHoliday(@RequestBody HolidayRequest r) {
        return leave.addHoliday(r.day(), r.name());
    }

    @DeleteMapping("/api/leave/holidays/{id}")
    @PreAuthorize(WRITE)
    public List<LeaveService.Holiday> removeHoliday(@PathVariable Long id) {
        return leave.removeHoliday(id);
    }

    @GetMapping("/api/leave/count")
    @PreAuthorize(StaffAccess.ANY)
    public LeaveService.DaysPreview count(@RequestParam(required = false) Long leaveTypeId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return leave.count(leaveTypeId, from, to);
    }

    // ------------------------------------------------------------------ leave: my own

    @GetMapping("/api/my/leave")
    @PreAuthorize(StaffAccess.ANY)
    public LeaveService.MyLeave mine(@RequestParam(required = false) Integer year) {
        return leave.mine(year);
    }

    @PostMapping("/api/my/leave")
    @PreAuthorize(StaffAccess.ANY)
    public LeaveService.RequestView apply(@RequestBody LeaveService.NewRequest r) {
        return leave.apply(r);
    }

    @PostMapping("/api/leave/requests/{id}/cancel")
    @PreAuthorize(StaffAccess.ANY)
    public LeaveService.RequestView cancel(@PathVariable Long id) {
        return leave.cancel(id);
    }

    // ------------------------------------------------------------------ leave: approvers and HR

    @GetMapping("/api/leave/requests")
    @PreAuthorize(StaffAccess.ANY)
    public List<LeaveService.RequestView> requests(@RequestParam(defaultValue = "SUBMITTED") String status, @RequestParam(required = false) Integer year) {
        return leave.list(status, year);
    }

    @GetMapping("/api/leave/away")
    @PreAuthorize(StaffAccess.ANY)
    public List<LeaveService.RequestView> away(@RequestParam(required = false) LocalDate day) {
        return leave.away(day);
    }

    @PostMapping("/api/leave/requests/{id}/decision")
    @PreAuthorize(StaffAccess.ANY)
    public LeaveService.RequestView decide(@PathVariable Long id, @RequestBody DecisionRequest r) {
        return leave.decide(id, r.approve(), r.note());
    }

    @PostMapping("/api/hr/staff/{id}/leave")
    @PreAuthorize(WRITE)
    public LeaveService.RequestView record(@PathVariable Long id, @RequestBody LeaveService.NewRequest r) {
        return leave.record(id, r);
    }

    @GetMapping("/api/hr/staff/{id}/leave")
    @PreAuthorize(StaffAccess.ANY)
    public List<LeaveService.RequestView> history(@PathVariable Long id) {
        return leave.historyFor(id);
    }

    @GetMapping("/api/hr/staff/{id}/leave-balances")
    @PreAuthorize(StaffAccess.ANY)
    public List<LeaveService.Balance> balances(@PathVariable Long id, @RequestParam(required = false) Integer year) {
        return leave.balancesFor(id, year == null ? LocalDate.now().getYear() : year);
    }

    @PostMapping("/api/hr/staff/{id}/leave-adjustments")
    @PreAuthorize(WRITE)
    public List<LeaveService.Balance> adjust(@PathVariable Long id, @RequestBody AdjustRequest r) {
        return leave.adjust(id, r.leaveTypeId(), r.year(), r.days(), r.reason());
    }
}
