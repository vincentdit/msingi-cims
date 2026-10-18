package tz.co.msingi.cims.helpdesk;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tz.co.msingi.cims.common.exception.TooManyRequestsException;
import tz.co.msingi.cims.common.web.RateLimiter;
import tz.co.msingi.cims.helpdesk.HelpdeskArticleService.ArticleView;
import tz.co.msingi.cims.helpdesk.HelpdeskCategoryService.CategoryView;
import tz.co.msingi.cims.helpdesk.HelpdeskService.GuestCreated;
import tz.co.msingi.cims.helpdesk.HelpdeskService.TicketView;

import java.time.Clock;
import java.util.List;

/**
 * The public help page: people without an account (for example stuck at the NECTA look-up) read the public articles, ask for
 * help with their name and mobile number, and follow the request with its number and the code sent by SMS. Rate-limited.
 */
@Tag(name = "Help (public)")
@RestController
@RequestMapping("/api/public/help")
public class HelpdeskPublicController {

    private final HelpdeskService service;
    private final HelpdeskCategoryService categories;
    private final HelpdeskArticleService articles;
    private final RateLimiter raise;
    private final RateLimiter follow;

    public HelpdeskPublicController(HelpdeskService service, HelpdeskCategoryService categories, HelpdeskArticleService articles,
                                    @org.springframework.beans.factory.annotation.Value("${cims.helpdesk.public-requests-per-hour:5}") int perHour,
                                    @org.springframework.beans.factory.annotation.Value("${cims.helpdesk.public-lookups-per-minute:20}") int perMinute) {
        this.service = service;
        this.categories = categories;
        this.articles = articles;
        this.raise = new RateLimiter(perHour, 60 * 60_000, Clock.systemUTC());
        this.follow = new RateLimiter(perMinute, 60_000, Clock.systemUTC());
    }

    public record Access(String ticketNo, String code, Boolean satisfied) {
    }

    public record Vote(boolean helpful) {
    }

    public record Draft(String subject, String body) {
    }

    public record Solved(Long articleId) {
    }

    public record Feedback(String ticketNo, String code, boolean helpful) {
    }

    @PostMapping("/suggest")
    public tz.co.msingi.cims.helpdesk.assistant.HelpdeskAssistant.Suggestion suggest(@RequestBody Draft d, HttpServletRequest req) {
        limit(follow, req);
        return service.suggestFor("GUEST", d.subject(), d.body());
    }

    @PostMapping("/suggest/solved")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void solved(@RequestBody Solved s, HttpServletRequest req) {
        limit(follow, req);
        service.solvedBeforeAsking("GUEST", s.articleId());
    }

    @PostMapping("/tickets/assistant")
    public TicketView assistantFeedback(@RequestBody Feedback f, HttpServletRequest req) {
        limit(follow, req);
        return service.guestAssistantFeedback(f.ticketNo(), f.code(), f.helpful());
    }

    @GetMapping("/categories")
    public List<CategoryView> categories() {
        return categories.forAudience("GUEST");
    }

    @GetMapping("/articles")
    public List<ArticleView> articles(@RequestParam(required = false) String q, @RequestParam(required = false) Long categoryId) {
        return articles.forReader("PUBLIC", q, categoryId);
    }

    @GetMapping("/articles/{id}")
    public ArticleView article(@PathVariable Long id) {
        return articles.read(id, "PUBLIC");
    }

    @PostMapping("/articles/{id}/vote")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void vote(@PathVariable Long id, @RequestBody Vote v, HttpServletRequest req) {
        limit(follow, req);
        articles.vote(id, "PUBLIC", v.helpful());
    }

    @Operation(summary = "Ask for help without an account (multipart: name, phone, email, categoryId, subject, body, files); at most 5 an hour")
    @PostMapping(value = "/tickets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public GuestCreated create(@RequestParam String name, @RequestParam String phone, @RequestParam(required = false) String email,
                               @RequestParam Long categoryId, @RequestParam String subject, @RequestParam String body,
                               @RequestParam(value = "files", required = false) List<MultipartFile> files, HttpServletRequest req) {
        limit(raise, req);
        return service.guestCreate(name, phone, email, categoryId, subject, body, files);
    }

    @PostMapping("/tickets/open")
    public TicketView open(@RequestBody Access a, HttpServletRequest req) {
        limit(follow, req);
        return service.guestOpen(a.ticketNo(), a.code());
    }

    @PostMapping(value = "/tickets/reply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TicketView reply(@RequestParam String ticketNo, @RequestParam String code, @RequestParam String body,
                            @RequestParam(value = "files", required = false) List<MultipartFile> files, HttpServletRequest req) {
        limit(follow, req);
        return service.guestReply(ticketNo, code, body, files);
    }

    @PostMapping("/tickets/close")
    public TicketView close(@RequestBody Access a, HttpServletRequest req) {
        limit(follow, req);
        return service.guestClose(a.ticketNo(), a.code(), a.satisfied());
    }

    @PostMapping("/tickets/attachments/{id}")
    public ResponseEntity<byte[]> attachment(@PathVariable Long id, @RequestBody Access a, HttpServletRequest req) {
        limit(follow, req);
        return HelpdeskController.send(service.guestAttachment(a.ticketNo(), a.code(), id));
    }

    /** The client address as the server sees it (see LetterController: forwarded headers are not trusted here). */
    private static void limit(RateLimiter l, HttpServletRequest req) {
        if (!l.tryAcquire(req.getRemoteAddr())) throw new TooManyRequestsException("Too many requests. Please wait a little and try again.");
    }
}
