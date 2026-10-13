package tz.co.msingi.cims.exams.office;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Examinations Office board (Examinations Officer, administrator, Deputy Principal (Academic) and management). */
@RestController
@RequiredArgsConstructor
public class ExamsOfficeController {

    private final ExamsOfficeService service;

    @GetMapping("/api/exams/office")
    @PreAuthorize("hasAnyRole('INSTITUTION_ADMIN','EXAMINATIONS_OFFICER','DEPUTY_PRINCIPAL_ACADEMIC','MANAGEMENT_VIEWER')")
    public ExamsOfficeService.Board board(@RequestParam(required = false) Long semesterId) {
        return service.board(semesterId);
    }
}
