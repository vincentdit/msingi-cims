package tz.co.msingi.cims.hostel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.util.ArrayList;
import java.util.List;

/** A room: block, floor, type, gender, fee category, status and its beds. */
@Getter
@Setter
@Entity
@Table(name = "hostel_rooms")
public class HostelRoom extends BaseEntity {

    public enum RoomType { SINGLE, DOUBLE, TRIPLE, QUAD, DORMITORY }

    public enum Gender { FEMALE, MALE }

    public enum Status { AVAILABLE, MAINTENANCE, CLOSED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hostel_id")
    private Hostel hostel;

    @Column(nullable = false)
    private String block = "";

    @Column(nullable = false)
    private short floor;

    @Column(nullable = false)
    private String roomNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoomType roomType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gender gender;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fee_category_id")
    private HostelFeeCategory feeCategory;

    @Column(nullable = false)
    private boolean accessible;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.AVAILABLE;

    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("label ASC")
    private List<HostelBed> beds = new ArrayList<>();

    public String label() {
        return (block.isBlank() ? "" : block + " · ") + "Room " + roomNo;
    }
}
