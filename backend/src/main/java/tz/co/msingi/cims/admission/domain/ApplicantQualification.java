package tz.co.msingi.cims.admission.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.admission.eligibility.EligibilityEngine;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** A CSEE/ACSEE result (from NECTA) or a prior NTA/NVA award held by an applicant. */
@Getter
@Setter
@Entity
@Table(name = "applicant_qualifications")
public class ApplicantQualification extends BaseEntity {

    public enum Type { CSEE, ACSEE, NTA, NVA, OTHER }

    /** Level of an award that is not NTA or NVA (declared by the applicant). */
    public enum OtherLevel { CERTIFICATE, DIPLOMA, ADVANCED_DIPLOMA, DEGREE, MASTERS, OTHER }

    public enum Source { NECTA, NACTVET, MANUAL }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applicant_id")
    private Applicant applicant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false)
    private String indexNumber;

    private Short examYear;
    private Short awardLevel;
    private String centreName;
    private String division;
    private Short points;
    private String candidateName;
    private String awardName;
    private String institution;
    private java.math.BigDecimal gpa;
    private String classAward;

    @Enumerated(EnumType.STRING)
    private OtherLevel otherLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    @Column(nullable = false)
    private boolean verified;

    private OffsetDateTime verifiedAt;

    @OneToMany(mappedBy = "qualification", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<QualificationSubject> subjects = new ArrayList<>();

    public void addSubject(String code, String name, String grade, Short credits) {
        addSubject(code, name, grade);
        subjects.get(subjects.size() - 1).setCredits(credits);
    }

    public void addSubject(String code, String name, String grade) {
        QualificationSubject s = new QualificationSubject();
        s.setQualification(this);
        s.setSubjectCode(code);
        s.setSubjectName(name);
        s.setGrade(grade);
        subjects.add(s);
    }

    public EligibilityEngine.Qualification toEngine() {
        return new EligibilityEngine.Qualification(type.name(), awardLevel == null ? null : awardLevel.intValue(), verified,
                subjects.stream().map(s -> new EligibilityEngine.Qualification.Subject(s.getSubjectCode(), s.getSubjectName(), s.getGrade())).toList());
    }
}
