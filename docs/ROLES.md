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
| TECHNICAL_TEAM | Platform Administrator | Integration logs and synchronisation, run by Msingi. |
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

### 4. Principal / Management Viewer: Working

- **Sees:** the dashboard, reports and read-only views of every area.
- **Does:** nothing that changes data.
- **Next:** a management dashboard focused on decisions (enrolment, fee collection, pass rates, by programme and intake) rather than office queues.

### 5. Deputy Principal (Academic): Working

- **Sees:** admissions, registry, teaching, examinations, graduation and notices.
- **Does:** approves selection (Academic Committee), the academic calendar, curricula and results; revokes letters.
- **Next:** one "waiting for my approval" list across selection, curricula, results and amendments.

### 6. Admissions Officer: Working (refined most)

- **Sees:** admission windows, applications, programmes, students (read), notices and messages.
- **Does:**
  - Opens and closes windows.
  - Checks declared qualifications.
  - Runs selection, NACTVET verification and letters.
  - Hands selected applicants over to Registry.
- **Next:** an applicant search and file view like NOAS (print an application file); eligibility reports per programme and subject.

### 7. Registrar / Registry Officer: Working

- **Sees:** admissions (read), students, semester registration, graduation, clearance, hostels (read), IPT (read), notices and messages.
- **Does:** keeps student records, approves detail changes, runs semester registration and prepares graduation lists.
- **Next:** student status changes (suspension, deferment, withdrawal) and ID cards.

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

### 12. Examinations Officer: Working

- **Sees:** programmes, students, calendar, curricula, teaching, marks, eligibility, results, amendments, regulations and notices.
- **Does:** runs marks windows, results processing and publication, resits, amendments, transcripts and NACTVET results files.
- **Next:** none planned.

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
- **Next:** NOAS also keeps integration logs (NECTA, NACTVET, GePG calls). CIMS does not record those calls yet; that belongs to the Platform Administrator slice.

### 19. Platform Administrator (Msingi): Not started

- **Should see:** all institutions (tenants), their status, usage and integration health.
- **Should do:** onboard a new institution (code, domains, first administrator) and suspend or reactivate one.
- **Gap:** institutions are created by the demo seeder only. Next: a platform console.

### 20. Sponsor Officer (optional in the SRS): Not started

- **Should see:** statements for the students they sponsor.

## Suggested order for the next slices

1. Principal / Management: a decision-focused dashboard.
2. Deputy Principal: one approvals inbox.
3. Admissions Officer: NOAS-style applicant search and file.
4. Registrar: status changes and ID cards.
5. Platform Administrator: the platform console and onboarding.
