package tz.co.msingi.cims.exams;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

/** The institution's choices for the results workflow (BR-17). */
@Getter
@Setter
@Entity
@Table(name = "exam_settings")
public class ExamSettings extends BaseEntity {

    /** Whether module results go to a Department Board after the Head of Department. */
    @Column(nullable = false)
    private boolean useDepartmentBoard = true;
}
