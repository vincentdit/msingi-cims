package tz.co.msingi.cims.helpdesk;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.helpdesk.HelpdeskArticleService.ArticleView;
import tz.co.msingi.cims.helpdesk.HelpdeskCategoryService.CategoryView;
import tz.co.msingi.cims.helpdesk.HelpdeskFiles.FileContent;
import tz.co.msingi.cims.helpdesk.HelpdeskService.TicketRow;
import tz.co.msingi.cims.helpdesk.HelpdeskService.TicketView;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Help for people with an account: applicants, students and staff raise and follow their own requests and read the knowledge base. */
@Tag(name = "Help (requesters)")
@RestController
@RequestMapping("/api/help")
@PreAuthorize("isAuthenticated() and !hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
public class HelpdeskController {

    private final HelpdeskService service;
    private final HelpdeskCategoryService categories;
    private final HelpdeskArticleService articles;

    public record Close(Boolean satisfied) {
    }

    public record Vote(boolean helpful) {
    }

    public record Draft(String subject, String body) {
    }

    public record Solved(Long articleId) {
    }

    @Operation(summary = "Answers likely to solve what is being written, and the category guessed")
    @PostMapping("/suggest")
    public tz.co.msingi.cims.helpdesk.assistant.HelpdeskAssistant.Suggestion suggest(@RequestBody Draft d) {
        return service.suggestFor(type(), d.subject(), d.body());
    }

    @PostMapping("/suggest/solved")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void solved(@RequestBody Solved s) {
        service.solvedBeforeAsking(type(), s.articleId());
    }

    @PostMapping("/tickets/{id}/assistant")
    public TicketView assistantFeedback(@PathVariable Long id, @RequestBody Vote v) {
        return service.requesterAssistantFeedback(id, v.helpful());
    }

    @GetMapping("/categories")
    public List<CategoryView> categories() {
        return categories.forAudience(type());
    }

    @GetMapping("/articles")
    public List<ArticleView> articles(@RequestParam(required = false) String q, @RequestParam(required = false) Long categoryId) {
        return articles.forReader(type(), q, categoryId);
    }

    @GetMapping("/articles/{id}")
    public ArticleView article(@PathVariable Long id) {
        return articles.read(id, type());
    }

    @PostMapping("/articles/{id}/vote")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void vote(@PathVariable Long id, @RequestBody Vote v) {
        articles.vote(id, type(), v.helpful());
    }

    @GetMapping("/tickets")
    public List<TicketRow> mine() {
        return service.mine();
    }

    @Operation(summary = "Raise a request (multipart: categoryId, subject, body, up to 3 PDF/JPEG/PNG files)")
    @PostMapping(value = "/tickets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TicketView create(@RequestParam Long categoryId, @RequestParam String subject, @RequestParam String body,
                             @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        return service.create(categoryId, subject, body, files);
    }

    @GetMapping("/tickets/{id}")
    public TicketView ticket(@PathVariable Long id) {
        return service.myTicket(id);
    }

    @PostMapping(value = "/tickets/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TicketView reply(@PathVariable Long id, @RequestParam String body, @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        return service.requesterReply(id, body, files);
    }

    @PostMapping("/tickets/{id}/close")
    public TicketView close(@PathVariable Long id, @RequestBody(required = false) Close c) {
        return service.requesterClose(id, c == null ? null : c.satisfied());
    }

    @GetMapping("/attachments/{id}")
    public ResponseEntity<byte[]> attachment(@PathVariable Long id) {
        return send(service.myAttachment(id));
    }

    private String type() {
        return service.requesterType();
    }

    public static ResponseEntity<byte[]> send(FileContent f) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(f.data());
    }
}
