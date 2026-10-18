package tz.co.msingi.cims.helpdesk.support;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.helpdesk.support.SupportService.Agent;
import tz.co.msingi.cims.helpdesk.support.SupportService.SupportPage;
import tz.co.msingi.cims.helpdesk.support.SupportService.SupportView;

import java.util.List;
import java.util.Map;

/** The Msingi support desk in the platform console: tickets from every institution. */
@Tag(name = "Msingi support (platform)")
@RestController
@RequestMapping("/api/platform/support")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
public class PlatformSupportController {

    private final SupportService service;

    public record Assign(Long platformUserId) {
    }

    public record Status(String status) {
    }

    public record Priority(String priority) {
    }

    @GetMapping("/tickets")
    public SupportPage tickets(@RequestParam(defaultValue = "open") String view, @RequestParam(required = false) Long tenantId,
                               @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.platformTickets(view, tenantId, q, page, size);
    }

    @GetMapping("/counts")
    public Map<String, Long> counts() {
        return service.platformCounts();
    }

    @GetMapping("/tickets/{id}")
    public SupportView ticket(@PathVariable Long id) {
        return service.platformTicket(id);
    }

    @PostMapping(value = "/tickets/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SupportView message(@PathVariable Long id, @RequestParam String body, @RequestParam(defaultValue = "false") boolean internal,
                               @RequestParam(required = false) String statusAfter, @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        return service.platformMessage(id, body, internal, statusAfter, files);
    }

    @PostMapping("/tickets/{id}/assign")
    public SupportView assign(@PathVariable Long id, @RequestBody Assign a) {
        return service.platformAssign(id, a.platformUserId());
    }

    @PostMapping("/tickets/{id}/status")
    public SupportView status(@PathVariable Long id, @RequestBody Status s) {
        return service.platformStatus(id, s.status());
    }

    @PostMapping("/tickets/{id}/priority")
    public SupportView priority(@PathVariable Long id, @RequestBody Priority p) {
        return service.platformPriority(id, p.priority());
    }

    @GetMapping("/agents")
    public List<Agent> agents() {
        return service.platformAgents();
    }

    @GetMapping("/attachments/{id}")
    public ResponseEntity<byte[]> attachment(@PathVariable Long id) {
        return tz.co.msingi.cims.helpdesk.HelpdeskController.send(service.platformAttachment(id));
    }
}
