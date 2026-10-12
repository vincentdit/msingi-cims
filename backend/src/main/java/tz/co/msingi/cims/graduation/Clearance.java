package tz.co.msingi.cims.graduation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** A finalist's clearance across the offices (UC-18); cleared once every office has cleared them. */
@Getter
@Setter
@Entity
@Table(name = "clearances")
public class Clearance extends BaseEntity {

    public enum Status { IN_PROGRESS, CLEARED }

    @Column(name = "student_id", nullable = false, updatable = false)
    private Long studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.IN_PROGRESS;

    @Column(nullable = false, updatable = false)
    private String startedByName;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime startedAt = OffsetDateTime.now();

    private OffsetDateTime clearedAt;

    @OneToMany(mappedBy = "clearance", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ClearanceItem> items = new ArrayList<>();
}
