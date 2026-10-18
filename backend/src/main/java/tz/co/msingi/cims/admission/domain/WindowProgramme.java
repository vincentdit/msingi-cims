package tz.co.msingi.cims.admission.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.admission.eligibility.EntryRules;
import tz.co.msingi.cims.common.BaseEntity;

/** A programme offered in an admission window, with capacity and entry requirements. */
@Getter
@Setter
@Entity
@Table(name = "window_programmes")
public class WindowProgramme extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "window_id")
    private AdmissionWindow window;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "programme_id")
    private Programme programme;

    @Column(nullable = false)
    private int capacity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private EntryRules entryRules;

    /** Selling points shown on the programme page and the advert poster ("Hostel bure"…), one per line. */
    @Column(length = 500)
    private String highlights;
}
