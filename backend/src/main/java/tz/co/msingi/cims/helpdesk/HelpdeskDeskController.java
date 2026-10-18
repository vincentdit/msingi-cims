package tz.co.msingi.cims.helpdesk;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.helpdesk.HelpdeskArticleService.ArticleRequest;
import tz.co.msingi.cims.helpdesk.HelpdeskArticleService.ArticleView;
import tz.co.msingi.cims.helpdesk.HelpdeskCategoryService.CategoryRequest;
import tz.co.msingi.cims.helpdesk.HelpdeskCategoryService.CategoryView;
import tz.co.msingi.cims.helpdesk.HelpdeskService.Agent;
import tz.co.msingi.cims.helpdesk.HelpdeskService.DeskPage;
import tz.co.msingi.cims.helpdesk.HelpdeskService.Performance;
import tz.co.msingi.cims.helpdesk.HelpdeskService.TicketView;

import java.util.List;
import java.util.Map;

/** The helpdesk for staff: the queue of their offices, answering and managing tickets, categories and the knowledge base. */
@Tag(name = "Helpdesk (staff)")
@RestController
@RequestMapping("/api/helpdesk")
@PreAuthorize(HelpdeskDeskController.STAFF)
@RequiredArgsConstructor
public class HelpdeskDeskController {

    static final String STAFF = "isAuthenticated() and !hasRole('PLATFORM_ADMIN') and !hasRole('APPLICANT') and !hasRole('STUDENT')";

    private final HelpdeskService service;
    private final HelpdeskCategoryService categories;
    private final HelpdeskArticleService articles;

    public record Assign(Long userId) {
    }

    public record Route(Long categoryId) {
    }

    public record Status(String status) {
    }

    public record Priority(String priority) {
    }

    @GetMapping("/tickets")
    public DeskPage tickets(@RequestParam(defaultValue = "open") String view, @RequestParam(required = false) String status,
                            @RequestParam(required = false) Long categoryId, @RequestParam(required = false) String q,
                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.desk(view, status, categoryId, q, page, size);
    }

    @GetMapping("/counts")
    public Map<String, Long> counts() {
        return service.counts();
    }

    @GetMapping("/tickets/{id}")
    public TicketView ticket(@PathVariable Long id) {
        return service.deskTicket(id);
    }

    @PostMapping(value = "/tickets/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TicketView message(@PathVariable Long id, @RequestParam String body, @RequestParam(defaultValue = "false") boolean internal,
                              @RequestParam(required = false) String statusAfter, @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        return service.staffMessage(id, body, internal, statusAfter, files);
    }

    @PostMapping("/tickets/{id}/assign")
    public TicketView assign(@PathVariable Long id, @RequestBody Assign a) {
        return service.assign(id, a.userId());
    }

    @PostMapping("/tickets/{id}/route")
    public ResponseEntity<TicketView> route(@PathVariable Long id, @RequestBody Route r) {
        TicketView v = service.route(id, r.categoryId());
        return v == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(v);
    }

    @PostMapping("/tickets/{id}/status")
    public TicketView status(@PathVariable Long id, @RequestBody Status s) {
        return service.setStatus(id, s.status());
    }

    @PostMapping("/tickets/{id}/priority")
    public TicketView priority(@PathVariable Long id, @RequestBody Priority p) {
        return service.setPriority(id, p.priority());
    }

    @GetMapping("/tickets/{id}/suggestions")
    public tz.co.msingi.cims.helpdesk.assistant.HelpdeskAssistant.StaffSuggestions suggestions(@PathVariable Long id) {
        return service.suggestions(id);
    }

    @PostMapping("/suggestions/{learnedId}/used")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void used(@PathVariable Long learnedId) {
        service.usedSuggestion(learnedId);
    }

    @GetMapping("/gaps")
    public List<tz.co.msingi.cims.helpdesk.assistant.HelpdeskAssistant.Gap> gaps(@RequestParam(defaultValue = "90") int days) {
        return service.gaps(days);
    }

    @GetMapping("/tickets/{id}/agents")
    public List<Agent> agents(@PathVariable Long id) {
        return service.agents(id);
    }

    @GetMapping("/attachments/{id}")
    public ResponseEntity<byte[]> attachment(@PathVariable Long id) {
        return HelpdeskController.send(service.staffAttachment(id));
    }

    @GetMapping("/performance")
    public Performance performance(@RequestParam(defaultValue = "30") int days) {
        return service.performance(days);
    }

    // ---------------------------------------------------------------- settings

    @GetMapping("/categories")
    public List<CategoryView> categories() {
        return categories.all();
    }

    @PostMapping("/categories")
    @PreAuthorize("hasRole('INSTITUTION_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryView addCategory(@Valid @RequestBody CategoryRequest r) {
        return categories.create(r);
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasRole('INSTITUTION_ADMIN')")
    public CategoryView updateCategory(@PathVariable Long id, @Valid @RequestBody CategoryRequest r) {
        return categories.update(id, r);
    }

    @GetMapping("/articles")
    public List<ArticleView> articles() {
        return articles.all();
    }

    @PostMapping("/articles")
    @ResponseStatus(HttpStatus.CREATED)
    public ArticleView addArticle(@Valid @RequestBody ArticleRequest r) {
        return articles.create(r);
    }

    @PutMapping("/articles/{id}")
    public ArticleView updateArticle(@PathVariable Long id, @Valid @RequestBody ArticleRequest r) {
        return articles.update(id, r);
    }

    @DeleteMapping("/articles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteArticle(@PathVariable Long id) {
        articles.delete(id);
    }
}
