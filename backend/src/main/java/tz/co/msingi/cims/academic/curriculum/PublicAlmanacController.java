package tz.co.msingi.cims.academic.curriculum;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The published almanac on the institution's public site: only what is for everyone. */
@Tag(name = "Public site")
@RestController
@RequestMapping("/api/public/almanac")
@RequiredArgsConstructor
public class PublicAlmanacController {

    private final AcademicCalendarService calendar;

    @Operation(summary = "Academic years whose almanac is published")
    @GetMapping("/years")
    public List<CurriculumDtos.AlmanacYear> years() {
        return calendar.almanacYears(AcademicCalendarService.Reader.PUBLIC);
    }

    @Operation(summary = "A published almanac: the entries for everyone")
    @GetMapping
    public CurriculumDtos.AlmanacView almanac(@RequestParam Long academicYearId) {
        return calendar.almanac(academicYearId, AcademicCalendarService.Reader.PUBLIC);
    }
}
