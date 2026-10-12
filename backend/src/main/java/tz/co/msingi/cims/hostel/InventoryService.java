package tz.co.msingi.cims.hostel;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.exams.ExamAccess;
import tz.co.msingi.cims.hostel.HostelDtos.BedView;
import tz.co.msingi.cims.hostel.HostelDtos.FeeCategoryRequest;
import tz.co.msingi.cims.hostel.HostelDtos.FeeCategoryView;
import tz.co.msingi.cims.hostel.HostelDtos.HostelDetail;
import tz.co.msingi.cims.hostel.HostelDtos.HostelRequest;
import tz.co.msingi.cims.hostel.HostelDtos.HostelSummary;
import tz.co.msingi.cims.hostel.HostelDtos.RoomRequest;
import tz.co.msingi.cims.hostel.HostelDtos.RoomView;
import tz.co.msingi.cims.hostel.HostelRepositories.AllocationRepository;
import tz.co.msingi.cims.hostel.HostelRepositories.BedRepository;
import tz.co.msingi.cims.hostel.HostelRepositories.FeeCategoryRepository;
import tz.co.msingi.cims.hostel.HostelRepositories.HostelRepository;
import tz.co.msingi.cims.hostel.HostelRepositories.RoomRepository;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.registry.RegistryRepositories.StudentRepository;
import tz.co.msingi.cims.registry.Student;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The bed inventory (FR-HST-01): fee categories, hostels with gender designation, rooms and beds, with live occupancy. */
@Service
@RequiredArgsConstructor
public class InventoryService {

    static final Role[] WARDENS = {Role.INSTITUTION_ADMIN, Role.HOSTEL_WARDEN};
    static final Role[] VIEWERS = {Role.INSTITUTION_ADMIN, Role.HOSTEL_WARDEN, Role.REGISTRAR, Role.BURSAR, Role.ACCOUNTANT, Role.MANAGEMENT_VIEWER};
    private static final String BED_LABELS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final FeeCategoryRepository categories;
    private final HostelRepository hostels;
    private final RoomRepository rooms;
    private final BedRepository beds;
    private final AllocationRepository allocations;
    private final StudentRepository students;
    private final ExamAccess access;
    private final AuditService audit;

    // ------------------------------------------------------------------ fee categories

    @Transactional(readOnly = true)
    public List<FeeCategoryView> categories() {
        access.require("see hostel fee categories", VIEWERS);
        return categories.findAllByOrderByCodeAsc().stream().map(InventoryService::view).toList();
    }

    @Transactional
    public FeeCategoryView saveCategory(Long id, FeeCategoryRequest req) {
        access.require("set hostel fees", WARDENS);
        HostelFeeCategory c;
        if (id == null) {
            if (categories.findByCodeIgnoreCase(req.code()).isPresent()) throw new BusinessRuleException("DUPLICATE_CODE", "A fee category " + req.code() + " exists");
            c = new HostelFeeCategory();
            c.setCode(req.code().toUpperCase(Locale.ROOT));
        } else {
            c = categories.findById(id).orElseThrow(() -> new NotFoundException("Fee category", id));
        }
        c.setName(req.name().trim());
        c.setAmount(req.amount());
        c.setActive(req.active() == null || req.active());
        categories.saveAndFlush(c);
        audit.record(id == null ? "HOSTEL_FEE_CATEGORY_CREATED" : "HOSTEL_FEE_CATEGORY_UPDATED", "HostelFeeCategory", c.getId(),
                Map.of("code", c.getCode(), "amount", c.getAmount()));
        return view(c);
    }

    // ------------------------------------------------------------------ hostels

    @Transactional(readOnly = true)
    public List<HostelSummary> hostels() {
        access.require("see hostels", VIEWERS);
        List<HostelRoom> all = rooms.findAllByOrderByIdAsc();
        Map<Long, HostelAllocation> active = activeByBed();
        return hostels.findAllByOrderByNameAsc().stream()
                .map(h -> summary(h, all.stream().filter(r -> r.getHostel().getId().equals(h.getId())).toList(), active)).toList();
    }

    @Transactional
    public HostelSummary saveHostel(Long id, HostelRequest req) {
        access.require("manage hostels", WARDENS);
        Hostel h;
        if (id == null) {
            if (hostels.findByCodeIgnoreCase(req.code()).isPresent()) throw new BusinessRuleException("DUPLICATE_CODE", "A hostel " + req.code() + " exists");
            h = new Hostel();
            h.setCode(req.code().toUpperCase(Locale.ROOT));
        } else {
            h = hostels.findById(id).orElseThrow(() -> new NotFoundException("Hostel", id));
            if (h.getGender() != req.gender() && req.gender() != Hostel.Gender.MIXED) {
                HostelRoom.Gender g = HostelRoom.Gender.valueOf(req.gender().name());
                boolean clash = rooms.findByHostelIdOrderByBlockAscFloorAscRoomNoAsc(h.getId()).stream().anyMatch(r -> r.getGender() != g);
                if (clash) throw new BusinessRuleException("GENDER_CLASH", "Some rooms are designated for the other gender. Change them first.");
            }
        }
        h.setName(req.name().trim());
        h.setGender(req.gender());
        h.setLocation(req.location() == null || req.location().isBlank() ? null : req.location().trim());
        h.setActive(req.active() == null || req.active());
        hostels.saveAndFlush(h);
        audit.record(id == null ? "HOSTEL_CREATED" : "HOSTEL_UPDATED", "Hostel", h.getId(), Map.of("code", h.getCode(), "gender", h.getGender().name()));
        return summary(h, rooms.findByHostelIdOrderByBlockAscFloorAscRoomNoAsc(h.getId()), activeByBed());
    }

    @Transactional(readOnly = true)
    public HostelDetail hostel(Long id) {
        access.require("see hostels", VIEWERS);
        Hostel h = hostels.findById(id).orElseThrow(() -> new NotFoundException("Hostel", id));
        List<HostelRoom> rs = rooms.findByHostelIdOrderByBlockAscFloorAscRoomNoAsc(h.getId());
        Map<Long, HostelAllocation> active = activeByBed();
        Map<Long, Student> occupants = students.findAllById(active.values().stream().map(HostelAllocation::getStudentId).toList()).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        return new HostelDetail(summary(h, rs, active), rs.stream().map(r -> roomView(r, active, occupants)).toList(), access.has(WARDENS));
    }

    // ------------------------------------------------------------------ rooms and beds

    @Transactional
    public RoomView saveRoom(Long id, RoomRequest req) {
        access.require("manage hostel rooms", WARDENS);
        Hostel h = hostels.findById(req.hostelId()).orElseThrow(() -> new NotFoundException("Hostel", req.hostelId()));
        HostelFeeCategory fc = categories.findById(req.feeCategoryId()).orElseThrow(() -> new NotFoundException("Fee category", req.feeCategoryId()));
        HostelRoom.Gender gender = h.getGender() == Hostel.Gender.MIXED ? req.gender() : HostelRoom.Gender.valueOf(h.getGender().name());
        if (gender == null) throw new BusinessRuleException("GENDER_REQUIRED", "In a mixed hostel each room is designated female or male");
        if (h.getGender() != Hostel.Gender.MIXED && req.gender() != null && req.gender() != gender) {
            throw new BusinessRuleException("GENDER_CLASH", h.getName() + " is a " + h.getGender().name().toLowerCase() + " hostel");
        }
        String block = req.block() == null ? "" : req.block().trim();
        String roomNo = req.roomNo().trim();
        Map<Long, HostelAllocation> active = activeByBed();
        HostelRoom r;
        if (id == null) {
            if (rooms.existsByHostelIdAndBlockAndRoomNo(h.getId(), block, roomNo)) throw new BusinessRuleException("DUPLICATE_ROOM", "That room already exists");
            r = new HostelRoom();
            r.setHostel(h);
        } else {
            r = rooms.findById(id).orElseThrow(() -> new NotFoundException("Room", id));
            if (!r.getHostel().getId().equals(h.getId())) throw new BusinessRuleException("WRONG_HOSTEL", "A room cannot move to another hostel");
            boolean occupied = r.getBeds().stream().anyMatch(b -> active.containsKey(b.getId()));
            if (occupied && gender != r.getGender()) throw new BusinessRuleException("ROOM_OCCUPIED", "The room has students in it, so its gender cannot change");
        }
        r.setBlock(block);
        r.setFloor(req.floor() == null ? 0 : req.floor().shortValue());
        r.setRoomNo(roomNo);
        r.setRoomType(req.roomType());
        r.setGender(gender);
        r.setFeeCategory(fc);
        r.setAccessible(Boolean.TRUE.equals(req.accessible()));
        r.setStatus(req.status() == null ? HostelRoom.Status.AVAILABLE : req.status());
        int want = req.beds();
        while (r.getBeds().size() < want) {
            HostelBed b = new HostelBed();
            b.setRoom(r);
            b.setLabel(nextLabel(r));
            r.getBeds().add(b);
        }
        while (r.getBeds().size() > want) {
            HostelBed last = r.getBeds().stream().max(java.util.Comparator.comparing(HostelBed::getLabel)).orElseThrow();
            if (last.getId() != null && (active.containsKey(last.getId()) || allocationsExist(last))) {
                throw new BusinessRuleException("BED_IN_USE", "Bed " + last.getLabel() + " has allocations, so the room cannot shrink. Put it out of service instead.");
            }
            r.getBeds().remove(last);
        }
        rooms.saveAndFlush(r);
        audit.record(id == null ? "HOSTEL_ROOM_CREATED" : "HOSTEL_ROOM_UPDATED", "HostelRoom", r.getId(), Map.of("hostel", h.getCode(), "room", r.label(),
                "beds", want, "status", r.getStatus().name()));
        Map<Long, Student> occupants = students.findAllById(active.values().stream().map(HostelAllocation::getStudentId).toList()).stream()
                .collect(Collectors.toMap(Student::getId, Function.identity()));
        return roomView(r, active, occupants);
    }

    @Transactional
    public void bedStatus(Long bedId, HostelBed.Status status) {
        access.require("manage hostel beds", WARDENS);
        HostelBed b = beds.findById(bedId).orElseThrow(() -> new NotFoundException("Bed", bedId));
        if (status == HostelBed.Status.MAINTENANCE && activeByBed().containsKey(b.getId())) {
            throw new BusinessRuleException("BED_OCCUPIED", "The bed is allocated. Move or check the student out first.");
        }
        b.setStatus(status);
        audit.record("HOSTEL_BED_STATUS", "HostelBed", b.getId(), Map.of("room", b.getRoom().label(), "bed", b.getLabel(), "status", status.name()));
    }

    // ------------------------------------------------------------------ helpers

    Map<Long, HostelAllocation> activeByBed() {
        return allocations.findByStatusIn(EnumSet.of(HostelAllocation.Status.ALLOCATED, HostelAllocation.Status.CHECKED_IN)).stream()
                .collect(Collectors.toMap(a -> a.getBed().getId(), Function.identity(), (a, b) -> a));
    }

    private boolean allocationsExist(HostelBed b) {
        return allocations.existsByBedId(b.getId());
    }

    private static String nextLabel(HostelRoom r) {
        for (char c : BED_LABELS.toCharArray()) {
            String l = String.valueOf(c);
            if (r.getBeds().stream().noneMatch(b -> b.getLabel().equals(l))) return l;
        }
        return String.valueOf(r.getBeds().size() + 1);
    }

    static boolean usable(HostelBed b) {
        HostelRoom r = b.getRoom();
        return b.getStatus() == HostelBed.Status.AVAILABLE && r.getStatus() == HostelRoom.Status.AVAILABLE && r.getHostel().isActive();
    }

    private static HostelSummary summary(Hostel h, List<HostelRoom> rs, Map<Long, HostelAllocation> active) {
        int total = 0, occupied = 0, unpaid = 0, out = 0, available = 0;
        for (HostelRoom r : rs) {
            for (HostelBed b : r.getBeds()) {
                total++;
                HostelAllocation a = active.get(b.getId());
                if (a != null) {
                    if (a.getStatus() == HostelAllocation.Status.CHECKED_IN) occupied++;
                    else unpaid++;
                } else if (usable(b)) {
                    available++;
                } else {
                    out++;
                }
            }
        }
        return new HostelSummary(h.getId(), h.getCode(), h.getName(), h.getGender().name(), h.getLocation(), h.isActive(), rs.size(), total, available,
                occupied, unpaid, out);
    }

    private static RoomView roomView(HostelRoom r, Map<Long, HostelAllocation> active, Map<Long, Student> occupants) {
        List<BedView> bv = r.getBeds().stream().map(b -> {
            HostelAllocation a = b.getId() == null ? null : active.get(b.getId());
            Student s = a == null ? null : occupants.get(a.getStudentId());
            return new BedView(b.getId(), b.getLabel(), b.getStatus().name(), a == null ? null : a.getId(), s == null ? null : s.getRegistrationNo(),
                    s == null ? null : s.fullName(), a == null ? null : a.getStatus().name());
        }).toList();
        return new RoomView(r.getId(), r.getHostel().getId(), r.getHostel().getName(), r.getBlock(), r.getFloor(), r.getRoomNo(), r.getRoomType().name(),
                r.getGender().name(), r.getFeeCategory().getId(), r.getFeeCategory().getName(), r.getFeeCategory().getAmount(), r.isAccessible(),
                r.getStatus().name(), bv);
    }

    private static FeeCategoryView view(HostelFeeCategory c) {
        return new FeeCategoryView(c.getId(), c.getCode(), c.getName(), c.getAmount(), c.isActive());
    }
}
