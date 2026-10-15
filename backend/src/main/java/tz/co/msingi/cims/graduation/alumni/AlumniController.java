package tz.co.msingi.cims.graduation.alumni;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tz.co.msingi.cims.common.exception.TooManyRequestsException;
import tz.co.msingi.cims.common.web.RateLimiter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Tag(name = "Alumni and tracer surveys")
@RestController
@RequiredArgsConstructor
public class AlumniController {

    private static final String READ = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','DEPUTY_PRINCIPAL_ACADEMIC','MANAGEMENT_VIEWER')";
    private static final String WRITE = "hasAnyRole('INSTITUTION_ADMIN','REGISTRAR')";
    private final AlumniService service;
    private final RateLimiter limiter = new RateLimiter(60, 60_000, java.time.Clock.systemUTC());

    @GetMapping("/api/alumni")
    @PreAuthorize(READ)
    public List<AlumniService.AlumnusView> list(@RequestParam(required = false) Long programmeId, @RequestParam(required = false) Long graduationId,
                                                @RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        return service.alumni(programmeId, graduationId, status, q);
    }

    @PutMapping("/api/alumni/{studentId}")
    @PreAuthorize(WRITE)
    public AlumniService.AlumnusView save(@PathVariable Long studentId, @Valid @RequestBody AlumniService.Profile p) {
        return service.save(studentId, p, "STAFF");
    }

    @GetMapping(value = "/api/alumni/export", produces = "text/csv")
    @PreAuthorize(READ)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Long programmeId, @RequestParam(required = false) Long graduationId,
                                         @RequestParam(required = false) String status) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"alumni.csv\"")
                .body(("﻿" + service.csv(programmeId, graduationId, status)).getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/api/student/alumni")
    @PreAuthorize("hasRole('STUDENT')")
    public AlumniService.AlumnusView mine() {
        return service.mine();
    }

    @PutMapping("/api/student/alumni")
    @PreAuthorize("hasRole('STUDENT')")
    public AlumniService.AlumnusView saveMine(@Valid @RequestBody AlumniService.Profile p) {
        return service.saveMine(p);
    }

    @GetMapping("/api/tracer-surveys")
    @PreAuthorize(READ)
    public List<AlumniService.SurveyView> surveys() {
        return service.surveys();
    }

    @PostMapping("/api/tracer-surveys")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public AlumniService.SurveyView create(@Valid @RequestBody AlumniService.SurveyRequest r) {
        return service.create(r);
    }

    @GetMapping("/api/tracer-surveys/{id}")
    @PreAuthorize(READ)
    public AlumniService.SurveyView survey(@PathVariable Long id) {
        return service.survey(id);
    }

    @PostMapping("/api/tracer-surveys/{id}/open")
    @PreAuthorize(WRITE)
    public AlumniService.SurveyView open(@PathVariable Long id) {
        return service.open(id);
    }

    @PostMapping("/api/tracer-surveys/{id}/close")
    @PreAuthorize(WRITE)
    public AlumniService.SurveyView close(@PathVariable Long id) {
        return service.close(id);
    }

    @GetMapping("/api/public/tracer/{token}")
    public AlumniService.PublicSurvey publicSurvey(@PathVariable String token, HttpServletRequest http) {
        limit(http);
        return service.publicSurvey(token);
    }

    @PostMapping("/api/public/tracer/{token}")
    public AlumniService.PublicSurvey answer(@PathVariable String token, @RequestBody Map<String, String> answers, HttpServletRequest http) {
        limit(http);
        return service.answer(token, answers);
    }

    private void limit(HttpServletRequest http) {
        if (!limiter.tryAcquire(http.getRemoteAddr())) throw new TooManyRequestsException("Too many requests. Try again in a minute.");
    }
}
