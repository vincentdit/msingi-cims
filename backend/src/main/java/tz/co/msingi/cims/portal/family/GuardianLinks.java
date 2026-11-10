package tz.co.msingi.cims.portal.family;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Keeps a student's next of kin linked for the family portal and for fee reminders to guardians (FR-STU-05). */
@Component
@RequiredArgsConstructor
public class GuardianLinks {

    private final JdbcTemplate jdbc;

    /** Links the next of kin recorded on the student, when the phone number is a valid Tanzanian mobile number. Safe to call often. */
    public void linkNextOfKin(Long studentId) {
        jdbc.query("select next_of_kin_phone, next_of_kin_name, next_of_kin_relation from students where id = ? and next_of_kin_phone is not null", rs -> {
            String phone = FamilyPortalService.normaliseOrNull(rs.getString(1));
            if (phone == null) return;
            jdbc.update("""
                    insert into guardian_links (tenant_id, student_id, phone, name, relation, source, created_by_name)
                    values (cims_current_tenant(), ?, ?, ?, ?, 'NEXT_OF_KIN', 'Student record') on conflict (tenant_id, student_id, phone) do nothing""",
                    studentId, phone, rs.getString(2), rs.getString(3));
        }, studentId);
    }
}
