package tz.co.msingi.cims.finance.fees;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.AcademicYearRepository;
import tz.co.msingi.cims.academic.domain.AcademicRepositories.ProgrammeRepository;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.finance.fees.FeeDtos.FeeItemRequest;
import tz.co.msingi.cims.finance.fees.FeeDtos.FeeItemView;
import tz.co.msingi.cims.finance.fees.FeeDtos.GateRequest;
import tz.co.msingi.cims.finance.fees.FeeDtos.GateView;
import tz.co.msingi.cims.finance.fees.FeeDtos.LineRequest;
import tz.co.msingi.cims.finance.fees.FeeDtos.LineView;
import tz.co.msingi.cims.finance.fees.FeeDtos.StructureRequest;
import tz.co.msingi.cims.finance.fees.FeeDtos.StructureView;
import tz.co.msingi.cims.finance.fees.FeeRepositories.ClearanceGateRepository;
import tz.co.msingi.cims.finance.fees.FeeRepositories.FeeItemRepository;
import tz.co.msingi.cims.finance.fees.FeeRepositories.FeeStructureRepository;
import tz.co.msingi.cims.platform.audit.AuditService;
import tz.co.msingi.cims.platform.security.CurrentUser;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fee items, fee structures with Bursar approval and versions, and clearance gates (FR-FIN-01, FR-FIN-06). */
@Service
@RequiredArgsConstructor
public class FeeService {

    /** Gates enforced by the modules built so far; the others are stored for the modules that will use them. */
    static final Set<ClearanceGate.Gate> APPLIED_NOW = Set.of(ClearanceGate.Gate.REGISTRATION);

    private final FeeItemRepository items;
    private final FeeStructureRepository structures;
    private final ClearanceGateRepository gates;
    private final AcademicYearRepository years;
    private final ProgrammeRepository programmes;
    private final AuditService audit;

    // ------------------------------------------------------------------ fee items

    @Transactional(readOnly = true)
    public List<FeeItemView> items() {
        return items.findAllByOrderByAllocationPriorityAscCodeAsc().stream().map(FeeItemView::of).toList();
    }

    @Transactional
    public FeeItemView createItem(FeeItemRequest req) {
        if (items.existsByCodeIgnoreCase(req.code())) {
            throw new BusinessRuleException("DUPLICATE_CODE", "A fee item with code " + req.code() + " already exists");
        }
        FeeItem i = new FeeItem();
        i.setCode(req.code());
        apply(i, req);
        items.save(i);
        audit.record("FEE_ITEM_CREATED", "FeeItem", i.getId(), Map.of("code", i.getCode()));
        return FeeItemView.of(i);
    }

    /** The code never changes (it is printed on invoices); name, category, GFS code, priority and status can. */
    @Transactional
    public FeeItemView updateItem(Long id, FeeItemRequest req) {
        FeeItem i = items.findById(id).orElseThrow(() -> new NotFoundException("Fee item", id));
        if (!i.getCode().equalsIgnoreCase(req.code())) {
            throw new BusinessRuleException("CODE_IMMUTABLE", "The code of a fee item cannot be changed");
        }
        apply(i, req);
        audit.record("FEE_ITEM_UPDATED", "FeeItem", i.getId(), Map.of("code", i.getCode()));
        return FeeItemView.of(i);
    }

    private static void apply(FeeItem i, FeeItemRequest req) {
        i.setName(req.name().trim());
        i.setCategory(req.category());
        i.setGfsCode(req.gfsCode() == null || req.gfsCode().isBlank() ? null : req.gfsCode().trim());
        i.setAllocationPriority(req.allocationPriority() == 0 ? 100 : req.allocationPriority());
        i.setActive(req.active());
    }

    // ------------------------------------------------------------------ fee structures

    @Transactional(readOnly = true)
    public List<StructureView> structures(Long academicYearId) {
        return structures.findByAcademicYearIdOrderByProgrammeCodeAscYearOfStudyAscStructureVersionDesc(academicYearId)
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public StructureView structure(Long id) {
        return view(require(id));
    }

    /** A new draft; if an approved structure exists for the same programme and year, this is its next version. */
    @Transactional
    public StructureView create(StructureRequest req) {
        var year = years.findById(req.academicYearId()).orElseThrow(() -> new NotFoundException("Academic year", req.academicYearId()));
        var programme = programmes.findById(req.programmeId()).orElseThrow(() -> new NotFoundException("Programme", req.programmeId()));
        if (req.yearOfStudy() > Math.max(1, (programme.getDurationSemesters() + 1) / 2)) {
            throw new BusinessRuleException("INVALID_YEAR_OF_STUDY", programme.getName() + " runs for "
                    + (programme.getDurationSemesters() + 1) / 2 + " year(s)");
        }
        if (structures.findByAcademicYearIdAndProgrammeIdAndYearOfStudyAndStatus(year.getId(), programme.getId(),
                req.yearOfStudy(), FeeStructure.Status.DRAFT).isPresent()) {
            throw new BusinessRuleException("DRAFT_EXISTS", "There is already a draft for " + programme.getName()
                    + ", year " + req.yearOfStudy() + ". Edit or approve that draft.");
        }
        FeeStructure f = new FeeStructure();
        f.setAcademicYear(year);
        f.setProgramme(programme);
        f.setYearOfStudy(req.yearOfStudy());
        f.setStructureVersion((short) (structures.maxVersion(year.getId(), programme.getId(), req.yearOfStudy()) + 1));
        f.setNotes(req.notes());
        f.setCreatedBy(CurrentUser.id().orElse(null));
        f.setCreatedByName(CurrentUser.name());
        setLines(f, req.lines());
        structures.save(f);
        audit.record("FEE_STRUCTURE_DRAFTED", "FeeStructure", f.getId(), Map.of("programme", programme.getCode(),
                "yearOfStudy", req.yearOfStudy(), "version", f.getStructureVersion(), "total", f.total(false)));
        return view(f);
    }

    @Transactional
    public StructureView updateDraft(Long id, String notes, List<LineRequest> lines) {
        FeeStructure f = requireDraft(id);
        f.setNotes(notes);
        setLines(f, lines);
        audit.record("FEE_STRUCTURE_EDITED", "FeeStructure", id, Map.of("total", f.total(false)));
        return view(f);
    }

    /** Starts the next version from an approved structure (the approved one stays in use until the new one is approved). */
    @Transactional
    public StructureView revise(Long id) {
        FeeStructure src = require(id);
        if (src.getStatus() != FeeStructure.Status.APPROVED) {
            throw new BusinessRuleException("NOT_APPROVED", "Only the approved structure can be revised");
        }
        List<LineRequest> lines = src.getLines().stream()
                .map(l -> new LineRequest(l.getFeeItem().getId(), l.getAmount(), l.getForeignAmount(), l.getInstalment())).toList();
        return create(new StructureRequest(src.getAcademicYear().getId(), src.getProgramme().getId(), src.getYearOfStudy(),
                "Revision of version " + src.getStructureVersion(), lines));
    }

    /**
     * Bursar approval. The person who prepared the draft cannot approve it. The previously approved version is
     * superseded: invoices already issued keep their amounts; new invoices use this version.
     */
    @Transactional
    public StructureView approve(Long id) {
        FeeStructure f = requireDraft(id);
        if (isPreparer(f)) {
            throw new BusinessRuleException("SEGREGATION_OF_DUTIES", "The person who prepared a fee structure cannot approve it");
        }
        if (f.getLines().isEmpty()) {
            throw new BusinessRuleException("EMPTY_STRUCTURE", "A fee structure needs at least one fee item");
        }
        OffsetDateTime now = OffsetDateTime.now();
        structures.findByAcademicYearIdAndProgrammeIdAndYearOfStudyAndStatus(f.getAcademicYear().getId(), f.getProgramme().getId(),
                f.getYearOfStudy(), FeeStructure.Status.APPROVED).ifPresent(old -> {
            old.setStatus(FeeStructure.Status.SUPERSEDED);
            old.setSupersededAt(now);
            structures.saveAndFlush(old);
        });
        f.setStatus(FeeStructure.Status.APPROVED);
        f.setApprovedAt(now);
        f.setApprovedBy(CurrentUser.requireId());
        f.setApprovedByName(CurrentUser.name());
        audit.record("FEE_STRUCTURE_APPROVED", "FeeStructure", id, Map.of("programme", f.getProgramme().getCode(),
                "yearOfStudy", f.getYearOfStudy(), "version", f.getStructureVersion(), "total", f.total(false)));
        return view(f);
    }

    private void setLines(FeeStructure f, List<LineRequest> lines) {
        Set<String> seen = new HashSet<>();
        f.getLines().clear();
        for (LineRequest l : lines) {
            short instalment = l.instalment() == 0 ? 1 : l.instalment();
            if (!seen.add(l.feeItemId() + "/" + instalment)) {
                throw new BusinessRuleException("DUPLICATE_ITEM", "A fee item can appear only once per instalment");
            }
            FeeItem item = items.findById(l.feeItemId()).orElseThrow(() -> new NotFoundException("Fee item", l.feeItemId()));
            if (!item.isActive()) {
                throw new BusinessRuleException("INACTIVE_ITEM", item.getName() + " is not active");
            }
            FeeStructureLine line = new FeeStructureLine();
            line.setStructure(f);
            line.setFeeItem(item);
            line.setAmount(l.amount());
            line.setForeignAmount(l.foreignAmount());
            line.setInstalment(instalment);
            f.getLines().add(line);
        }
        if (f.total(false).signum() <= 0) {
            throw new BusinessRuleException("ZERO_TOTAL", "The fee structure total must be more than zero");
        }
    }

    private StructureView view(FeeStructure f) {
        List<LineView> lines = f.getLines().stream()
                .sorted((a, b) -> a.getInstalment() != b.getInstalment() ? a.getInstalment() - b.getInstalment()
                        : a.getFeeItem().getAllocationPriority() - b.getFeeItem().getAllocationPriority())
                .map(l -> new LineView(l.getFeeItem().getId(), l.getFeeItem().getCode(), l.getFeeItem().getName(),
                        l.getFeeItem().getCategory().name(), l.getAmount(), l.getForeignAmount(), l.getInstalment())).toList();
        BigDecimal first = f.getLines().stream().filter(l -> l.getInstalment() == 1).map(FeeStructureLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean canApprove = f.getStatus() == FeeStructure.Status.DRAFT && !isPreparer(f);
        return new StructureView(f.getId(), f.getAcademicYear().getId(), f.getAcademicYear().getCode(), f.getProgramme().getId(),
                f.getProgramme().getCode(), f.getProgramme().getName(), f.getYearOfStudy(), f.getStructureVersion(), f.getStatus().name(),
                f.getNotes(), f.getCreatedByName(), f.getApprovedAt(), f.getApprovedByName(), f.total(false), f.total(true), first,
                lines, canApprove);
    }

    private static boolean isPreparer(FeeStructure f) {
        return f.getCreatedBy() != null && f.getCreatedBy().equals(CurrentUser.id().orElse(-1L));
    }

    private FeeStructure require(Long id) {
        return structures.findById(id).orElseThrow(() -> new NotFoundException("Fee structure", id));
    }

    private FeeStructure requireDraft(Long id) {
        FeeStructure f = require(id);
        if (f.getStatus() != FeeStructure.Status.DRAFT) {
            throw new BusinessRuleException("NOT_A_DRAFT", "An approved fee structure cannot be changed. Revise it to create a new version.");
        }
        return f;
    }

    // ------------------------------------------------------------------ clearance gates

    @Transactional(readOnly = true)
    public List<GateView> gates() {
        return Arrays.stream(ClearanceGate.Gate.values()).map(g -> gates.findByGate(g)
                .map(x -> new GateView(g.name(), x.isEnabled(), x.getMinPercentPaid(), x.isFirstInstalmentRequired(), APPLIED_NOW.contains(g)))
                .orElse(new GateView(g.name(), false, BigDecimal.ZERO, false, APPLIED_NOW.contains(g)))).toList();
    }

    @Transactional
    public List<GateView> updateGate(ClearanceGate.Gate gate, GateRequest req) {
        ClearanceGate g = gates.findByGate(gate).orElseGet(() -> {
            ClearanceGate n = new ClearanceGate();
            n.setGate(gate);
            return n;
        });
        g.setEnabled(req.enabled());
        g.setMinPercentPaid(req.minPercentPaid());
        g.setFirstInstalmentRequired(req.firstInstalmentRequired());
        gates.save(g);
        audit.record("CLEARANCE_GATE_SET", "ClearanceGate", g.getId(), Map.of("gate", gate.name(), "enabled", req.enabled(),
                "minPercentPaid", req.minPercentPaid(), "firstInstalmentRequired", req.firstInstalmentRequired()));
        return gates();
    }
}
