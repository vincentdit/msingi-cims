package tz.co.msingi.cims.registry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** First registration at reporting: originals checked against the joining instructions, then Registry approval (BP-05 steps 4–5). */
@Getter
@Setter
@Entity
@Table(name = "first_registrations")
public class FirstRegistration extends BaseEntity {

    public enum Status { PENDING, APPROVED }

    public record DocumentCheck(String item, boolean verified) {
    }

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private Student student;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<DocumentCheck> documents = new ArrayList<>();

    private OffsetDateTime reportedAt;
    private String remarks;
    private String checkedByName;
    private OffsetDateTime approvedAt;
    private Long approvedBy;
    private String approvedByName;
}
