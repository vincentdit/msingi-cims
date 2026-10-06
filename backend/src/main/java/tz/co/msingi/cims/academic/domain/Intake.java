package tz.co.msingi.cims.academic.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

@Getter
@Setter
@Entity
@Table(name = "intakes")
public class Intake extends BaseEntity {

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private short startMonth;

    @Column(nullable = false)
    private boolean active = true;
}
