package tz.co.msingi.cims.exams;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.TenantScopedEntity;

import java.math.BigDecimal;

/** Award classification by GPA (e.g. First Class 3.5–4.0). */
@Getter
@Setter
@Entity
@Table(name = "classification_bands")
public class ClassificationBand extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "regulation_set_id")
    private RegulationSet regulationSet;

    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private BigDecimal minGpa;
    @Column(nullable = false)
    private BigDecimal maxGpa;
}
