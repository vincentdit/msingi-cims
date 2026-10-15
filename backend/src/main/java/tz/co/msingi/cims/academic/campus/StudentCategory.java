package tz.co.msingi.cims.academic.campus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

/** A student category priced separately in fee structures, such as regular, evening, weekend or distance (FR-FIN-01). */
@Getter
@Setter
@Entity
@Table(name = "student_categories")
public class StudentCategory extends BaseEntity {
    @Column(nullable = false)
    private String code;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private boolean active = true;
}
