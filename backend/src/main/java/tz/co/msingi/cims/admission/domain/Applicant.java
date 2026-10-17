package tz.co.msingi.cims.admission.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;
import tz.co.msingi.cims.platform.security.UserAccount;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "applicants")
public class Applicant extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Column(nullable = false)
    private String firstName;

    private String middleName;

    @Column(nullable = false)
    private String surname;

    @Column(columnDefinition = "char(1)")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    private String sex;
    private LocalDate dateOfBirth;
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
    private String ward;
    private String street;
    private String nextOfKinEmail;
    private String nextOfKinAddress;
    private String nextOfKinRegion;
    private String nextOfKinDistrict;
    private String nextOfKinWard;
    private String nextOfKinStreet;

    /** Education history: primary school (from NECTA's PSLE list or typed) and secondary schools (from NECTA results when fetched). */
    private String primarySchool;
    private String primarySchoolCode;
    private String olevelSchool;
    @Column(nullable = false)
    private boolean olevelFromNecta;
    private String alevelSchool;
    @Column(nullable = false)
    private boolean alevelFromNecta;

    /** The Form Four (CSEE) index number and year the applicant registered with; names come from NECTA. */
    private String nectaIndexNumber;
    private Short nectaExamYear;

    /** NECTA's PREM number for the candidate (from the CSEE results). */
    private String premNumber;

    @Column(nullable = false)
    private boolean namesFromNecta;

    @Column(nullable = false)
    private OffsetDateTime privacyConsentAt;

    @Column(nullable = false)
    private String privacyNoticeVersion;

    public String fullName() {
        return firstName + (middleName == null || middleName.isBlank() ? "" : " " + middleName) + " " + surname;
    }

    /** Fields required before an application can be submitted. */
    public List<String> missingProfileFields() {
        List<String> missing = new ArrayList<>();
        if (sex == null) missing.add("sex");
        if (dateOfBirth == null) missing.add("dateOfBirth");
        if (nationality == null || nationality.isBlank()) missing.add("nationality");
        if (region == null || region.isBlank()) missing.add("region");
        if (nextOfKinName == null || nextOfKinName.isBlank()) missing.add("nextOfKinName");
        if (nextOfKinPhone == null || nextOfKinPhone.isBlank()) missing.add("nextOfKinPhone");
        return missing;
    }
}
