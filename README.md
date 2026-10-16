# CIMS — College/Institution Management System

**Msingi Technologies Ltd** · One platform from application to graduation for NACTVET‑registered institutions.

This repository holds Release 1 build slices (**Foundation, Online Admission, Selection, NACTVET verification and offers, Admission letters, Registration hand-over, Fees, GePG reconciliation, Student portal, Messaging, Reports and dashboards, Curricula and semester registration, Examinations and results, Resits, amendments and transcripts, Teaching, attendance and examination eligibility, Lecturer's My teaching and self check-in, Clearance and graduation, Hostels, Industrial practical training**), as specified in `docs/` (Documents 01–07).

| Area | Delivered in this slice |
|---|---|
| Platform | Multi-tenant (one tenant per institution), tenant resolved from host name or `X-Tenant` header, **PostgreSQL row-level security**, JWT security (local issuer, Keycloak-ready), role-based access with **staff users and roles managed by the Institution Administrator** (Users & roles, as NOAS user management: accounts with one or more roles, Head of Department limited to a department and Programme Coordinator to a programme, a temporary password the person must replace at first sign-in, reset, disable with a reason, enable and unlock; the institution always keeps an active administrator; changed roles, disabling or a reset end the person's sessions at once; staff change their own password from the menu; the Auditor can view accounts), **data scope** (a Head of Department limited to a department and a Programme Coordinator to a programme see only its programmes, students, semester registrations, curricula, result sheets, IPT placements, modules, dashboard figures and programme reports, change only its modules and curricula, and are refused other records even by direct link; the sidebar names the department or programme), back-office menus grouped by area and showing only the pages each role can open (see `docs/ROLES.md` for every role, what it sees and does, and what is next), append-only audit trail with an **Audit trail page** for the Auditor and the administrator (who did what, when, on which record and from which IP; periods up to a year, filters by area, person, action and text, security events, failed sign-ins and locked accounts recorded against the account, a record's full history and a person's activity, CSV export that is itself recorded), per-institution number sequences, RFC 7807 errors, OpenAPI docs |
| Platform console (Msingi) | Separate sign-in for Msingi staff at `/platform`: the **NACTVET catalogue** of registered institutions and their accredited programmes (synced from NACTVET's `institutionprogrammeall` service as in NOAS; onboarding fills in the institution's details and imports its chosen programmes, with NACTVET boards as starting departments; more programmes can be imported later), onboard an institution with its web addresses and first administrator in one step, edit details and addresses, suspend (portals answer "temporarily unavailable", data and GePG payments kept) and reactivate, add an administrator or reset one's password, credit SMS bundles, usage and a setup checklist per institution, integration health and a log of every call to NECTA, NACTVET, GePG and the SMS gateway (also shown to each institution's Auditor), platform users, and an append-only platform audit trail; actions inside an institution also appear in its own audit trail |
| Academic structure | Academic years, intakes, departments, NVA/NTA programmes (levels validated) |
| Admissions | **Applicants** search for the Admissions Office as in NOAS (name, NECTA index number, phone, email or reference; filters for window, status, programme chosen or first choice, sex, fee paid, declared qualifications to check and submission dates; sort by name or submission; CSV export recorded in the audit trail; each application with its history from the audit trail and a printable PDF application file whose printing is recorded); admission portal home page with the institution's campus photo (uploaded by the administrator on the Institution page: JPEG, PNG or WebP up to 5 MB and at least 800 pixels wide, checked by its content, with a caption; a drawing of a campus in the institution's colours until one is uploaded), shown framed beside the banner and softened behind it, and a How to apply guide that follows the current process (NECTA index sign-up, SMS verification, details, GePG fee, other qualifications, choices at the applicant's entry level, changes until the window closes) and ticks off a signed-in applicant's own steps; admission windows with capacity and **NACTVET minimum entry rules (JSON rule engine)**, created and run by the Admissions Office or the administrator: published to open on their date (applicants see when applications open) or opened at once, closed early with a reason, reopened with a new closing date until selection starts, extended, and closed automatically when the closing date passes; applicants who have started but not submitted are told by SMS when a window closes early or reopens, and every change is kept in the window's opening and closing record; applicant sign-up with the **Form Four (CSEE) index number and year**: the candidate's names, sex, school and results are fetched from NECTA (the NECTA results API used by NOAS) and confirmed by the applicant, names are then fixed as NECTA has them, the CSEE results are stored as verified, one account per index number and year (an unconfirmed sign-up can be restarted), sign-in by phone, email or index number; passwords as in NOAS (8+ characters with upper and lower case, a digit and a special character, checked on the server) with a strength meter, the rules ticked off as they are met and the password typed twice; SMS OTP, password reset, after the fee an Other qualifications step as in NOAS (another CSEE/ACSEE sitting from NECTA, an NTA award fetched from NACTVET's particulars service by registration number and graduation year with programme, level, GPA, class and modules and the names checked against the applicant's, an NVA award, or another non-NTA award declared for the Admissions Office to check — or none); the Admissions Office's qualification checks per window (waiting, verified, not accepted) and on each application: declared NVA, other and typed-in NTA awards are verified against the certificates, so they count for eligibility and selection, or not accepted with a reason the applicant sees and receives by SMS, profile (address chosen region → district → ward → village/street from Tanzania's administrative areas as in NOAS, impairment from a fixed list, education history — primary school searched in NECTA's PSLE list of 19,438 schools (or typed when not listed) and O-level / A-level schools taken from the NECTA CSEE / ACSEE results — next of kin with relationship, phone, email, address and their own region-to-street address; all checked on the server and carried to the student record at hand-over), application, eligibility per programme with reasons, programmes offered by the applicant's **entry level as in NOAS** (a verified NTA award at level N leads only to NTA N+1 programmes, otherwise ACSEE to NVA 1–3, NTA 4 and NTA 5/7 programmes whose entry rules take A-level, otherwise CSEE to NVA 1–3 and NTA 4; each programme admits at an entry level set by the office or worked out from its award level and semesters, e.g. an Ordinary Diploma admits at NTA 4), only those programmes listed and choosable, a choice no longer at the applicant's level must be changed before submitting, submission with reference number; after submitting, applicants can still change their personal details, programme choices and other qualifications while the window is open and selection has not started (the application stays submitted, details cannot be left incomplete, choices cannot be emptied, a qualification a chosen programme needs cannot be removed, and the office sees when it was last changed) |
| Selection (BP-03) | Merit ranking, stable allocation of places across applicants' choices, Admissions Committee review (exclusions with reasons) and approval with segregation of duties, statistics, CSV for the committee pack |
| NACTVET verification and offers (BP-04) | Pre-validation, submission by API or NACTVET-template file, outcome fetch or file import (all-or-nothing), query resolution and resubmission, offers with a deadline, acceptance with NACTVET confirmation code for multiple admissions (attempt-limited), decline, automatic lapse, released places re-filled by the next round |
| Admission letters (FR-ADM-13, BP-16) | Joining instructions per window; PDF letter + joining instructions issued automatically on acceptance (or in bulk), QR code and 16-character verification code, public `/verify` page (rate-limited, minimal personal data), corrected editions (old code verifies as superseded), revocation, frozen snapshot so reprints match what was issued |
| Registration hand-over (BP-05, FR-REG-01/02) | Accepted applicants become students without re-entering data; registration numbers from a configurable pattern (never changed, never reused — enforced by the database); student account sign-in; first registration at reporting (originals checklist from the joining instructions, Registry approval → ACTIVE); student register with search, statistics and class-list CSV |
| Fees (BP-06, FR-FIN-01 to 04, 06, 12) | Fee items (GFS codes, allocation priority); fee structures per programme and year of study with local/foreign amounts and instalments, prepared by the Accountant and approved by the Bursar (segregation of duties), versioned; annual invoice with control number on offer acceptance, billing runs (duplicate-safe), invoice moves to the student at hand-over; payments allocated to items in order, overpayment held as credit; clearance rules (registration enforced at first registration); statements, collections and debtor ageing with CSV |
| GePG reconciliation (FR-FIN-05, BP-06 steps 6–7) | Daily import of GePG's reconciliation file (CSV, all-or-nothing validation), matched by GePG receipt number; exceptions: not received by CIMS (posted from the file with receipt and allocation), not in GePG's file (settles itself if a later file has it), amount differs, repeated line, unknown bill; each exception explained with name and time; day closed only with no open exceptions; month view and the Bursar's monthly sign-off once every day with collections is closed; CIMS payments of a day downloadable in GePG's layout |
| Student portal (FR-STU-01/02, FR-REG-08) | Mobile-first, installable student app (`/student`, English/Kiswahili): sign-in with registration number or mobile number; dashboard with fee balance, next payment and control number, registration and examination clearance, latest notices; invoices with control numbers and payment history with **PDF receipts** (amount in words, items paid); notices from staff for all students or a programme/year, published now or scheduled, optional SMS alert, read tracking, withdrawal; requests to correct personal details, approved or rejected by Registry with reasons (name/date-of-birth changes flagged for original documents; phone change also moves the sign-in) |
| Messaging (FR-COM-01 to 03, FR-INT-04) | Every SMS is written to an outbox in the same transaction as the event and handed to the gateway after commit (nothing goes out for work that rolls back), retried up to three times, with delivery reports (signed callback) and a full message log; 26 event messages with built-in English and Kiswahili wording that each institution may reword or switch off, sent in the institution's chosen language; verification codes sent at once and never stored; bulk messages to students (status, programme, year, only those who owe fees), applicants (window, status) or a list of numbers, with personalisation ({firstName}, {balance}, …), a preview of recipients, SMS parts and cost, and a check against the prepaid SMS bundle; delivery per bulk message and resending of failures; typographic punctuation converted so messages stay in the cheaper GSM encoding |
| Reports and dashboards (FR-RPT-01/02, FR-ADM-16) | **Approvals**: one list of what waits for the signed-in person's decision (selection rounds, curricula, class results to recommend and to confirm, examination exemptions, result amendments, graduand lists, module results to moderate and Department Board approvals, resit marks), grouped by the body deciding, oldest first, each linking to where it is decided, own submissions shown but left to someone else, with a count on the menu and the dashboard; **Institution overview** for the Principal, the administrator and the Deputy Principal (Academic), by academic year: current students by sex, new students against accredited places, annual fees collected and outstanding, pass rate from published results, first-choice applicants per place, a by-programme table with totals, programmes needing attention against stated thresholds (shown with an icon and words), new students by year of entry and payments by month; role-based dashboard (admissions funnel, daily applications, programme demand against capacity; current students by programme and status; billed/collected/outstanding, 30-day collections, debtor ageing and reconciliation warnings), each section shown only to roles that may see it; standard report catalogue of 29 reports — Admissions (by programme, region and gender, qualification, status, NACTVET verification, acceptance rate, letters issued, applicant list), Registry (enrolment, nominal roll, age and region profiles, first registration, semester registration status, module class list), Finance (collections by item/day/channel, receipts register, debtors and ageing, reconciliation exceptions), Governance (user access review, audit log extract) — filterable by academic year, intake, window, programme, award, level, sex, status, year of study, semester, module and period; on screen, as Excel (frozen header, filters, real numbers and dates) or PDF; lists of named people hidden from management viewers; personal-data views and every export recorded in the audit log; all report SQL runs under row-level security |
| Curricula and semester registration (FR-ACA-02/03/04, FR-REG-03, FR-STU-02) | Module catalogue (code, credits, NTA level, graded or competence-based; code, credits and assessment locked once a module is in an approved curriculum); versioned curricula per programme placing modules in each year and semester as core or elective, with how many electives a student must choose; a draft is checked for empty semesters and unmet elective rules and must be approved by the Deputy Principal (Academic) or administrator who did not prepare it; new versions are copied from the approved one and apply from an intake year, and each student stays on the version they started; academic calendar of semesters with registration, closing and late-registration dates plus key dates (teaching, examinations, results, holidays); students register in the portal (core modules shown, electives chosen, details confirmed, fee clearance checked, late registrations flagged) and may change their electives until Registry approves; Registry approves one by one or all at once (clearance re-checked, uncleared students skipped) or returns a registration with a note; SMS on approval and return |
| Examinations and results (FR-ACA-05, FR-EXM-01/02/03/05/06/07/10/14, BP-08) | Examination regulations per award level, versioned by the academic year they apply from (grading scale with grade points and pass grades, CA/SE weights and component pass marks, GPA decimals and rounding, supplementary and discontinuation thresholds, award classification), seeded with the SRS's indicative NTA 4–5 and NTA 6 values and locked once used; module offerings set up from approved semester registrations, with lecturers allocated; assessment plans (tests, assignments, practicals and the examination, weights checked against the regulations); marks entered on screen or through a validated Excel template (nothing saved unless the whole file is valid), with CA, SE, total, grade and pass/fail computed live and ABS for absence; a marks-entry window set by the Examinations Officer; lecturer → Head of Department moderation (grade distribution and anomaly flags, return with comments; department-scoped HODs) → Department Board (optional); class result sheets per programme and year with GPA, CGPA, proposed decision (pass, supplementary, special exam, incomplete, repeat, discontinue) and provisional classification, each student graded on their own programme's scale; Academic Committee changes with reasons and recommendation; Governing Board confirmation by someone else, after which the database refuses changes to the results; publication to the student portal (Results tab, English/Kiswahili) with SMS, withheld for students without results clearance; reports for module performance, the class result sheet (Excel/PDF) and results by programme |
| Examinations office and appeals (FR-EXM-12, BP-11) | **Examinations office** board per semester for the Examinations Officer (their landing page): marks window, modules through marks entry, moderation and board approval (those behind listed with lecturers), eligibility lists and pending exemptions, class result sheets from computed to published with NACTVET submission status, resit registrations and payments, remarks and appeals, pending amendments, and a list of next steps linking to each page. **Remarks and appeals**: within an appeal period after publication (set with the fee per module), students ask in the portal for an examination to be re-marked or appeal a result and pay by GePG; the Examinations Officer assigns an examiner who did not teach the module (conflict of interest refused); the examiner records new marks with a comment (original marks kept; a remark keeps the coursework); unchanged outcomes close with an SMS, changed ones become amendments approved by the administrator, which re-grade and recompute the student's results; requests can be declined with a reason or withdrawn before assignment (unpaid invoice cancelled); audit trail throughout |
| Resits, amendments and transcripts (FR-EXM-08/11/13, BP-10) | Supplementary and special examinations: the Examinations Officer opens a session per semester (fee per module, registration deadline, exam period); students with a published supplementary or special-exam decision register in the portal for their failed or missed modules and pay by GePG; lecturers enter the resit (out of 100, replacing the examination; coursework carried) only for students who have paid; supplementary grades capped at the regulations' grade, special exams not; Head of Department approves; a RESIT result sheet per class goes through committee, board and publication, with carry over for a module still failed; confirmed resit results replace first attempts in CGPA and transcripts. Amendments to confirmed results: requested with reason and evidence by the Examinations Officer, approved by the administrator (someone else), the database lock opened only for that change, GPA, CGPA and decisions recomputed, SMS to the student, full audit. Statements of results / provisional transcripts: PDF of every published semester with GPA, CGPA, classification and a QR verification code, issued by staff or downloaded by the student (withheld without results clearance), reissued only when the record changes (earlier ones superseded), verifiable on the public verification page. NACTVET results: Excel file per confirmed sheet and a record of each submission and NACTVET's response |
| Lecturer: my teaching, class lists and self check-in (BR-14, FR-TCH-02/03, BP-07, BP-08) | **My teaching** is where a lecturer lands after signing in: for a chosen semester, each module allocated to them with its class (programmes, co-lecturers), sessions and average attendance, the assessment plan and how much CA and SE is entered, where the results stand in moderation (with any comment from the Head of Department), examination eligibility, supplementary marks to enter and any open check-in; students below the attendance threshold across their modules; remarks assigned to them; and next steps linking to each page (e.g. no attendance yet, marks entry closing with CA/SE progress, results returned, all marks in and ready to submit). **Class lists** per module as a printable PDF that doubles as a paper attendance register (blank session columns, signatures) and as Excel with each student's attendance so far. **Self check-in** (optional in the SRS): the lecturer starts check-in for a class in progress and projects a six-digit code that changes every 20 seconds, with its QR code; students check in from the student portal by scanning or typing it (English and Swahili), late after a set number of minutes; a phone used for more than one student is flagged; wrong codes are limited per student; when check-in closes (by the lecturer or on time) everyone else is marked absent, low-attendance warnings go out and the lecturer can correct the register |
| NVA competence (BP-09, FR-EXM-09) | Competence units set up per semester from COMPETENCE modules (or mostly-NVA programmes), tasks with evidence, assessors allocated by the Examinations Office; assessors record competent / not yet competent per task with evidence, re-assessment attempts up to the maximum on Exam regulations (default 3); submission, Head of Department verification or return from Approvals (SMS to trainees not yet competent), reopening; award eligibility per class, confirmed list and Excel for NACTVET; the student sees outcomes per task on the results page |
| Finance overview, sponsors, waivers and refunds (FR-FIN-08/09/10/11/12, BP-17) | **Finance overview** where the Bursar and Accountant land: billed, paid %, owed (with the share due from sponsors), waived, money received, collections against the Bursar's targets, fees by class, debtors by age, largest debtors, 12-month receipts by month and purpose, bursary work and next steps, postings CSV. **Sponsors:** sponsorships per student and year (percentage of all fees or chosen items, optional cap), CSV import, one sponsor invoice with a line per student (never more than the student owes), GePG or bank remittance, each share moved onto the student's invoice as the sponsor pays, sponsor-pending amounts counting towards clearance, Excel statement; a **Sponsor Officer** sees only their sponsor. **Waivers and refunds:** requested with evidence, approved by the Bursar and above the threshold also by the Principal (never one's own request or both levels), waiver posted as a credit note, refund from credit with a voucher, SMS to the student |
| Hostel wardens, repairs; IPT department scope; student next steps | Wardens assigned to hostels work only their hostels (allocation, free beds, check-in and check-out); repairs logged by wardens or reported by students, with status updates by SMS. IPT coordinators can be limited to a department. The student home lists **what to do next** across the journey (registration, fees and control numbers, sponsor share, attendance, resits, competence, hostel, IPT, clearance), in English and Swahili |
| Teaching, attendance and examination eligibility (FR-TCH-02/03/04, FR-EXM-04, BP-07, BRU-08) | Class lists from approved semester registrations; lecturers record each class session (date, time, length, lecture/tutorial/practical/seminar, topic) with a one-tap register (present, late, absent, excused; everyone starts present), on a computer or a phone or tablet; a session saved while the connection is down is kept on the device and sent when it returns, and its reference stops it being recorded twice; sessions corrected or deleted until results are submitted; each student's attendance percentage (late counts as attended, excused is left out) with an SMS warning once they fall below the threshold; attendance per module for lecturers, Heads of Department (their department), the Examinations Office and management, plus a lecturer workload view (modules, credits, students, sessions, hours taught, modules not yet allocated). Examination eligibility lists generated by the Examinations Officer per semester from the institution's rules (minimum attendance, default 75%; every coursework item marked; examination fees clearance), with each student's reasons, regenerated as attendance, marks and payments change, SMS to students who become ineligible; exemptions asked for with a reason by a Head of Department, the Examinations Office or the Deputy Principal (Academic) and decided by someone else in academic management, with SMS on approval; an ineligible student gets no examination mark (on screen or by Excel) and is marked on the marks sheet; students see their attendance and eligibility per module on the portal home page; reports for attendance by module (TCH-01) and the eligibility list (EXM-04) |
| Clearance and graduation (FR-GRD-01..04, BR-29/30, BP-15, UC-18) | Finalists are found from published results: the latest published semester (after resits where there are any) is the programme's last, with a Pass and nothing outstanding; award, classification, GPA and credits are taken from it. Clearance offices are configured by Registry: the Head of the student's department, finance (cleared automatically when the student owes nothing and meets the graduation fees gate) and offices with assigned officers (library, hostel, sports and games by default). The student starts clearance on the portal (or Registry starts it); each office clears or holds with a reason, shown to the student and sent by SMS; the clearance completes when every office has cleared (SMS). Officers work from their office's queue; clearance officers and wardens see only Clearance. Graduations: Registry generates the graduand list from finalists with the award and full clearance (and sees who is not ready and why), sends it to the Governing Board; another administrator records the Board's approval with the minute reference or returns it; students become graduates and get an SMS. NACTVET graduand Excel file and a record of the submission and NACTVET's response. Award certificates: numbered once with a verification code and QR code, printed one by one or all together as A4 landscape PDF, every print logged and reprints marked and needing a reason; verifiable on the public verification page with minimal personal data. Reports GRD-01 (clearance status) and GRD-02 (graduand list) |
| Hostels (FR-HST-01..05, BR-28, BRU-18, BP-12, UC-16) | Bed inventory kept by the warden: fee categories (per bed per academic year), hostels designated female, male or mixed (each room of a mixed hostel female or male), rooms with block, floor, type, accessibility and status, beds that can be put out of service; live occupancy per hostel. Application windows per academic year with the payment period and priority rules. Active students apply on the portal with up to three hostel choices, a room type and special needs, and can change or withdraw until allocated. Allocation, automatic or manual: never against the room's gender, special needs, first years and students meeting the hostel fees gate first (each rule can be switched off), then who applied first; preferred hostels, accessible rooms and the room type are tried in that order; those without a bed go on the waiting list (SMS). The database itself refuses a second active allocation of a bed. The hostel fee is invoiced with a GePG control number (SMS with the bed, amount, control number and pay-by date); allocations still unpaid after the payment period are released every morning (or on demand), their invoice cancelled, and the beds offered to the waiting list. Check-in once the fee is paid, with the room's condition; check-out with condition, damages and a damage charge invoiced. Wardens see only Hostels, Clearance and Reports. Reports HST-01 (occupancy by hostel and gender) and HST-02 (allocations) |
| Industrial practical training (FR-IPT-01..03, BP-13) | The IPT Coordinator keeps placement organisations with contacts and how many students each can take, and IPT periods per semester linked to the IPT module, with the weights of the three marks (workplace supervisor, institution supervisor, logbook; adding up to 100) and how many logbook weeks must be approved first. Students propose a placement on the portal (an organisation in the list or a new one) until requests close; the coordinator confirms it with an institution supervisor (never beyond the organisation's capacity) or declines it with a note, or places students directly; both are sent by SMS. Numbered placement letters as PDF for the coordinator and the student. Students keep a weekly electronic logbook (no future weeks or weeks outside the placement), save drafts and submit; the institution supervisor approves or returns with a comment (SMS), records supervision visits and the marks; the IPT mark is computed when all three are in. The coordinator sends the IPT marks to the IPT module's results, which then go through the usual moderation, approval and publication. Lecturers see the students they supervise; the IPT Coordinator sees only Industrial training and Reports. Reports IPT-01 (placements by organisation) and IPT-02 (placement list) |
| Finance | Itemised invoices, **GePG control numbers** for public institutions, signed payment callback (idempotent), receipts, fee-paid event → application |
| Fee collection without GePG (private and faith-based institutions) | Ownership follows NACTVET (Public, Private, Faith-based/FBO; onboarding maps the catalogue's text). Only public institutions use GePG. Private and FBO institutions get a 12-digit **CIMS payment reference** per invoice ("8", institution id, six random digits, Luhn check digit) and list their own **payment methods** (Bursar, Finance › Payment methods): mobile money business numbers (M-Pesa, Mixx by Yas, Airtel Money, HaloPesa, Ezy Pesa, T-Pesa) and bank accounts (CRDB, NMB, NBC, Stanbic, Exim, Absa, Azania, DTB, Equity, KCB, TCB, PBZ, Amana, Mkombozi, others). Portals show step-by-step instructions per account in English and Kiswahili; SMS `PAYMENT_REFERENCE`. Payments are confirmed by the collector's HMAC-signed callback (`/api/integrations/collections/payment-notification`) or by importing the day's mobile money or bank statement on the reconciliation page; numbers issued before an ownership change are replaced on the next request |
| Portal (React) | Branded, bilingual (English/Kiswahili), mobile-first admission portal and a staff back-office (dashboard, applications, windows, programmes) |
| Back-office in Kiswahili (BR-40) | Every staff screen in English or Kiswahili, chosen on the staff sign-in page or in the menu (English/Kiswahili) and saved with the account (`user_accounts.language`, `PUT /api/account/language`), so it follows the person to another device and applies at sign-in; switching takes effect at once, without reloading. The staff screens are written in English and translated as React renders them: a JSX runtime of our own (`frontend/src/locale`, the `cims-jsx` import source) looks up text and label props (`label`, `title`, `placeholder`, `helperText`, …) in a dictionary of about 4,000 entries (`staff-sw.json`), including messages the server sends (errors, dashboard to-dos, report names and headings) and patterns for text built from values ("{0} result amendment(s) wait for the administrator."). Values, names, codes and anything typed are never translated; text with no entry stays in English, so a new screen is untranslated, never blank. Dates follow the language (Okt, Des). MUI's pagination and autocomplete wording is translated too |

NECTA, NACTVET, GePG, the payment collector and SMS are **simulated** in development (see *What is simulated*).

---

## Project layout

```
backend/    Spring Boot 3.3 · Java 21 · PostgreSQL 16 · Flyway
  src/main/java/tz/co/msingi/cims/
    platform/      tenancy, security, audit, numbering
    console/       Msingi platform console: platform users and sign-in, institutions, integration health, platform audit
    integration/   NECTA, NACTVET, HESLB, NIDA, MUSE and TAMISEMI clients (simulated and live); settings/; log/ records every call to outside systems
    academic/      years, intakes, departments, programmes
    admission/     windows, applicants, applications, eligibility engine
    registry/      students, registration numbers, hand-over, first registration
    finance/       fees, billing, invoices, control numbers, payments, GePG, clearance, reports
    notification/  SMS
    dev/           demo data seeder (dev profile only)
  src/main/resources/db/migration/   V1–V39 (V5 = row-level security, V6 = selection, V7 = NACTVET verification and offers, V8 = admission letters, V9 = students, V10 = fees, V11 = GePG reconciliation, V12 = student portal, V13 = messaging, V14 = curricula, calendar and semester registration, V15 = examinations and results, V16 = resits, amendments, transcripts and NACTVET results submissions, V17 = class sessions, attendance and examination eligibility, V18 = clearance offices, clearances, graduations, graduands and certificates, V19 = hostels, rooms, beds, windows, applications and allocations, V20 = placement organisations, IPT periods, placements, logbooks, visits and assessments, V21 = admission window opening and closing record, V22 = applicants registered by NECTA index number, V23 = ward, village/street and fuller next-of-kin details, V24 = education history, V25 = NTA awards from NACTVET and other awards, V26 = qualification checks, V27 = changes after submission, V28 = programme entry level, V29 = institution photos, V30 = staff user management, V31 = student status changes, photos and ID cards, V32 = platform console: platform users, platform audit trail, integration log, V33 = NACTVET institution/programme catalogue, V34 = remarks and appeals, V35 = self check-in, V36 = NVA competence, V37 = sponsors, waivers/refunds and finance targets, V38 = hostel wardens and repairs, V39 = FBO ownership, payment methods and payment references, … V48 = government integrations: settings, NIN, HESLB, MUSE, TAMISEMI, NACTVET registration and results by API)
frontend/   React 18 · TypeScript · Vite 8 · MUI 6 · React Router 7
nactvet-mock/  stand-in NACTVET server: admission verification, student registration, results (see nactvet-mock/README.md)
gov-mocks/  stand-in GePG, HESLB, NIDA, MUSE and TAMISEMI servers for trying the live adapters (see gov-mocks/README.md)
dev-keys/   TEST-ONLY GePG key pairs for CIMS and the GePG mock (never used with the real GePG)
deploy/     production compose file, TLS proxy, PostgreSQL with WAL archiving, encrypted backups and restore (see deploy/README.md)
loadtest/   k6 and Locust load tests for NFR-PER (see loadtest/README.md)
.github/workflows/  CI (tests, build, images) and a manual load-test workflow
docs/       Concept Note, BRD, SRS, Business Processes, Database Design, Architecture, Plan & Cost
docker/db-init/  database role and databases for Docker (non-superuser app role)
docker-compose.yml
```

## Prerequisites

- JDK 21 and Maven 3.9+
- Node.js 22.12 or newer (Vite 8 and Vitest 5 need it; check with `node -v`)
- PostgreSQL 16 (local install or via Docker)

## Quick start with Docker

```bash
docker compose up --build
```

- Admission portal: http://localhost:8081
- API docs (Swagger UI): http://localhost:8085/swagger-ui.html

## Local development

**1. Database** (once). Easiest with Docker — PostgreSQL is published on host port **5434** so it does not clash with other local databases:

```bash
docker compose up -d db
```

On first start, `docker/db-init/01-cims-roles.sql` creates the application role `cims` and the databases `cims` and `cims_test` (for integration tests).

Or with a local PostgreSQL install (then set `CIMS_DB_URL=jdbc:postgresql://localhost:5432/cims`):

```sql
CREATE ROLE cims LOGIN PASSWORD 'cims' NOSUPERUSER NOBYPASSRLS;
CREATE DATABASE cims OWNER cims;
CREATE DATABASE cims_test OWNER cims;        -- for integration tests
```

#### Database role

The application must connect as an **ordinary role that owns the database** — never a superuser and never a role with `BYPASSRLS`. PostgreSQL does not apply row-level security to those roles, so every institution would see every other institution's data. The backend checks this at start-up and refuses to run if the role bypasses RLS. The same rule applies in production: create a dedicated application role; keep the `postgres` superuser for administration.

> **Upgrading a Docker database created before this change** (where `cims` was the superuser): the volume must be recreated. This deletes the local data; the dev profile re-seeds the demo institution.
>
> ```bash
> docker compose down -v
> docker compose up -d db
> ```

**2. Backend** (dev profile seeds a demo institution):

```bash
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"     # Windows (PowerShell)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev            # Linux / macOS
```

The Maven Wrapper (`mvnw`) uses Maven 3.9.15 and reuses it from `~/.m2/wrapper` if already downloaded. The backend listens on **8085** by default (`PORT` to change).

**3. Frontend:**

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173 (proxies /api to 127.0.0.1:8085; override with CIMS_API_URL)
```

### Demo data (dev profile)

| What | Value |
|---|---|
| Institution | Msingi Demo Technical College (`DEMO`), mapped to `localhost` and `127.0.0.1` |
| Staff — administrator / approver | `admin` / `Admin@2026` |
| Staff — admissions officer | `admissions` / `Admissions@2026` |
| Staff — registry officer | `registry` / `Registry@2026` |
| Staff — bursar / accountant | `bursar` / `Bursar@2026`, `accountant` / `Accountant@2026` (both land on the Finance overview) |
| Staff — head of department / deputy principal (academic) | `hod` / `Hod@2026` (prepares curricula, moderates marks), `academic` / `Academic@2026` (approves curricula, keeps the calendar, chairs the Academic Committee) |
| Staff — lecturer / examinations officer | `lecturer` / `Lecturer@2026` (lands on My teaching; records attendance or opens self check-in, enters marks, prints class lists), `exams` / `Exams@2026` (sets up modules, generates eligibility lists, computes and publishes results); `admin` confirms results for the Governing Board |
| Staff — clearance officers | `library` / `Library@2026` (library and sports and games offices), `warden` / `Warden@2026` (hostel office and the Hostels module); `hod` clears for the department, `registry` runs graduations and `admin` records the Governing Board's approval |
| Staff — IPT coordinator | `ipt` / `Ipt@2026` (organisations, IPT periods, placements and sending IPT marks to results); `lecturer` supervises the demo placements |
| Staff — principal, auditor, assessor, programme coordinator | `principal` / `Principal@2026` (second-level approval of large waivers and refunds), `auditor` / `Auditor@2026`, `assessor` / `Assessor@2026` (lands on My teaching with the competence units allocated to them), `coordinator` / `Coordinator@2026` |
| Sponsor officer | none seeded: add a sponsor under Sponsors, then create a user with the Sponsor Officer role and that sponsor on Users & roles |
| Industrial training | Four placement organisations and an IPT period in the current semester that started two weeks ago, open for student requests for three more weeks |
| Examination regulations | NTA Levels 4–5 and NTA Level 6, from the earliest academic year (indicative values: confirm against your institution's regulations); examination marks entry open on the current demo semester |
| Curricula and calendar | Approved BTCIT and ODIT curricula; 2027/2028 Semester 1 with registration open from two days before the first start, Semester 2 later |
| Open window | 2027/2028 September Intake, fee TZS 10,000, four programmes |
| Staff back-office | http://localhost:5173/staff |

**Simulated NECTA index numbers** (any year; the simulated NECTA makes up names, sex and school from the index number):

| Index | Result |
|---|---|
| `S0101/0001` | Strong results — eligible for all demo programmes |
| `S0101/0002` | Weak results — not eligible for NTA programmes |
| `S0101/0000` | Not found |
| anything else in `S1234/5678` form | Deterministic random results |

**Simulated NACTVET outcomes** — decided by the last digit of the applicant's CSEE index number:

| Last digit | Outcome |
|---|---|
| 7 | Queried (name does not match NECTA); verified once the query is resolved and resubmitted |
| 9 | Not verified (does not meet minimum entry requirements) |
| 3 | Verified, also admitted by another institution: the applicant must enter NACTVET's confirmation code to accept |
| any other | Verified |

In the dev profile the SMS verification code and the NACTVET confirmation code are also shown on screen, and a **Simulate payment** button pays the GePG control number.

## Tests

```bash
cd backend
.\mvnw.cmd test            # unit + integration tests (needs the cims_test database on port 5434)
.\mvnw.cmd test "-DskipITs"  # unit tests only
```

Frontend unit tests (the Kiswahili dictionary and runtime):

```bash
cd frontend
npm test
```

End-to-end and accessibility tests (Playwright with axe, WCAG 2.1 A/AA) run against a running demo institution — start the
backend with the dev profile and `npm run dev` first. CI runs them in Chromium, Firefox and WebKit and at phone size:

```bash
cd frontend
npx playwright install chromium        # once
npm run e2e -- --project=chromium      # or: npm run e2e  (all browsers, after installing them)
```

To add or correct Kiswahili for a staff screen, add the English text as it appears in the code (or as the server sends it) and its Kiswahili to `frontend/src/locale/staff-sw.json`; for text built from values use `{0}`, `{1}` … in both (`"{0} students": "Wanafunzi {0}"`). A status on a chip can be worded differently from a button with a `[label] ` key (`"[label] Open": "Wazi"`, `"Open": "Fungua"`).

Integration tests run against real PostgreSQL so that row-level security, triggers and constraints are exercised. They include:

- `TenantIsolationIT` — one institution cannot read or write another's rows (repository, native SQL, and API token checks);
- `AmendAfterSubmissionIT` — details, other qualifications and choices changed after submitting while the window is open (still submitted, same reference, time of change kept), details not left incomplete, choices not emptied, a qualification a choice needs not removable, changes stopped once selection starts or the window closes, programmes follow the entry level of the highest verified qualification;
- `ApplicantSearchIT` — the office finds applicants by index number, programme (first choice), qualifications to check, status, fee and sex, invalid filters and other roles refused, exports the list as CSV and prints the application file, both recorded and shown in the application's history;
- `ApprovalsIT` — the Deputy Principal sees a proposed selection round to approve and a curriculum still being prepared, the proposer sees the round but cannot decide it, other approvers and roles see nothing or are refused, and decided items leave the list;
- `OverviewIT` — the Principal's institution overview: students, new intake against accredited places, applicants per place, empty fee and result figures before billing and publication, attention flags by programme, intake and collection trends, unknown years and other roles refused;
- `DataScopeIT` — Heads of Department and Programme Coordinators see only their programmes and students (lists, a student's record, class lists, statistics, dashboard), are refused others, create modules and curricula only for their department or programme, and run reports only for their programmes (chosen for them when they have one);
- `AuditTrailIT` — the Auditor sees who did what, when and from where, failed sign-ins with their reason, filters (security events, person, text, area, a record's history, paging), date checks, the summary and filter choices, CSV export recorded in the trail, other institutions' auditors seeing nothing, other roles refused, and entries that cannot be updated or deleted even in the database;
- `StaffUserManagementIT` — the administrator creates staff accounts with scoped roles and a temporary password that only allows the password change until replaced, roles that cannot be given or limited that way refused, changed roles, disabling and resets ending existing sessions, enable and reset, the institution keeping an administrator (no disabling yourself or removing your own administrator role), only the administrator changing accounts and the Auditor viewing them;
- `InstitutionPhotoIT` — the administrator uploads, re-captions, replaces and removes the campus photo, the portal shows it to everyone with a cache-busting address, other institutions do not see it, non-images, small photos and long captions refused, only the administrator manages it;
- `EntryLevelTest` — the NOAS entry-level rule: NTA award → next NTA level only, ACSEE → NVA 1–3, NTA 4 and A-level NTA 5/7, CSEE → NVA 1–3 and NTA 4, unverified qualifications ignored;
- `QualificationChecksIT` — the office's queue of declared qualifications with the applications they are on, verifying, not accepting only with a reason (SMS), NECTA results not checked again, only the Admissions Office deciding, the states seen by the applicant and on the application;
- `OtherQualificationsIT` — NTA award looked up at (simulated) NACTVET with level, programme, institution, GPA, class and modules, duplicates, not found, names not matching, other awards declared with or without a certificate number, invalid levels;
- `ApplicantProfileIT` — region, district, ward and village/street lists, profile options, addresses checked down the levels (any spelling, saved as the data spells it), impairment and relationship from the lists, next-of-kin details, primary school search and choice by code or typed, O-level school from the registration results (not overwritable), A-level school from fetched ACSEE results and released when they are removed;
- `ApplicantJourneyIT` — NECTA look-up by index number and year (dashes, lower case and the year typed with the index accepted) → registration under NECTA's names (names sent by the browser ignored) → verify → CSEE results already stored and locked → sign-in by index number → another sitting → profile (names unchanged) → GePG fee → choices → submit; one account per index number, invalid and unknown index numbers, restarting an unconfirmed sign-up, duplicate phones, privacy consent; `LiveNectaClientTest` — the NECTA results API request (dash index, exam id, key) and response mapping, unknown candidates, NECTA unreachable, the key required;
- `AdmissionWindowsIT` — creating, publishing to open later (shown as upcoming), opening now, closing early with a reason (SMS to applicants who have not submitted, no new applications), reopening with a new closing date, extending, only the Admissions Office / administrator / Deputy Principal (Academic) opening and closing, no changes to a closed window, no reopening once selection has started, automatic closing at the closing date, the opening and closing record;
- `FeeRemindersAndEntryTestIT` — application-fee reminders once after two days and once before closing, never for paid applications or closed windows; the entry-rule test tool with sample results, changed rules tried before saving, entry levels, invalid grades and levels;
- `GepgCallbackIT` — signature check and idempotent payment posting;
- `EligibilityEngineTest` — NACTVET entry rule evaluation;
- `SelectionIT`, `SelectionAllocatorTest`, `MeritScorerTest` — merit ranking, stable allocation, committee exclusions and approval;
- `VerificationIT`, `SimulatedNactvetGatewayTest`, `CsvTest` — NACTVET submission by API and file, outcomes, queries, offers, multiple-admission confirmation and lock-out, lapse, released places, access rules (also run against `nactvet-mock` in live mode);
- `LiveNactvetGatewayTest` — the live adapter against a stand-in HTTP server: the template and credentials sent, outcomes read with those still processing left for later, confirmation and confirmation elsewhere, refused credentials, outages and missing references, mock confirmation codes only when enabled;
- `LetterIT`, `AdmissionLetterRendererTest`, `RateLimiterTest` — letter issued on acceptance, PDF content and QR code decoded from the rendered page, public verification, reissue/revoke, access rules, escaping;
- `RegistryIT`, `RegistrationNumberPatternTest` — hand-over, numbering patterns, numbers skipped when already taken, database refusal to change or delete, first registration, class list, isolation;
- `FeesIT`, `PaymentAllocatorTest` — Bursar approval and versions, billing on acceptance and in runs, foreign rates, allocation and credit, registration clearance, reports, privacy;
- `ReconciliationIT` — every exception kind, posting a missed payment, close and re-import rules, a payment matched on a later day, monthly sign-off, invalid files, roles and isolation;
- `StudentPortalIT`, `AmountInWordsTest` — student sign-in by registration number, dashboard, notice audiences and scheduling, read tracking, Registry decisions on detail changes, receipts and privacy;
- `MessagingIT`, `SmsTextTest` — templates and language, masked verification codes, no SMS on rollback, bulk audiences and placeholders, bundle balance and charging, gateway refusals and retries, resending, signed delivery reports, isolation;
- `ReportsIT` — catalogue by role, admissions and registry figures, filters and validation, Excel and PDF content, audit of exports, dashboards by role, isolation;
- `CurriculumAndRegistrationIT` — modules and duplicate codes, curriculum checks, approval by someone other than the preparer, locked modules, new versions, calendar dates, the semester registration states and windows, elective rules, fee clearance on submission and approval, approve all, return and resubmit, class-list reports, isolation; the late-registration fee (needs a late period, billed once, cancelled with the registration, shown to Registry) and the waivers proposed when a student withdraws;
- `GradingTest` — weighting and rounding, D is not a pass, CA/SE pass marks, absent and incomplete, the diploma scale, GPA rounding and truncation, classification, decisions, scale validation;
- `ResultsIT` — regulations checks and lock, set-up and allocation, assessment plans, marks on screen and by Excel (rejected file changes nothing), moderation and return, department approval before computation, GPA, decisions and classification, committee changes with reasons, segregation of duties, database lock, publication with withheld results and SMS, reports, isolation;
- `AttendanceAndEligibilityIT` — sessions recorded only by the module's lecturer, a retried save not duplicated, no clashing or future sessions, percentages with late and excused, one SMS warning per student, corrections, summaries for lecturer / Head of Department / another department's head, workload, the student’s view, deleting a session; eligibility from attendance, coursework and examination clearance, SMS to ineligible students, no examination mark for them, exemptions asked and decided by different people, regenerating keeps approved exemptions, reports TCH-01 and EXM-04; self check-in with the rotating code (QR link, same-phone flag, wrong-code limit, no editing while open, absentees on close by the lecturer or on time, corrections); the lecturer's My teaching board and class lists (PDF and Excel, lecturer and Head of Department only); `TallyTest` — the attendance arithmetic;
- `ClearanceAndGraduationIT` — finalists from published final results (with and without the award), default offices and officer assignment, starting clearance on the portal and by Registry, automatic finance from the balance, holds with reasons and SMS, decisions only by the office's officers or the student's Head of Department, completion, the graduand list with not-ready reasons, Board approval by someone other than the submitter, graduates, certificates numbered once and reprints with a reason, public verification, the NACTVET file and submission, the student's view, reports GRD-01 and GRD-02;
- `HostelIT` — fee categories, gender rules for hostels and rooms, duplicate rooms, the student's hostel choices, applications, priority (special needs first), automatic allocation with the waiting list and SMS, invoices with control numbers, manual allocation (never a room of the other gender), the database refusing a second active allocation of a bed, check-in only once paid, check-out with damages invoiced, release of unpaid allocations with the invoice cancelled, cancelling an allocation, occupancy, the student's view, reports HST-01 and HST-02; wardens working only their hostels and repairs reported by students; moves to another bed (gender and same-bed checks, the difference for the rest of the occupancy period billed or refunded, history), refunds of the unused part at early check-out and of what was paid when a paid bed is cancelled, and an unpaid bed released when its student withdraws;
- `IptIT` — organisations and duplicate names, IPT period weights, student requests (an organisation in the list or a new one), decline with a note, confirmation with a supervisor and a numbered letter, organisation capacity, direct placement, the letter PDF and SMS, logbook drafts, submission, locking, return with a comment and approval, only the student's supervisor reviewing, visits, the three marks and the weighted IPT mark, the logbook mark needing enough approved weeks, sending marks to the module's results (row total and grade), the student's view, reports IPT-01 and IPT-02; a department's coordinator placing only that department's students;
- `ResitsAndTranscriptsIT` — resit eligibility (supplementary and special), registration and GePG fee, marks only once paid, the supplementary cap, a coursework fail that a resit cannot fix, approval, the RESIT sheet with carry over, student results after resits, the statement of results PDF and its verification, amendment requested and approved by different people with GPA recomputed and SMS, superseded statements, the NACTVET results file and submission record;
- `CompetenceIT` — unit set-up from COMPETENCE modules, tasks and assessors, outcomes only by allocated assessors, corrections before verification, re-assessment attempts and the maximum, incomplete submission refused, verification and return, award eligibility, confirmed list and Excel, the student's view;
- `SponsorsAndAdjustmentsIT` — sponsors and sponsorships, CSV import, the sponsor invoice capped at what each student owes, bank remittance and duplicate references, shares moved onto students' invoices, sponsor-pending in clearance, the sponsor officer's scope, waivers and refunds with the threshold and two levels, own-request refusal, credit notes and refund vouchers, finance dashboard and targets;
- `DatabaseRoleGuardTest` — start-up refuses a role that bypasses row-level security.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `CIMS_DB_URL` / `CIMS_DB_USER` / `CIMS_DB_PASSWORD` | `jdbc:postgresql://localhost:5434/cims`, `cims`, `cims` | Database |
| `CIMS_SECURITY_MODE` | `local` | `local` (CIMS issues tokens) or `keycloak` |
| `CIMS_JWT_PRIVATE_KEY` / `CIMS_JWT_PUBLIC_KEY` | generated at start-up | RSA keys (PEM) for local mode — **set in production** |
| `CIMS_KEYCLOAK_ISSUER_URI` | — | Issuer when `CIMS_SECURITY_MODE=keycloak` |
| `CIMS_ACCESS_TOKEN_TTL` | `60m` | Access token lifetime |
| `PORT` | `8085` | Backend HTTP port |
| `CIMS_TEST_DB_URL` | `jdbc:postgresql://localhost:5434/cims_test` | Test database |
| `CIMS_CORS_ORIGINS` | `http://localhost:5173` | Allowed browser origins |
| `CIMS_GEPG_MODE` / `CIMS_GEPG_CALLBACK_SECRET` | `simulated` / `change-me` | GePG adapter (public institutions) |
| `CIMS_EMAIL_MODE` / `CIMS_EMAIL_FROM` / `CIMS_SMTP_HOST` / `CIMS_SMTP_PORT` / `CIMS_SMTP_USERNAME` / `CIMS_SMTP_PASSWORD` | `simulated` / `no-reply@cims.msingi.co.tz` / `localhost` / `1027` | Email channel: `simulated` writes to the log and the outbox; `smtp` sends through the mail server. In development run `docker compose up -d mailpit` and use `CIMS_EMAIL_MODE=smtp` to read every email at http://localhost:8027 |
| `CIMS_COLLECTION_MODE` / `CIMS_COLLECTION_CALLBACK_SECRET` | `simulated` / `change-me` | Private and FBO institutions: `statement` in production until a collector adapter is added (payments confirmed from statements and the signed callback) |
| `CIMS_NECTA_MODE` | `simulated` | NECTA results: `simulated` or `live` (the NECTA results API, `POST /api/results/individual`) |
| `NECTA_API_KEY` | — | NECTA results API key; required when `CIMS_NECTA_MODE=live`. For development it can sit in `backend/config/application-dev.yml` (not committed; copy `application-dev.yml.example`), which makes the dev profile use live NECTA |
| `CIMS_NECTA_BASE_URL` | `https://api.necta.go.tz` | NECTA results API address |
| `CIMS_NACTVET_MODE` | `simulated` | NACTVET admission verification: `simulated` (in CIMS) or `live` (HTTP, NOAS style). Live uses `CIMS_NACTVET_VERIFICATION_URL` (default the NACTE base URL) and `CIMS_NACTVET_VERIFICATION_AUTHORIZATION` / `CIMS_NACTVET_VERIFICATION_API_CODE` (default `NACTE_AUTHORIZATION` / `NACTE_API_CODE`); the paths are placeholders until NACTVET publishes the API (`CIMS_NACTVET_SUBMIT_PATH`, `CIMS_NACTVET_RESULTS_PATH`, `CIMS_NACTVET_CONFIRM_PATH`). The `nactvet-mock` profile points live mode at the `nactvet-mock` server on port 8099 |
| `CIMS_NACTVET_AWARDS_MODE` / `NACTE_AUTHORIZATION` / `NACTE_API_CODE` | `simulated` / — / — | Applicants' NTA awards from NACTVET's particulars service (`live` needs the NACTE credentials; for development they can sit in `backend/config/application-dev.yml`, not committed) |
| `CIMS_DATA_KEY` | development key (warning in the log) | Encrypts staff authenticator-app secrets: base64 of 32 random bytes (`openssl rand -base64 32`). **Set in production** and keep it safe — without it, staff must set up two-step sign-in again |
| `CIMS_PORTAL_URL` | `https://{host}` (dev: `http://localhost:5173`) | The portal address in personal links sent by SMS and email (workplace assessment, tracer surveys) |
| `CIMS_RETENTION_CRON` | `0 0 2 1 * *` | When retention runs (monthly, only for institutions that switched it on; `-` disables) |
| `CIMS_OFFER_EXPIRY_CRON` | `0 5 * * * *` | When expired offers are closed (`-` disables) |
| `CIMS_FEE_REMINDER_CRON` | `0 0 9 * * *` | When application-fee reminders are sent (applicants with an unpaid control number; each window can switch them off; `-` disables) |
| `CIMS_VERIFICATION_URL` | `https://{host}/verify/{code}` (dev: `http://localhost:5173/verify/{code}`) | Link encoded in letter QR codes; `{host}` is the institution's primary domain |

Behind a reverse proxy, set `server.forward-headers-strategy=native` (or `framework`) so the public verification rate limit applies per real client address.
| `CIMS_PLATFORM_BOOTSTRAP_USERNAME` / `CIMS_PLATFORM_BOOTSTRAP_PASSWORD` | `msingi` / — (dev: `Msingi@2026`) | The first Msingi platform console account, created at start-up when there is none; must change the password at first sign-in |
| `CIMS_INTEGRATION_LOG_RETENTION_DAYS` | `365` | How long calls to NECTA, NACTVET, GePG and the SMS gateway are kept in the integration log |
| `CIMS_NACTVET_CATALOGUE_MODE` | follows `CIMS_NACTVET_AWARDS_MODE` | NACTVET institution/programme catalogue for the platform console: `live` (uses `NACTE_AUTHORIZATION` / `NACTE_API_CODE`) or `simulated` (a sample) |
| `cims.platform.staff-login-url` | `https://{host}/staff/login` | Staff sign-in address shown to the platform console after onboarding |
| `VITE_TENANT` (frontend) | — | Force an institution code in development |
| `CIMS_API_URL` (frontend dev server) | `http://127.0.0.1:8085` | Where the Vite proxy sends `/api` |

## Security and privacy

- **Two-step sign-in (staff):** authenticator app (TOTP, RFC 6238) with 10 single-use recovery codes and an SMS code as a fall-back. The institution administrator chooses who must use it (Security & privacy → Sign-in & sessions): optional, privileged roles, or all staff. A lost phone is handled by another administrator (Users & roles → Reset two-step sign-in).
- **Idle sign-out:** minutes without activity per user type (staff, students, applicants), with a minute's warning.
- **Common passwords** (e.g. `Tanzania@2024`, `P@ssw0rd1`) are refused on sign-up, change and reset, even when they meet the complexity rule.
- **Personal data (PDPA 2022):** everyone can download their data from their account and send a request (copy, correction, erasure, objection); the Registrar or administrator answers within 30 days. Erasure applies to applicants who never became students. Retention anonymises such applicants and clears old SMS/email texts after the periods the institution sets, with a preview first.

## Data migration and external selections

Staff → **Data import** brings records in from CSV or Excel (.xlsx); each tab has a template. Every row is checked first and the problems can be downloaded; nothing changes until **someone other than the uploader** signs the file off, when it is checked again and applied whole or not at all.

| File | Who | What it does |
|---|---|---|
| Students | Registrar, administrator | Students from before CIMS (academic years not yet in CIMS are added). Each gets a student-portal sign-in by registration number; they set a password with *Forgot password* and their phone |
| Earlier results | Registrar, Examinations Officer | One row per module, with the semester GPA and decision; shown with the student's record, on the student portal and in the results across years |
| Opening balances | Bursar, Accountant | Positive = owed (an invoice); negative = paid in excess (credit used against the next invoice) |
| External selections | Admissions Officer | Students selected by TAMISEMI or another body join a window as *selected*: NECTA results are fetched, they go to NACTVET verification and offers like any applicant, and an SMS asks them to sign up with their index number |

## Government integrations

Each outside system has a simulated adapter (development, tests) and a live one, chosen per deployment with
`CIMS_<SYSTEM>_MODE=live`. Each institution enters its own codes and switches the optional exchanges on under
**Administration → Integrations**. Every call is logged (platform console → Integrations, and the auditor's log).
`gov-mocks` and `nactvet-mock` answer the live adapters on a developer's computer (profiles `dev,gov-mocks,nactvet-mock`).

| System | What CIMS does | Live adapter |
|---|---|---|
| GePG (FR-INT-03) | Bill per invoice with a line per GFS code; control number by signed callback (pending until it arrives, SMS when it does); signed payment notifications; reconciliation asked for by API (*Ask GePG* on the reconciliation page) or imported from GePG's file | `LiveGepgGateway`: XML signed SHA1withRSA with the deployment's keystore, GePG's answers checked against its certificate; per-institution SP code, sub SP code, system id, default GFS code |
| NACTVET registration (FR-REG-07) | Registrar sends the year's new students as a batch; each is registered or queried; queried students are corrected and sent again | `LiveNactvetSubmissionClient` (NOAS style, placeholder paths) |
| NACTVET results (FR-EXM-11) | *Send to NACTVET* on a confirmed result sheet; NACTVET's acceptance or queries per student; the file route remains | as above |
| HESLB (FR-INT-05) | Registration and continuation data out; beneficiaries in as HESLB sponsorships capped at what HESLB pays the institution; disbursements in, recorded against the HESLB sponsor invoice | `LiveHeslbClient` (JSON + API key, placeholder paths) |
| NIDA (FR-INT-06) | NIN kept encrypted (AES-256-GCM, last four digits shown, keyed hash against duplicates); checked with NIDA against name and birth date when the institution uses it; entered by the student or the Registrar | `LiveNidaClient` |
| MUSE (FR-INT-07) | Preview and post a period's collections: bank debited, revenue credited per GFS code; each period once | `LiveMuseClient` |
| TAMISEMI (FR-INT-08) | Fetches the year's selections into a selections import awaiting a second person's sign-off | `LiveTamisemiClient` |

## Deployment, CI and load tests

- **Production**: `deploy/docker-compose.prod.yml` (PostgreSQL with WAL archiving, backend, web app, TLS proxy, encrypted
  backups kept 35 days and 12 months, restore script). Start-up refuses development-only settings under the `prod`
  profile. See `deploy/README.md`.
- **CI**: `.github/workflows/ci.yml` runs the backend tests against PostgreSQL 16 with a non-superuser role, the mocks'
  tests, the web app's type check and build, shell and compose checks, and builds the Docker images (pushed to GHCR on
  version tags).
- **End-to-end and security**: `.github/workflows/e2e-security.yml` starts the demo institution and runs the Playwright
  smoke and accessibility suites in three browsers, an OWASP ZAP baseline scan (report kept as an artifact; rules in
  `.zap/rules.tsv`) and a Trivy scan of dependencies, secrets and Dockerfiles (accepted findings, each with its reason, in `.trivyignore.yaml`); on
  pushes to main, pull requests and every Monday. The main CI also runs `npm audit --audit-level=high`.
- **Several backend instances**: scheduled jobs take a row in `job_locks` before running (like ShedLock: held at most a
  set time if an instance dies, and at least a short time so a slightly later clock does not run a job twice), so the
  backend can run as several replicas behind the proxy.
- **Load tests**: `loadtest/k6/cims.js` and `loadtest/locust/locustfile.py` with the NFR-PER-01 thresholds; results in
  `loadtest/README.md`.

## Integration API (connected systems)

Other systems (an LMS, accounting, a library system) read CIMS with OAuth2 client credentials. The administrator adds the system under Security & privacy → Connected systems, chooses what it may read and hands over the client ID and secret (shown once).

```bash
# a token (the institution comes from the host name, or X-Tenant in development)
curl -s -X POST https://apply.college.ac.tz/api/oauth/token -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=client_credentials
# then, with "Authorization: Bearer <access_token>"
GET /api/integration/v1/programmes                       # programmes:read
GET /api/integration/v1/students?updatedSince=…&page=0   # students:read
GET /api/integration/v1/students/{regNo}                 # students:read
GET /api/integration/v1/students/{regNo}/results         # results:read (published results)
GET /api/integration/v1/payments?since=2026-01-01T00:00:00Z   # payments:read
```

Tokens last an hour, work only on `/api/integration/v1/**`, and stop at once when the administrator switches the system off.

## How multi-tenancy works

1. `TenantResolutionFilter` resolves the institution from the host name (`tenant_domains`) or the `X-Tenant` header, before security runs.
2. `TenantAwareJpaTransactionManager` starts every transaction with `set_config('app.tenant_id', …, true)`.
3. Row-level security policies (`V5__row_level_security.sql`) make other institutions' rows invisible and unwritable — including for native SQL.
4. JWTs carry a `tenant` claim; a token from one institution is rejected by another.
5. The Msingi platform console (`/platform`, API `/api/platform/**`) belongs to no institution: requests there ignore the host and `X-Tenant`, its staff are in `platform_users`, and its tokens (issuer `cims-platform`, no `tenant` claim) are refused everywhere else. It acts inside an institution only by switching to that institution, so row-level security still applies. The integration log (`integration_calls`) is the one table the platform reads across institutions.
6. A suspended institution answers `503 INSTITUTION_SUSPENDED` on every request (GePG notifications are still accepted). Each server caches institution lookups for 5 minutes, so suspension and new domains reach all servers within that time.

Production notes: connect the application with a role that is **not** a superuser; preferably run Flyway with a separate migration role and grant the application role DML only.

## What is simulated (and what comes next)

| Component | Now | Next |
|---|---|---|
| NECTA | `SimulatedNectaClient`; `LiveNectaClient` for the NECTA results API (switch with `CIMS_NECTA_MODE=live` and `NECTA_API_KEY`) | Caching NECTA answers; matching the names on later sittings with the registered names |
| GePG | `SimulatedGepgGateway`; `LiveGepgGateway` (signed XML, control number and payment callbacks, reconciliation by API), tried end to end against `gov-mocks` | GePG UAT on-boarding (keys, SP codes) and a scheduled daily reconciliation request |
| Payment collector (private/FBO) | `SimulatedCollectionGateway`; `StatementCollectionGateway` for production; generic HMAC-signed callback; statement import | An adapter for the chosen aggregator (e.g. Selcom, AzamPay, ClickPesa) or bank collection API with its own signature scheme and USSD push |
| SMS | `SimulatedSmsGateway` (log only; 000 = unreachable, 999 = refused), outbox, delivery-report callback | Aggregator adapter (`SmsGateway`) for the chosen provider; email channel |
| NACTVET | Admission verification, student registration and results by API: simulated and live adapters, tried end to end against `nactvet-mock` | NACTVET's real endpoints and field names (configuration only) |
| HESLB, NIDA, MUSE, TAMISEMI | Simulated and live adapters, tried end to end against `gov-mocks` | Each body's interface document and access (configuration only) |
| Letters | HTML template (`templates/admission-letter.html`) → PDF | Per-institution letter templates and logo upload; email delivery |
| Reconciliation | Daily GePG file import, exception handling, day close and Bursar sign-off (FR-FIN-05); sponsors, waivers and refunds | Bank statement reconciliation; instalment plans |
| Registration | Hand-over, first registration, student requests to correct details, semester and module registration with fee clearance, status changes with HoD recommendation and Deputy Principal approval (FR-REG-04, BP-14), student photos and ID cards with QR verification and batch printing (FR-REG-06) | Carry-over and repeat modules (needs results), late-registration fee |
| Examinations | NTA marks, moderation, approval, GPA, decisions, publication, supplementary and special examinations, amendments, statements of results, NACTVET results file (indicative layout) | Registering carried-over modules in the next year's semester registration, NVA competence records (FR-EXM-09), NACTVET's official results template, examination analytics across years (FR-EXM-14), final award transcripts at graduation |
| Teaching | Sessions and attendance entered by the lecturer, kept on the device while offline, eligibility lists and exemptions | Timetabling, check-in limited to the classroom (Wi-Fi or location), partitioning attendance by academic year at scale |
| Graduation | Online clearance, graduand lists and Board approval, certificates with QR verification | Alumni contacts and tracer surveys (FR-GRD-05), signature images and security features on printed certificates, a final award transcript distinct from the statement of results |
| Hostels | Inventory, applications, allocation, invoices, release of unpaid beds, check-in and check-out, wardens per hostel, repairs (FR-HST-06) | Room changes and swaps, refunds when a paid allocation is cancelled, discipline holds |
| Industrial training | Organisations, periods, placement requests and letters, logbooks, visits, marks and transfer to results | Online access for workplace supervisors to enter their mark, visit planning with routes and allowances, employer feedback surveys, IPT placement maps |
| Identity | Local JWT issuer | Keycloak realm per institution, MFA for privileged roles |

---

© 2026 Msingi Technologies Ltd. All rights reserved.
