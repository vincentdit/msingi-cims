package tz.co.msingi.cims.registry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tz.co.msingi.cims.academic.domain.AcademicYear;
import tz.co.msingi.cims.academic.domain.Intake;
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The student master record (BR-09): created from the accepted application, reused by every office. */
@Getter
@Setter
@Entity
@Table(name = "students")
public class Student extends BaseEntity {

    public enum Status { ADMITTED, ACTIVE, POSTPONED, SUSPENDED, DISCONTINUED, WITHDRAWN, DECEASED, COMPLETED, GRADUATED }

    private Long applicantId;
    private Long applicationId;
    private Long userId;

    /** Never changes once assigned (enforced by a database trigger, BRU-06). */
    @Column(nullable = false, updatable = false)
    private String registrationNo;

    @Column(nullable = false)
    private String firstName;
    private String middleName;
    @Column(nullable = false)
    private String surname;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, columnDefinition = "char(1)")
    private String sex;

    @Column(nullable = false)
    private LocalDate dateOfBirth;
    @Column(nullable = false)
    private String nationality;
    @Column(nullable = false)
    private String phone;
    private String email;
    private String region;
    private String district;
    private String postalAddress;
    private String disability;
    private String nextOfKinName;
    private String nextOfKinPhone;
    private String nextOfKinRelation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "programme_id")
    private Programme programme;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_academic_year_id")
    private AcademicYear entryAcademicYear;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "intake_id")
    private Intake intake;

    @Column(nullable = false)
    private short yearOfStudy = 1;

    /** The curriculum version the student follows (set at the first semester registration, then kept). */
    private Long curriculumId;

    private String nactvetVerificationNo;
    private String admissionLetterNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ADMITTED;

    @Column(nullable = false)
    private OffsetDateTime admittedAt;
    @Column(nullable = false)
    private String admittedByName;

    public String fullName() {
        return Stream.of(firstName, middleName, surname).filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(" "));
    }
}
