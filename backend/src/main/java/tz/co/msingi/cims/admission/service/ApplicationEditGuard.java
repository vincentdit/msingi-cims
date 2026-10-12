package tz.co.msingi.cims.admission.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.admission.domain.Application;
import tz.co.msingi.cims.admission.selection.SelectionRepositories.SelectionRoundRepository;

import java.time.OffsetDateTime;

/**
 * When an applicant may still change their application: while it is a draft, and — after submitting — while the
 * window is open and selection has not started for it (the submitted application stays submitted; the changes
 * count straight away).
 */
@Component
@RequiredArgsConstructor
public class ApplicationEditGuard {

    private final SelectionRoundRepository rounds;

    /** Submitted, window still open and no selection round yet. */
    public boolean amendable(Application app) {
        return app.getStatus() == Application.Status.SUBMITTED
                && app.getWindow().isOpen(OffsetDateTime.now())
                && rounds.findByWindowIdOrderByRoundNoDesc(app.getWindow().getId()).isEmpty();
    }

    /** A draft, or a submitted application that can still be amended. */
    public boolean changeable(Application app) {
        return app.isEditable() || app.getStatus() == Application.Status.WITHDRAWN || amendable(app);
    }

    public void markAmended(Application app) {
        if (app.getStatus() == Application.Status.SUBMITTED) app.setAmendedAt(OffsetDateTime.now());
    }
}
