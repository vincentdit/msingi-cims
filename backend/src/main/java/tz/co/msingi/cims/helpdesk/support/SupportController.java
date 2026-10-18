package tz.co.msingi.cims.helpdesk.support;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.helpdesk.support.SupportService.SupportRow;
import tz.co.msingi.cims.helpdesk.support.SupportService.SupportView;

import java.util.List;

/** Support from Msingi Technologies, for the institution's administrators. */
@Tag(name = "Msingi support (institution)")
@RestController
@RequestMapping("/api/msingi-support")
@PreAuthorize("hasRole('INSTITUTION_ADMIN')")
@RequiredArgsConstructor
public class SupportController {

    private final SupportService service;

    public record Close(Boolean satisfied) {
    }

    public record Escalate(String category, String priority, String note) {
    }

    @GetMapping("/tickets")
    public List<SupportRow> tickets() {
        return service.institutionTickets();
    }

    @PostMapping(value = "/tickets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SupportView raise(@RequestParam String category, @RequestParam(required = false) String priority, @RequestParam String subject,
                             @RequestParam String body, @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        return service.raise(category, priority, subject, body, files);
    }

    @GetMapping("/tickets/{id}")
    public SupportView ticket(@PathVariable Long id) {
        return service.institutionTicket(id);
    }

    @PostMapping(value = "/tickets/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SupportView reply(@PathVariable Long id, @RequestParam String body, @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        return service.institutionReply(id, body, files);
    }

    @PostMapping("/tickets/{id}/close")
    public SupportView close(@PathVariable Long id, @RequestBody(required = false) Close c) {
        return service.institutionClose(id, c == null ? null : c.satisfied());
    }

    @GetMapping("/attachments/{id}")
    public ResponseEntity<byte[]> attachment(@PathVariable Long id) {
        return tz.co.msingi.cims.helpdesk.HelpdeskController.send(service.institutionAttachment(id));
    }

    /** Sends one of the institution's helpdesk tickets on to Msingi. */
    @PostMapping("/escalate/{helpdeskTicketId}")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportView escalate(@PathVariable Long helpdeskTicketId, @RequestBody Escalate e) {
        return service.escalate(helpdeskTicketId, e.category(), e.priority(), e.note());
    }
}
