package tz.co.msingi.cims.platform.branding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

/** A photo of the institution shown on its portal, e.g. the main campus on the admission home page. */
@Getter
@Setter
@Entity
@Table(name = "institution_photos")
public class InstitutionPhoto extends BaseEntity {

    public enum Kind { CAMPUS, LOGO }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private byte[] data;

    @Column(nullable = false)
    private int sizeBytes;

    private Integer width;
    private Integer height;
    private String caption;

    @Column(nullable = false)
    private String uploadedByName;
}
