package tz.co.msingi.cims.portal;

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
import tz.co.msingi.cims.academic.domain.Programme;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;

/** A notice on the student portal (FR-STU-01), for every student or a programme and/or year of study. */
@Getter
@Setter
@Entity
@Table(name = "notices")
public class Notice extends BaseEntity {

    public enum Category { GENERAL, ACADEMIC, FINANCE, EXAMINATION, HOSTEL, EVENT }

    public enum Status { DRAFT, PUBLISHED, WITHDRAWN }

    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category = Category.GENERAL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "programme_id")
    private Programme programme;

    private Short yearOfStudy;
    private boolean pinned;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    private OffsetDateTime publishAt;
    private OffsetDateTime expiresAt;
    private int smsSentCount;

    @Column(nullable = false)
    private String createdByName;
    private String publishedByName;
    private OffsetDateTime withdrawnAt;
    private String withdrawnByName;

    /** Published, started and not expired. */
    public boolean isLive(OffsetDateTime now) {
        return status == Status.PUBLISHED && publishAt != null && !publishAt.isAfter(now) && (expiresAt == null || expiresAt.isAfter(now));
    }
}
