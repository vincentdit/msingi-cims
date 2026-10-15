package tz.co.msingi.cims.platform.imports;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Tag(name = "Data import")
@RestController
@RequestMapping("/api/imports")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','REGISTRAR','EXAMINATIONS_OFFICER','BURSAR','ACCOUNTANT','ADMISSIONS_OFFICER')")
public class ImportController {

    private final ImportService service;

    public record Decision(String note) {
    }

    @Operation(summary = "Upload a CSV or Excel file: it is checked and kept until signed off")
    @PostMapping(value = "/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ImportService.BatchView upload(@PathVariable ImportService.Kind kind, @RequestPart("file") MultipartFile file,
                                          @RequestParam(required = false) Long windowId, @RequestParam(required = false) String externalBody) throws IOException {
        return service.upload(kind, file.getOriginalFilename(), file.getBytes(), windowId, externalBody);
    }

    @GetMapping
    public List<ImportService.BatchView> list(@RequestParam(required = false) ImportService.Kind kind) {
        return service.list(kind);
    }

    @GetMapping("/external-bodies")
    public List<String> bodies() {
        return ImportService.EXTERNAL_BODIES;
    }

    @GetMapping("/batches/{id}")
    public ImportService.BatchView get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/batches/{id}/apply")
    public ImportService.BatchView apply(@PathVariable Long id, @RequestBody(required = false) Decision d) {
        return service.apply(id, d == null ? null : d.note());
    }

    @PostMapping("/batches/{id}/discard")
    public ImportService.BatchView discard(@PathVariable Long id) {
        return service.discard(id);
    }

    @GetMapping(value = "/batches/{id}/errors.csv", produces = "text/csv")
    public ResponseEntity<byte[]> errors(@PathVariable Long id) {
        return csv("import-problems-" + id + ".csv", service.errorReport(id));
    }

    @GetMapping(value = "/templates/{kind}", produces = "text/csv")
    public ResponseEntity<byte[]> template(@PathVariable ImportService.Kind kind) {
        return csv(kind.name().toLowerCase() + "-template.csv", ImportService.template(kind));
    }

    private static ResponseEntity<byte[]> csv(String name, String body) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(("﻿" + body).getBytes(StandardCharsets.UTF_8));
    }
}
