package tz.co.msingi.cims.finance.sponsors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

/** A third party that pays students' fees, e.g. HESLB, a government scheme or an employer (FR-FIN-09). */
@Getter
@Setter
@Entity
@Table(name = "sponsors")
public class Sponsor extends BaseEntity {

    public enum Kind { GOVERNMENT, LOAN_BOARD, COMPANY, NGO, RELIGIOUS, OTHER }

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    private String contactName;
    private String contactEmail;
    private String contactPhone;
    private String address;

    @Column(nullable = false)
    private boolean active = true;
}
