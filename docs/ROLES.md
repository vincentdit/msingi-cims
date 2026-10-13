# CIMS users and roles

This is the working list of who uses CIMS, what each person should see and do, and where the system stands for each. It follows Document 03 (System Requirements Specification), section 3, and borrows from NOAS where NOAS already solves the same problem. Update it whenever a role is refined.

**How access works**

- **Roles.** A staff account holds one or more roles. Each role can be limited to part of the institution: Head of Department to one department, Programme Coordinator to one programme.
- **Menus.** The back-office menu is built from `frontend/src/auth/access.ts`, which mirrors the API's `@PreAuthorize` rules. People only see pages their roles can open. A page opened by a direct link that is not part of the role shows a short note instead of errors.
- **Enforcement.** The API enforces every rule. The menu only keeps people away from pages they can't use.
- **Managing accounts.** The Institution Administrator manages staff accounts and roles on **Users & roles**. This works like NOAS user management: temporary passwords that must be changed at first sign-in, reset, disable, enable and unlock. Changing roles, disabling an account or resetting a password signs the person out at once.

**Demo accounts.** Use the DEMO institution; passwords are shown in brackets.

| Username | Role |
|---|---|
| admin (Admin@2026) | Institution Administrator + Deputy Principal (Academic) |
| principal (Principal@2026) | Principal / Management |
| academic (Academic@2026) | Deputy Principal (Academic) |
| admissions (Admissions@2026) | Admissions Officer |
| registry (Registry@2026) | Registrar |
| hod (Hod@2026) | Head of Department (ICT) |
| coordinator (Coordinator@2026) | Programme Coordinator (ODIT) |
| lecturer (Lecturer@2026) | Lecturer |
| assessor (Assessor@2026) | Assessor (NVA) |
| exams (Exams@2026) | Examinations Officer |
| bursar (Bursar@2026) | Bursar |
| accountant (Accountant@2026) | Accountant |
| warden (Warden@2026) | Hostel Warden |
| ipt (Ipt@2026) | IPT Coordinator |
| library (Library@2026) | Clearance Officer (library) |
| auditor (Auditor@2026) | Auditor |

## NOAS and CIMS roles

NOAS is the national admission system run by NACTVET. CIMS is one institution's system, so the roles line up like this:

| NOAS role | CIMS role(s) | Note |
|---|---|---|
| ADMIN (System Admin) | Platform Administrator (Msingi), Institution Administrator | NOAS has one national admin; CIMS has the platform operator plus an administrator in each institution. |
| ADMISSION_OFFICER | Admissions Officer | Same job, for one institution. |
| JAC_MEMBER (Joint Admission Committee) | Deputy Principal (Academic) | Approves selection for the Academic Committee. |
| TECHNICAL_TEAM | Platform Administrator | Integration health and logs in the platform console, run by Msingi. |
| INSTITUTION_OFFICER | All institution staff roles | In NOAS this is the college's view of national admissions; in CIMS the whole college runs inside the system. |
| APPLICANT | Applicant | Same, with NECTA/NACTVET lookups and the NOAS entry-level rule. |

NOAS also lets an administrator adjust which privileges a role has. CIMS keeps roles fixed in code for now; that can be added if institutions ask for it.

## The roles

Status: **Done** means refined and tested. **Working** means usable, with known gaps listed. **Not started** means the role exists but has no pages of its own.

### 1. Applicant (portal): Done

- **Sees:** the admission home page with How to apply and their own progress, their application, fee, results, choices, offer and letter.
- **Does:**
  - Signs up with their NECTA index number.
  - Completes their profile, pays by GePG and adds other qualifications.
  - Chooses programmes at their entry level (the NOAS rule) and submits.
  - Changes details, qualifications and choices while the window is open.
  - Accepts or declines an offer.
- **Next:** none planned.

### 2. Student (student portal): Working

- **Sees:** their profile, fees, semester registration, results, notices, clearance, hostel and IPT.
- **Does:** registers for the semester, requests detail changes, applies for a hostel and IPT, and downloads statements.
- **Next:** review against the student journey after the staff roles.

### 3. Institution Administrator: Done in this slice

- **Sees:** everything in the institution, plus **Users & roles**, **Audit trail** and **Institution**.
- **Does:**
  - Creates staff accounts with roles (department or programme limits where they apply) and hands over the temporary password.
  - Resets passwords, disables, enables and unlocks accounts.
  - Sets the campus photo.
  - Configures programmes and windows.
- **Safeguards:**
  - The institution always keeps one active administrator.
  - An administrator can't disable themselves or remove their own administrator role.
  - Every change is in the audit trail.
- **Next:** institution settings page (name, contacts, colours, logo, registration number pattern) and integration settings.

### 4. Principal / Management Viewer: Done

- **Sees:** the dashboard, reports and read-only views of every area. The dashboard opens on the **Institution overview** for the chosen academic year.
- **Institution overview:**
  - **Headline figures:** current students (female and male); new students against accredited places; the share of the year's annual fees collected (amounts and what is outstanding); the pass rate from published results; and first-choice applicants per place in the year's admission window.
  - **Needs attention:** the programmes below stated thresholds (intake under 50% of places, fees collected under 60%, pass rate under 60%, fewer applicants than places). Each is marked with an icon and words, not colour alone.
  - **Charts:** new students by year of entry (last five years) and payments received by month (last twelve months).
  - **By programme table:** students, new intake against places, fees collected and outstanding, pass rate, and applicants per place, with a total row.
  - The administrator and the Deputy Principal (Academic) see the same overview as a tab beside "Today's work".
- **Does:** nothing that changes data.
- **Next:** make the thresholds settable per institution; export the overview as PDF for board papers.

### 5. Deputy Principal (Academic): Done

- **Sees:** admissions, registry, teaching, examinations, graduation and notices, plus **Approvals**.
- **Approvals:** everything waiting for their decision in one list, grouped by the body it is decided for, oldest first.
  - **Academic Committee:** proposed selection rounds, draft curricula, computed class results to recommend, and examination exemptions.
  - **Student status changes:** requests from Registry, with or without the Head of Department's recommendation.
  - **Using the list:** each item says who put it forward and when, and opens the page where it is decided; the usual checks apply there.
  - **Own items:** items the person put forward themselves are shown but marked for someone else to decide (segregation of duties). So are drafts still being prepared.
  - **Count:** the menu shows how many decisions wait, and "Today's work" on the dashboard says so too.
- **Does:** approves selection, curricula, class results and exemptions for the Academic Committee; keeps the academic calendar; revokes letters.
- **Other approvers:** the same Approvals page serves them.
  - **Administrator:** Governing Board confirmation of results, result amendments and graduand lists.
  - **Head of Department:** module results to moderate and resit marks for their department.
  - **Head of Department and Examinations Officer:** recording the Department Board's approval.
  - **Student status changes:** the Head of the student's department recommends; the Deputy Principal decides.

### 6. Admissions Officer: Done

- **Sees:** admission windows, **Applicants**, programmes, students (read), notices and messages.
- **Applicants** (as NOAS "Applicants": search, view submissions, print files):
  - **Search:** by name, NECTA index number, phone, email or reference.
  - **Filters:** window, status, programme chosen (or first choice only), sex, fee paid, declared qualifications still to check, and submission dates.
  - **List:** shows index number, first choice and a "to check" count. It can be sorted by name or by submission date.
  - **Export CSV:** the list, with all three choices and contact details. Each export is recorded in the audit trail.
  - **Application view:** opens with the applicant's details, qualifications (with their checks), fee, choices with eligibility, and the application's **history** from the audit trail.
  - **Print file:** a PDF with every section and the history. Printing is recorded in the application's history.
- **Does:**
  - Opens and closes windows and checks declared qualifications.
  - Runs selection, NACTVET verification and letters.
  - Hands selected applicants over to Registry.
- **Next:** eligibility reports per programme and subject (NOAS "Eligibility Reports").

### 7. Registrar / Registry Officer: Done

- **Sees:** admissions (read), students, status changes, semester registration, graduation, clearance, hostels (read), IPT (read), notices and messages.
- **Does:** keeps student records, approves detail changes, runs semester registration and prepares graduation lists.
- **Status changes (FR-REG-04, BP-14):**
  - **Request:** from the student's record, Registry requests a postponement, resumption, suspension, discontinuation, withdrawal or death, with a reason, a reference (letter, minute or case number) and an effective date. A postponement also needs the date the student is expected back.
  - **Allowed changes:** only those that fit the student's status. For example, only a postponed or suspended student can resume. A student has one request in progress at a time. The effective date can be at most 90 days in the past.
  - **Recommend:** the Head of the student's department recommends the request from Approvals or the Status changes page.
  - **Decide:** the Deputy Principal (Academic) approves or rejects it. A rejection needs a reason. They may decide before the Head has recommended. Whoever requested a change cannot decide it.
  - **Apply:** an approved change applies at once if its date has come; otherwise a morning job applies it on the date. The student's status changes and they get an SMS (`STUDENT_STATUS_CHANGED`, English or Swahili). Discontinuation, withdrawal and death also revoke the student's ID card.
  - **Safety check:** if the student's status changed in the meantime, the change is not applied and is marked cancelled with a note.
  - **Cancel:** Registry can cancel a request before it is decided.
  - **Record:** every step is in the audit trail.
- **Status changes page:** what is in progress and the latest decisions. Registry, the Deputy Principal, Heads of Department (their department only) and the Principal see it.
- **Photo and ID card (FR-REG-06):**
  - **Photo:** Registry uploads the student's passport-style photo (JPEG or PNG, upright, at least 200 pixels, up to 2 MB).
  - **Issue:** a card is issued to a registered (active) student with a photo. Each card has a card number (ID2026-00001), a validity date and a QR code. The validity date is the end of the student's expected final academic year.
  - **Print:** each card prints at card size (85.6 × 54 mm), front and back.
  - **Replace:** a lost or damaged card is replaced with a reason. The old card then shows as superseded.
  - **Revoke:** a card can be revoked with a reason.
  - **Batch:** "ID cards" on the Students page issues and prints cards for a whole programme (or one year of study). It lists who still needs a photo.
  - **Verify:** anyone can scan the QR code or enter the code at /verify. The page shows whether the card is valid, expired, superseded or revoked, and the holder's current status. For example, it warns when the card is in force but the holder is suspended.

### 8. Head of Department: Done

- **Sees:** programmes, students, semester registration, calendar, curricula, teaching, marks, eligibility, results, regulations, clearance, IPT, dashboard and reports.
- **Does:** allocates lecturers and sets up the department's module offerings, moderates marks, requests examination exemptions, prepares curricula and clears finalists for the department.
- **When limited to a department** (the usual set-up; give the role "limited to" a department on Users & roles):
  - **Lists:** programmes, students, the student's record and class lists, semester registrations, curricula, result sheets and IPT placements show only the department's programmes.
  - **Modules:** teaching, marks and eligibility show the department's modules and the modules taught in its programmes. Moderation stays limited to the department's own modules.
  - **Dashboard and reports:** the dashboard counts only the department's students. Reports are run for one of its programmes, and reports that only cover the whole institution are not offered.
  - **Changes:** modules and curricula can be created or changed only for the department.
  - **Direct links:** a record outside the department is refused, even by a direct link.
- **Sidebar:** shows "Department: …" under the person's name.
- **Demo:** `hod` heads ICT.

### 9. Programme Coordinator: Done

- **Sees:** calendar, curricula, teaching, marks and IPT for their programme.
- **Does:** prepares the programme's curriculum and adds its modules, and follows its classes, attendance and marks (read-only unless also a lecturer).
- **When limited to a programme:** the same rules as above, with the programme in place of the department.
- **Demo:** `coordinator` coordinates ODIT.
- **Next:** a coordinator dashboard (class sizes, attendance below the threshold, marks not yet submitted).

### 10. Lecturer / Tutor: Working

- **Sees:** teaching and attendance, marks, and IPT (as institution supervisor).
- **Does:** takes attendance and enters marks for allocated modules only.
- **Next:** a "my modules this semester" landing page.

### 11. Assessor (NVA): Working

- **Sees:** teaching, marks and IPT.
- **Does:** records competence assessments for allocated NVA units.
- **Next:** check the NVA competence workflow end to end.

### 12. Examinations Officer: Done

- **Sees:** the **Examinations office** board (where they land after signing in), programmes, students, calendar, curricula, teaching, marks, eligibility, results, **remarks and appeals**, amendments, regulations and notices.
- **Does:** runs marks windows, results processing and publication, resits, amendments, transcripts and NACTVET results files.
- **Examinations office board** (per semester, refreshed every minute):
  - **Marks entry:** whether the window is set, upcoming, open or closed.
  - **Modules:** how many are in marks entry, with the Head of Department, with the Department Board, or approved. Those still behind are listed with their lecturers, and modules without a lecturer are flagged.
  - **Examination eligibility:** lists generated per module, students not eligible, and exemptions waiting.
  - **Result sheets:** each class's sheet from computed to published, the classes with no sheet yet, and each sheet's NACTVET submission (not submitted, accepted or queried).
  - **Supplementary and special examinations:** registrations, unpaid fees, resit marks submitted and approved.
  - **Also counted:** remarks and appeals at each stage, and amendments waiting for the administrator.
  - **Next:** a short list of what to do, each step linking to its page. For example: set the marks window, modules still in marks entry after the window closed, sheets to compute, confirmed sheets not published, sheets not sent to NACTVET, a NACTVET query, opening the resit session, or remarks needing an examiner.
- **Remarks and appeals (FR-EXM-12, BP-11):**
  - **Settings:** the appeal period (default 14 days after results are published; 0 stops new requests) and the fee per module. The Deputy Principal or the administrator sets them on Exam regulations.
  - **Student:** in the portal (Results, English and Swahili), asks for an examination script to be re-marked or appeals a result. Only published results within the period can be chosen, with one open request per module. The fee is invoiced with a GePG control number. The student can withdraw before an examiner is assigned; an unpaid invoice is then cancelled.
  - **Examinations Officer:** once the fee is paid, assigns an examiner from the teaching staff. The module's own lecturers are not offered, and choosing one is refused as a conflict of interest. Requests outside the grounds can be declined with a reason, sent to the student by SMS.
  - **Examiner:** sees only the requests assigned to them and records the new marks with a comment. A remark re-marks only the examination; the coursework stays. The original marks stay on record.
  - **Forwarding:** the Examinations Officer forwards the outcome.
    - **Unchanged:** the request closes and the student is told by SMS.
    - **Changed:** it becomes a result amendment for the administrator. On approval the result is re-graded, the student's GPA, CGPA and decisions are recomputed, and the student is told by SMS. A rejected amendment closes the request as unchanged.
  - **Privacy:** the student sees the new marks only once the request is decided, and never who examined it.
  - **Approvals:** paid requests needing an examiner, and outcomes to forward, appear in the Examinations Officer's Approvals.
  - **Record:** every step is in the audit trail.
- **Next:** NACTVET's official results template and API (when NACTVET publishes them), examination analytics across years (FR-EXM-14), and registering carried-over modules.

### 13. Bursar / Finance Manager: Working

- **Sees:** dashboard, reports, fees, hostels (read), notices and messages.
- **Does:** sets fee structures, approves waivers and refunds, signs off reconciliation and buys SMS bundles.
- **Next:** a finance dashboard (collections against targets, debtors by programme).

### 14. Accountant / Finance Officer: Working

- **Sees:** dashboard, reports, fees, hostels (read) and notices.
- **Does:** issues invoices, records payments and sponsors and works payment exceptions.
- **Next:** none planned.

### 15. Dean of Students / Hostel Warden: Working

- **Sees:** reports, clearance, hostels and notices.
- **Does:** runs hostel allocation, check-in and check-out, clears students for the hostel and posts notices.
- **Next:** limit to assigned hostels (SRS scope).

### 16. IPT Coordinator: Working

- **Sees:** reports and industrial training.
- **Does:** manages placements, supervisors, letters, logbooks and assessment.
- **Next:** none planned.

### 17. Clearance Officer: Working

- **Sees:** clearance for their office.
- **Does:** clears or holds students.
- **Next:** none planned.

### 18. Auditor: Done

- **Sees:** dashboard, reports, admissions (read), programmes, calendar, curricula, fees (read), messages (read), users (read) and the **Audit trail**.
- **Audit trail:**
  - Every change in CIMS with who made it, when, the record it touched and the details, and the IP address it came from.
  - Periods of today, 7 days, 30 days or chosen dates, up to a year at a time.
  - Filters by area, person, action and free text, with a security-events switch.
  - A summary of entries, sign-ins, failed sign-ins, locked accounts and staff account changes; each count can be clicked to filter.
  - A record's full history and everything one person did. The Users & roles page links to both for each account.
  - CSV export of up to 100,000 rows; each export is itself recorded.
- **Safeguards:**
  - Entries cannot be changed or deleted (database trigger).
  - Each institution sees only its own trail (row-level security).
  - Failed sign-ins and locked accounts are now recorded against the account they were for.
- **Does:** nothing that changes data.
- **Integration calls** (a tab on the Audit trail, as NOAS keeps integration logs):
  - **What:** the institution's own calls to NECTA, NACTVET (admission and awards), GePG and the SMS gateway, and the GePG payment notifications it received.
  - **Each call:** when, what, the outcome, how long it took and a reference: an index number, a bill id or a masked phone number. What was sent or received is never kept.
  - **Health:** each outside system's health over the last 7 days, and whether it runs live or simulated.
  - **Access:** the Auditor and the administrator.

### 19. Platform Administrator (Msingi): Done

- **Who:** Msingi Technologies staff, in the **platform console** at `/platform`. They sign in separately from any institution.
- **Keeping the two apart:**
  - Platform accounts sit in their own table, with the same lock-out rules as institution accounts.
  - A platform sign-in works only on the console, and an institution sign-in never works there.
  - Platform tokens are always issued and checked by CIMS itself, so the console also works when institutions use Keycloak.
- **Overview:**
  - **Totals:** institutions (active and suspended), students studying, applications this year and staff accounts.
  - **Integration health:** the last 24 hours, with a warning banner when an outside system is failing.
  - **Needs attention:** suspended institutions, institutions with failed calls today, and setup still incomplete.
  - **Recent actions:** the latest platform actions.
- **Institutions:**
  - **List:** status, students, staff, applications this year, SMS left, setup progress, last staff sign-in and failed calls.
  - **Onboard:** in one step, all kept or none.
    - The code (fixed), name, NACTVET number, ownership, contacts and colour.
    - The web address(es) its portals answer on. Bare host names are checked and must be unique on the platform.
    - Msingi's private notes.
    - The first administrator, who gets a temporary password (shown once) and the staff sign-in address.
  - **Detail page:**
    - **Usage:** students, applications, staff, SMS left and sent this month.
    - **Setup checklist:** web address, administrator signed in, staff accounts, programmes, academic year and intake, fee structure, admission window, campus photo and SMS bundle.
    - **Also shown:** the administrators, the web addresses, integration health for the last 7 days, contacts, notes and recent platform actions.
  - **Actions:**
    - Edit the details.
    - Add or remove web addresses. The last one cannot be removed.
    - Credit an SMS bundle. It shows on the institution's Messages page as a top-up.
    - Add an administrator, for example when the only one has left.
    - Reset an administrator's password. The administrator is unlocked and signed out everywhere. Only administrators can be reset this way; the institution's own administrator resets other staff.
  - **Suspend (with a reason; type the code to confirm):**
    - Every portal of the institution answers "service temporarily unavailable" (English and Swahili), and everyone signed in is stopped.
    - Its data is kept, scheduled jobs pause and GePG payments are still recorded.
    - **Reactivate** opens it again.
    - Other servers notice within 5 minutes.
- **NACTVET catalogue** (as NOAS reads it):
  - **Source:** every institution NACTVET has registered, with its accredited programmes, from NACTVET's `api-out-service/institutionprogrammeall` service. Each programme comes with its NTA level, duration, capacity (male, female and total), fee, entry requirements, selection criteria, specific CSEE/ACSEE subjects and NTA routes.
  - **Sync from NACTVET:**
    - Reads every page in the background, one sync at a time.
    - Updates what changed and marks what NACTVET no longer lists, without deleting it.
    - Each sync is recorded with its counts, and appears in the integration log and the platform audit trail.
    - The sync is live whenever the NACTVET awards service is live, with the same NACTE credentials. Otherwise it uses a sample marked as simulated.
  - **Browse:** search by name, code or registration number; filter by region, ownership and whether the institution is on CIMS. Each institution opens to its programmes.
  - **Onboard from the catalogue:**
    - The form is filled in from NACTVET: code, name, short name, registration number, ownership (government is public), phone, and region in the notes.
    - Msingi chooses which programmes to bring in.
    - Each programme is created with its award, duration (years × 2 semesters), entry level (holders of NTA 4 or 5 enter at the next level), capacity and NACTVET code.
    - Its fee and entry requirements go in the programme's description.
    - Each NACTVET board becomes a starting department, which the institution can rename or split.
    - One catalogue entry can become only one institution.
  - **On the institution page:**
    - **Link:** an institution onboarded by hand can be linked to its catalogue entry, or unlinked.
    - **Import:** programmes NACTVET lists that are not yet on CIMS can be imported at any time. Importing again adds only what is new.
- **Integration health:**
  - **Coverage:** calls from every institution to NECTA, NACTVET, NACTVET awards, GePG and the SMS gateway, live or simulated.
  - **States:** working, some failures, failing or no calls.
  - **Detail:** failure rate, average time, last success and last error.
  - **Calls:** a paged list of calls by institution, system and outcome.
  - **Retention:** entries are kept for 365 days (`CIMS_INTEGRATION_LOG_RETENTION_DAYS`).
- **Platform users:** add, with a temporary password; reset; disable; enable. Nobody can disable themselves or the last active user.
- **Audit trail:**
  - The platform's own trail cannot be changed or deleted: who, what, which institution, the details and the IP address.
  - What platform staff do inside an institution also appears in that institution's audit trail as "<name> (Msingi platform)".
- **First account:**
  - Created at start-up when none exists, from `CIMS_PLATFORM_BOOTSTRAP_USERNAME` (default `msingi`) and `CIMS_PLATFORM_BOOTSTRAP_PASSWORD`. That person must choose their own password at first sign-in.
  - **Development:** `msingi` / `Msingi@2026`.

### 20. Sponsor Officer (optional in the SRS): Not started

- **Should see:** statements for the students they sponsor.

## Suggested order for the next slices

1. The roles still marked Working, in this order: Lecturer, Assessor, Bursar, Accountant, Student, Hostel Warden, IPT Coordinator, Clearance Officer.
2. Sponsor Officer (optional in the SRS).
