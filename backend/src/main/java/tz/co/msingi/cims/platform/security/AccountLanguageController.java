package tz.co.msingi.cims.platform.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The language a signed-in person uses CIMS in (BR-40), kept with the account so it follows them to another device. */
@Tag(name = "My account")
@RestController
@RequiredArgsConstructor
public class AccountLanguageController {

    private final JdbcTemplate jdbc;

    public record LanguageRequest(@NotBlank @Pattern(regexp = "en|sw") String language) {
    }

    @Operation(summary = "Choose English (en) or Kiswahili (sw)")
    @PutMapping("/api/account/language")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public Map<String, String> set(@Valid @RequestBody LanguageRequest req) {
        // a plain update: no version check, so changing the language never clashes with other changes to the account
        jdbc.update("update user_accounts set language = ? where id = ?", req.language(), CurrentUser.requireId());
        return Map.of("language", req.language());
    }
}
