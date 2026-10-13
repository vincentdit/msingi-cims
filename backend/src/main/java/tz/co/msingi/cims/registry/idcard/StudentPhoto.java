package tz.co.msingi.cims.registry.idcard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

/** A student's passport-style photo, used on their ID card. */
@Getter
@Setter
@Entity
@Table(name = "student_photos")
public class StudentPhoto extends BaseEntity {

    @Column(nullable = false, unique = true)
    private Long studentId;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private byte[] data;

    @Column(nullable = false)
    private int sizeBytes;

    @Column(nullable = false)
    private String uploadedByName;
}
