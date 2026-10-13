package tz.co.msingi.cims.platform.branding;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.common.exception.NotFoundException;
import tz.co.msingi.cims.platform.branding.InstitutionPhoto.Kind;
import tz.co.msingi.cims.platform.branding.InstitutionPhotoService.PhotoView;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/** The institution's photos: served publicly to the portal, managed by the institution administrator. */
@Tag(name = "Institution photos")
@RestController
@RequiredArgsConstructor
public class InstitutionPhotoController {

    private static final String ADMIN = "hasRole('INSTITUTION_ADMIN')";

    private final InstitutionPhotoService service;

    @Operation(summary = "The institution's photo (e.g. the campus) for the portal")
    @GetMapping("/api/public/institution/photos/{kind}")
    public ResponseEntity<byte[]> image(@PathVariable String kind) {
        InstitutionPhoto p = service.image(kind(kind));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(p.getContentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .eTag("\"" + p.getVersion() + "\"")
                .body(p.getData());
    }

    @GetMapping("/api/admin/institution/photos/{kind}")
    @PreAuthorize(ADMIN)
    public ResponseEntity<PhotoView> view(@PathVariable String kind) {
        return service.view(kind(kind)).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @Operation(summary = "Upload or replace a photo (JPEG, PNG or WebP, up to 5 MB)")
    @PutMapping(value = "/api/admin/institution/photos/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ADMIN)
    public PhotoView upload(@PathVariable String kind, @RequestPart("file") MultipartFile file,
                            @RequestParam(value = "caption", required = false) String caption) throws IOException {
        return service.upload(kind(kind), file.getBytes(), caption);
    }

    @PatchMapping("/api/admin/institution/photos/{kind}")
    @PreAuthorize(ADMIN)
    public PhotoView caption(@PathVariable String kind, @RequestBody Map<String, String> body) {
        return service.caption(kind(kind), body.get("caption"));
    }

    @DeleteMapping("/api/admin/institution/photos/{kind}")
    @PreAuthorize(ADMIN)
    public ResponseEntity<Void> remove(@PathVariable String kind) {
        service.remove(kind(kind));
        return ResponseEntity.noContent().build();
    }

    private static Kind kind(String kind) {
        try {
            return Kind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("No such photo");
        }
    }
}
