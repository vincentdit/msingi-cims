package tz.co.msingi.cims.exams;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.academic.curriculum.Semester;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Supplementary and special examinations of a semester (BP-10): fees per module and the registration deadline. */
@Getter
@Setter
@Entity
@Table(name = "resit_sessions")
public class ResitSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "semester_id")
    private Semester semester;

    @Column(nullable = false)
    private BigDecimal supplementaryFee = BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal specialFee = BigDecimal.ZERO;
    @Column(nullable = false)
    private LocalDate registrationCloses;
    private String examPeriod;
    private String createdByName;
}
