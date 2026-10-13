package tz.co.msingi.cims.hostel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.hostel.HostelRepositories.AllocationRepository;
import tz.co.msingi.cims.hostel.HostelRepositories.HostelRepository;
import tz.co.msingi.cims.hostel.HostelRepositories.RoomRepository;
import tz.co.msingi.cims.notification.Messaging;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.numbering.NumberSequenceService;
import tz.co.msingi.cims.platform.security.CurrentUser;
import tz.co.msingi.cims.platform.tenancy.TenantContext;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.time.OffsetDateTime;
import java.time.Year;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Hostel repairs (FR-HST-06): students report a problem in their room from the portal (or a warden logs one), the warden takes it
 * up and closes it with a note, and the student is told by SMS at each step. Wardens see the repairs of their own hostels.
 */
@Service
@RequiredArgsConstructor
public class RepairService {

    public interface HostelRepairRepository extends JpaRepository<HostelRepair, Long> {
        List<HostelRepair> findTop300ByOrderByReportedAtDesc();

        List<HostelRepair> findByStudentIdOrderByReportedAtDesc(Long studentId);
    }

    public record RepairRequest(Long hostelId, Long roomId, @NotNull HostelRepair.Category category, HostelRepair.Priority priority,
                                @NotBlank @Size(min = 5, max = 1000) String description) {
    }

    public record StatusRequest(@NotNull HostelRepair.Status status, @Size(max = 1000) String note) {
    }

    public record RepairView(Long id, String repairNo, Long hostelId, String hostel, String room, String category, String priority, String description,
                             String status, String reportedByName, OffsetDateTime reportedAt, String studentName, String registrationNo, String note,
                             String updatedByName, OffsetDateTime resolvedAt, boolean canUpdate) {
    }

    private final HostelRepairRepository repairs;
    private final HostelRepository hostels;
    private final RoomRepository rooms;
    private final AllocationRepository allocations;
    private final StudentRepository students;
    private final HostelScope scope;
    private final ExamAccess access;
    private final NumberSequenceService sequences;
    private final Messaging messaging;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<RepairView> list(boolean openOnly) {
        access.require("see hostel repairs", InventoryService.VIEWERS);
        return repairs.findTop300ByOrderByReportedAtDesc().stream().filter(r -> scope.covers(r.getHostelId()))
                .filter(r -> !openOnly || r.getStatus() == HostelRepair.Status.OPEN || r.getStatus() == HostelRepair.Status.IN_PROGRESS)
                .sorted(Comparator.comparing((HostelRepair r) -> r.getPriority() != HostelRepair.Priority.URGENT).thenComparing(HostelRepair::getReportedAt))
                .map(this::view).toList();
    }

    @Transactional
    public RepairView log(RepairRequest req) {
        access.require("log hostel repairs", InventoryService.WARDENS);
        if (req.hostelId() == null) throw new BusinessRuleException("HOSTEL_REQUIRED", "Choose the hostel");
        hostels.findById(req.hostelId()).orElseThrow(() -> new NotFoundException("Hostel", req.hostelId()));
        scope.require(req.hostelId());
        if (req.roomId() != null) {
            HostelRoom room = rooms.findById(req.roomId()).orElseThrow(() -> new NotFoundException("Room", req.roomId()));
            if (!room.getHostel().getId().equals(req.hostelId())) throw new BusinessRuleException("WRONG_HOSTEL", "That room is in another hostel");
        }
        return view(create(req.hostelId(), req.roomId(), null, req));
    }

    @Transactional
    public RepairView update(Long id, StatusRequest req) {
        access.require("update hostel repairs", InventoryService.WARDENS);
        HostelRepair r = repairs.findById(id).orElseThrow(() -> new NotFoundException("Repair", id));
        scope.require(r.getHostelId());
        if (r.getStatus() == HostelRepair.Status.DONE || r.getStatus() == HostelRepair.Status.CANCELLED) {
            throw new BusinessRuleException("REPAIR_CLOSED", "This repair is closed");
        }
        if (req.status() == HostelRepair.Status.OPEN) throw new BusinessRuleException("INVALID_STATUS", "Choose in progress, done or cancelled");
        if ((req.status() == HostelRepair.Status.DONE || req.status() == HostelRepair.Status.CANCELLED) && (req.note() == null || req.note().isBlank())) {
            throw new BusinessRuleException("NOTE_REQUIRED", "Say what was done");
        }
        r.setStatus(req.status());
        r.setNote(req.note() == null || req.note().isBlank() ? r.getNote() : req.note().trim());
        r.setUpdatedByName(CurrentUser.name());
        if (req.status() == HostelRepair.Status.DONE || req.status() == HostelRepair.Status.CANCELLED) r.setResolvedAt(OffsetDateTime.now());
        repairs.saveAndFlush(r);
        if (r.getStudentId() != null) {
            students.findById(r.getStudentId()).ifPresent(s -> messaging.event("HOSTEL_REPAIR_UPDATED", s.getPhone(),
                    Map.of("place", place(r), "status", statusLabel(r.getStatus()))));
        }
        audit.record("HOSTEL_REPAIR_" + req.status().name(), "HostelRepair", r.getId(), Map.of("no", r.getRepairNo()));
        return view(r);
    }

    // ------------------------------------------------------------------ student portal

    @Transactional(readOnly = true)
    public List<RepairView> mine() {
        Student me = me();
        return repairs.findByStudentIdOrderByReportedAtDesc(me.getId()).stream().map(this::view).toList();
    }

    /** A student reports a problem in the room they are allocated to. */
    @Transactional
    public RepairView report(RepairRequest req) {
        Student me = me();
        HostelAllocation a = allocations.findByStatusIn(EnumSet.of(HostelAllocation.Status.ALLOCATED, HostelAllocation.Status.CHECKED_IN)).stream()
                .filter(x -> x.getStudentId().equals(me.getId())).findFirst()
                .orElseThrow(() -> new BusinessRuleException("NO_ROOM", "You do not have a hostel room, so there is nothing to report"));
        HostelRoom room = a.getBed().getRoom();
        long open = repairs.findByStudentIdOrderByReportedAtDesc(me.getId()).stream()
                .filter(r -> r.getStatus() == HostelRepair.Status.OPEN || r.getStatus() == HostelRepair.Status.IN_PROGRESS).count();
        if (open >= 5) throw new BusinessRuleException("TOO_MANY_OPEN", "You already have 5 repairs waiting. The warden will deal with them first.");
        return view(create(room.getHostel().getId(), room.getId(), me.getId(), req));
    }

    private HostelRepair create(Long hostelId, Long roomId, Long studentId, RepairRequest req) {
        int y = Year.now().getValue();
        HostelRepair r = new HostelRepair();
        r.setRepairNo(NumberSequenceService.format(TenantContext.require().code() + "/REP/" + y + "/", sequences.next("REPAIR-" + y), 5));
        r.setHostelId(hostelId);
        r.setRoomId(roomId);
        r.setStudentId(studentId);
        r.setCategory(req.category());
        r.setPriority(req.priority() == null ? HostelRepair.Priority.NORMAL : req.priority());
        r.setDescription(req.description().trim());
        r.setReportedByName(CurrentUser.name());
        r.setReportedAt(OffsetDateTime.now());
        repairs.saveAndFlush(r);
        audit.record("HOSTEL_REPAIR_REPORTED", "HostelRepair", r.getId(), Map.of("no", r.getRepairNo(), "category", r.getCategory().name(),
                "priority", r.getPriority().name()));
        return r;
    }

    private RepairView view(HostelRepair r) {
        Hostel h = hostels.findById(r.getHostelId()).orElse(null);
        HostelRoom room = r.getRoomId() == null ? null : rooms.findById(r.getRoomId()).orElse(null);
        Student s = r.getStudentId() == null ? null : students.findById(r.getStudentId()).orElse(null);
        boolean open = r.getStatus() == HostelRepair.Status.OPEN || r.getStatus() == HostelRepair.Status.IN_PROGRESS;
        return new RepairView(r.getId(), r.getRepairNo(), r.getHostelId(), h == null ? null : h.getName(), room == null ? null : room.label(), r.getCategory().name(),
                r.getPriority().name(), r.getDescription(), r.getStatus().name(), r.getReportedByName(), r.getReportedAt(), s == null ? null : s.fullName(),
                s == null ? null : s.getRegistrationNo(), r.getNote(), r.getUpdatedByName(), r.getResolvedAt(),
                open && CurrentUser.id().isPresent() && access.has(InventoryService.WARDENS) && scope.covers(r.getHostelId()));
    }

    private String place(HostelRepair r) {
        HostelRoom room = r.getRoomId() == null ? null : rooms.findById(r.getRoomId()).orElse(null);
        String hostel = hostels.findById(r.getHostelId()).map(Hostel::getName).orElse("the hostel");
        return room == null ? hostel : hostel + ", " + room.label();
    }

    private static String statusLabel(HostelRepair.Status s) {
        return switch (s) {
            case IN_PROGRESS -> "being fixed";
            case DONE -> "fixed";
            case CANCELLED -> "closed";
            default -> "received";
        };
    }

    private Student me() {
        return students.findByUserId(CurrentUser.requireId()).orElseThrow(() -> new NotFoundException("No student record is linked to this account"));
    }
}
