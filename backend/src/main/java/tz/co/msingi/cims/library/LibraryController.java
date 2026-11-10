package tz.co.msingi.cims.library;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** The library (FR-LIB-01..05). */
@Tag(name = "Library")
@RestController
@RequiredArgsConstructor
public class LibraryController {

    static final String WRITE = "hasAnyRole('INSTITUTION_ADMIN','LIBRARIAN')";
    static final String READ = "hasAnyRole('INSTITUTION_ADMIN','LIBRARIAN','MANAGEMENT_VIEWER','AUDITOR')";

    private final LibraryService service;

    public record StatusRequest(String status, String note) {
    }

    public record NoteRequest(String note) {
    }

    public record BarcodeRequest(String barcode) {
    }

    @GetMapping("/api/library/overview")
    @PreAuthorize(READ)
    public LibraryService.Overview overview() {
        return service.overview();
    }

    @GetMapping("/api/library/settings")
    @PreAuthorize(READ)
    public LibraryService.Settings settings() {
        return service.settings();
    }

    @PutMapping("/api/library/settings")
    @PreAuthorize(WRITE)
    public LibraryService.Settings saveSettings(@RequestBody LibraryService.Settings s) {
        return service.saveSettings(s);
    }

    @GetMapping("/api/library/items")
    @PreAuthorize(READ)
    public List<LibraryService.ItemSummary> search(@RequestParam(required = false) String q, @RequestParam(defaultValue = "50") int limit) {
        return service.search(q, limit);
    }

    @GetMapping("/api/library/items/{id}")
    @PreAuthorize(READ)
    public LibraryService.ItemDetail item(@PathVariable Long id) {
        return service.item(id);
    }

    @PostMapping("/api/library/items")
    @PreAuthorize(WRITE)
    public LibraryService.ItemDetail addItem(@RequestBody LibraryService.ItemRequest r) {
        return service.saveItem(null, r);
    }

    @PutMapping("/api/library/items/{id}")
    @PreAuthorize(WRITE)
    public LibraryService.ItemDetail saveItem(@PathVariable Long id, @RequestBody LibraryService.ItemRequest r) {
        return service.saveItem(id, r);
    }

    @PostMapping("/api/library/items/{id}/copies")
    @PreAuthorize(WRITE)
    public LibraryService.ItemDetail addCopy(@PathVariable Long id, @RequestBody LibraryService.CopyRequest r) {
        return service.addCopy(id, r);
    }

    @PutMapping("/api/library/copies/{id}/status")
    @PreAuthorize(WRITE)
    public LibraryService.ItemDetail copyStatus(@PathVariable Long id, @RequestBody StatusRequest r) {
        return service.setCopyStatus(id, r.status(), r.note());
    }

    @Operation(summary = "Import a catalogue from CSV or Excel (Title, Authors, ISBN, Publisher, Year, Edition, Subject, Call number, Kind, Barcode, Price)")
    @PostMapping(value = "/api/library/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(WRITE)
    public LibraryService.ImportResult importCatalogue(@RequestPart MultipartFile file) throws IOException {
        return service.importCatalogue(file.getBytes());
    }

    @GetMapping("/api/library/borrowers")
    @PreAuthorize(READ)
    public List<LibraryService.Borrower> borrowers(@RequestParam String q) {
        return service.findBorrowers(q);
    }

    @GetMapping("/api/library/borrowers/{type}/{id}")
    @PreAuthorize(READ)
    public LibraryService.Borrower borrower(@PathVariable String type, @PathVariable Long id) {
        return service.borrower(type.toUpperCase(java.util.Locale.ROOT), id);
    }

    @PostMapping("/api/library/issue")
    @PreAuthorize(WRITE)
    public LibraryService.LoanView issue(@RequestBody LibraryService.IssueRequest r) {
        return service.issue(r);
    }

    @Operation(summary = "Take a copy back by its number; a late return is fined")
    @PostMapping("/api/library/return")
    @PreAuthorize(WRITE)
    public LibraryService.LoanView giveBack(@RequestBody BarcodeRequest r) {
        return service.giveBack(r.barcode());
    }

    @GetMapping("/api/library/loans")
    @PreAuthorize(READ)
    public List<LibraryService.LoanView> loans(@RequestParam(defaultValue = "OPEN") String which, @RequestParam(required = false) String q) {
        return service.loans(which, q);
    }

    @PostMapping("/api/library/loans/{id}/renew")
    @PreAuthorize(WRITE)
    public LibraryService.LoanView renew(@PathVariable Long id) {
        return service.renew(id);
    }

    @PostMapping("/api/library/loans/{id}/lost")
    @PreAuthorize(WRITE)
    public LibraryService.LoanView lost(@PathVariable Long id, @RequestBody(required = false) NoteRequest r) {
        return service.lost(id, r == null ? null : r.note());
    }

    @PostMapping("/api/library/loans/{id}/waive")
    @PreAuthorize(WRITE)
    public LibraryService.LoanView waive(@PathVariable Long id, @RequestBody NoteRequest r) {
        return service.waive(id, r.note());
    }

    @PostMapping("/api/library/loans/{id}/fine-paid")
    @PreAuthorize(WRITE)
    public LibraryService.LoanView finePaid(@PathVariable Long id, @RequestBody NoteRequest r) {
        return service.finePaid(id, r.note());
    }

    // ------------------------------------------------------------------ students

    @GetMapping("/api/student/library")
    @PreAuthorize("hasRole('STUDENT')")
    public LibraryService.MyLibrary mine() {
        return service.mine();
    }

    @GetMapping("/api/student/library/catalogue")
    @PreAuthorize("hasRole('STUDENT')")
    public List<LibraryService.CatalogueEntry> catalogue(@RequestParam String q) {
        return service.catalogue(q);
    }
}
