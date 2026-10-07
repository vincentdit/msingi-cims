package tz.co.msingi.cims.finance.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** The reconciliation of one day's GePG collections against the payments CIMS recorded (FR-FIN-05). */
@Getter
@Setter
@Entity
@Table(name = "gepg_reconciliations")
public class GepgReconciliation extends BaseEntity {

    public enum Source { FILE, API }

    public enum Status { OPEN, CLOSED }

    @Column(nullable = false)
    private LocalDate reconDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source = Source.FILE;

    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    @Column(nullable = false)
    private int gepgCount;
    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal gepgTotal = BigDecimal.ZERO;
    @Column(nullable = false)
    private int cimsCount;
    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal cimsTotal = BigDecimal.ZERO;
    @Column(nullable = false)
    private int matchedCount;
    @Column(nullable = false)
    private int exceptionCount;

    @Column(nullable = false)
    private OffsetDateTime importedAt;
    @Column(nullable = false)
    private String importedByName;

    private OffsetDateTime closedAt;
    private String closedByName;
}
