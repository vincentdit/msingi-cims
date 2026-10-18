package tz.co.msingi.cims.notification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The event messages CIMS sends (FR-COM-01), with built-in English and Kiswahili wording (FR-COM-03).
 * Institutions may reword them; placeholders in braces are filled in when the message is sent.
 */
public final class MessageTemplates {

    public record Definition(String code, String name, String when, String group, List<String> variables, Set<String> required,
                             String en, String sw, Map<String, String> sample) {
    }

    public static final Map<String, Definition> ALL = new LinkedHashMap<>();

    private static void add(String code, String name, String when, String group, List<String> vars, Set<String> required,
                            String en, String sw, Map<String, String> sample) {
        ALL.put(code, new Definition(code, name, when, group, vars, required, en, sw, sample));
    }

    static {
        Map<String, String> s = Map.ofEntries(
                Map.entry("code", "482913"), Map.entry("ticketNo", "HD-2026-00042"), Map.entry("subject", "Control number not received"),
                Map.entry("due", "18 Oct 2026, 16:00"), Map.entry("institution", "Msingi Demo Technical College"), Map.entry("minutes", "10"),
                Map.entry("referenceNo", "DEMO/APP/2027/000123"), Map.entry("controlNo", "991234567890"), Map.entry("amount", "TZS 320,000"),
                Map.entry("description", "Fees 2027/2028 — ODIT year 1"), Map.entry("receiptNo", "DEMO/RCT/2026/000045"),
                Map.entry("programme", "Ordinary Diploma in Information Technology"), Map.entry("deadline", "20 October 2026"),
                Map.entry("reason", "The CSEE results could not be confirmed"), Map.entry("verificationCode", "K7Q2-9XMA-31PD-7HCE"),
                Map.entry("registrationNo", "DEMO/NTA6/27/0001"), Map.entry("reportingDate", "27 September 2027"),
                Map.entry("venue", "Main Hall"), Map.entry("title", "Orientation week starts on Monday"),
                Map.entry("semester", "Semester 1 2027/2028"), Map.entry("modules", "6"), Map.entry("gpa", "3.4"),
                Map.entry("decision", "Pass"), Map.entry("module", "ITU 05102"), Map.entry("grade", "C"),
                Map.entry("percent", "62%"), Map.entry("threshold", "75%"),
                Map.entry("office", "Library"), Map.entry("award", "Ordinary Diploma (NTA Level 6)"), Map.entry("graduation", "27th Graduation 2029"),
                Map.entry("date", "23 November 2029"), Map.entry("place", "Kilimanjaro Hostel, A · Room 12, bed B"),
                Map.entry("window", "Hostel applications 2027/2028"), Map.entry("organisation", "Tanzania Ports Authority"),
                Map.entry("start", "6 July 2028"), Map.entry("week", "3"), Map.entry("change", "postponed until 1 March 2028"),
                Map.entry("outcome", "your grade is now B"), Map.entry("kind", "Waiver"), Map.entry("status", "Fixed"),
                Map.entry("sponsor", "Higher Education Students' Loans Board"),
                Map.entry("channels", "M-Pesa 555123, CRDB Bank 0150123456700"), Map.entry("instalment", "2"),
                Map.entry("when", "is due on 15 Jan 2028"), Map.entry("document", "Birth certificate"), Map.entry("link", "https://apply.college.ac.tz/s/AbC123"), Map.entry("student", "Asha Juma Mwinyi"),
                Map.entry("cardNo", "SN104526781"), Map.entry("validTo", "30 June 2028"));

        add("OTP_CODE", "Verification code", "An applicant registers, signs in for the first time or resets a password", "Accounts",
                List.of("code", "institution", "minutes"), Set.of("code"),
                "{code} is your {institution} verification code. It expires in {minutes} minutes. Do not share it.",
                "{code} ni namba yako ya uthibitisho ya {institution}. Itaisha baada ya dakika {minutes}. Usimpe mtu yeyote.", s);
        add("APPLICATION_RECEIVED", "Application received", "An applicant submits an application", "Admission",
                List.of("referenceNo", "institution"), Set.of("referenceNo"),
                "Your application {referenceNo} has been received. We will notify you of the outcome by SMS.",
                "Maombi yako {referenceNo} yamepokelewa. Tutakujulisha matokeo kwa SMS.", s);
        add("APPLICATION_FEE_REMINDER", "Application fee reminder", "An applicant has not paid the application fee two days after getting a payment number, or the window closes within three days", "Admission",
                List.of("window", "amount", "controlNo", "deadline", "institution"), Set.of(),
                "Your application to {institution} ({window}) is not complete: pay {amount} with payment number {controlNo} before {deadline}.",
                "Maombi yako {institution} ({window}) hayajakamilika: lipa {amount} kwa namba ya malipo {controlNo} kabla ya {deadline}.", s);
        add("ADMISSION_WINDOW_CLOSED", "Admission window closed early", "The Admissions Office closes a window before its closing date", "Admission",
                List.of("window", "reason", "institution"), Set.of("window"),
                "{institution}: applications for {window} have closed. Your application was not submitted. {reason}",
                "{institution}: maombi ya {window} yamefungwa. Maombi yako hayakuwasilishwa. {reason}", s);
        add("ADMISSION_WINDOW_REOPENED", "Admission window reopened", "The Admissions Office reopens a closed window", "Admission",
                List.of("window", "deadline", "institution"), Set.of("window", "deadline"),
                "{institution}: applications for {window} are open again until {deadline}. Complete and submit your application before then.",
                "{institution}: maombi ya {window} yamefunguliwa tena hadi {deadline}. Kamilisha na uwasilishe maombi yako kabla ya hapo.", s);
        add("QUALIFICATION_NOT_ACCEPTED", "Qualification not accepted", "The Admissions Office does not accept a qualification an applicant declared", "Admission",
                List.of("award", "reason", "institution"), Set.of("award"),
                "{institution}: your qualification {award} was not accepted: {reason}. Sign in to see the details.",
                "{institution}: sifa yako {award} haikukubaliwa: {reason}. Ingia kuona maelezo.", s);
        add("DOCUMENT_NOT_ACCEPTED", "Document not accepted", "The Admissions Office does not accept a document an applicant uploaded", "Admission",
                List.of("document", "reason", "institution"), Set.of("document"),
                "{institution}: your {document} was not accepted: {reason}. Sign in and upload it again.",
                "{institution}: {document} yako haikukubaliwa: {reason}. Ingia na uipakie tena.", s);
        add("EXTERNAL_SELECTION", "Selected by an external body", "Students selected by TAMISEMI or another body are imported into an admission window", "Admission",
                List.of("institution", "organisation", "programme", "referenceNo"), Set.of("programme"),
                "{institution}: you were selected by {organisation} to join {programme} (ref {referenceNo}). Sign up on the admission portal with your Form Four index number to follow your admission.",
                "{institution}: umechaguliwa na {organisation} kujiunga na {programme} (kumb. {referenceNo}). Jisajili kwenye mfumo wa udahili kwa namba yako ya mtihani wa kidato cha nne kufuatilia udahili wako.", s);
        add("IPT_WORKPLACE_ASSESSMENT", "IPT workplace assessment link", "The IPT office sends the workplace supervisor the link to assess a student", "Industrial training",
                List.of("student", "institution", "link", "deadline"), Set.of("link"),
                "{institution}: please assess {student}'s industrial training at your workplace by {deadline}: {link}",
                "{institution}: tafadhali tathmini mafunzo kwa vitendo ya {student} mahali pako pa kazi kabla ya {deadline}: {link}", s);
        add("TRACER_SURVEY", "Graduate tracer survey", "The institution invites graduates to its tracer survey", "Graduation",
                List.of("institution", "title", "link"), Set.of("link"),
                "{institution} would like to hear how you are doing since graduating. Please answer a short survey ({title}): {link}",
                "{institution} ingependa kujua unaendeleaje tangu uhitimu. Tafadhali jibu dodoso fupi ({title}): {link}", s);
        add("OFFER_MADE", "Admission offer", "NACTVET verifies an applicant and the offer is made", "Admission",
                List.of("programme", "institution", "deadline"), Set.of(),
                "Congratulations! NACTVET has verified your admission to {programme} at {institution}. Log in to accept your offer by {deadline}.",
                "Hongera! NACTVET imethibitisha udahili wako katika {programme}, {institution}. Ingia kwenye mfumo kukubali nafasi yako kabla ya {deadline}.", s);
        add("OFFER_MADE_MULTIPLE", "Admission offer (admitted elsewhere too)", "As above, for an applicant admitted by more than one institution", "Admission",
                List.of("programme", "institution", "deadline"), Set.of(),
                "Congratulations! NACTVET has verified your admission to {programme} at {institution}. Log in to accept your offer by {deadline}. "
                        + "You were admitted by more than one institution: use the confirmation code NACTVET sent you.",
                "Hongera! NACTVET imethibitisha udahili wako katika {programme}, {institution}. Ingia kwenye mfumo kukubali nafasi yako kabla ya {deadline}. "
                        + "Umedahiliwa na chuo zaidi ya kimoja: tumia namba ya uthibitisho uliyotumiwa na NACTVET.", s);
        add("NACTVET_NOT_VERIFIED", "Not verified by NACTVET", "NACTVET does not verify a selected applicant", "Admission",
                List.of("programme", "institution", "reason"), Set.of(),
                "We regret that NACTVET did not verify your admission to {programme} at {institution}: {reason}. Please contact the admissions office for advice.",
                "Samahani, NACTVET haikuthibitisha udahili wako katika {programme}, {institution}: {reason}. Tafadhali wasiliana na ofisi ya udahili kwa ushauri.", s);
        add("OFFER_ACCEPTED", "Offer accepted", "An applicant accepts the offer", "Admission",
                List.of("programme", "institution"), Set.of(),
                "You have accepted your admission to {programme} at {institution}. Your admission letter and joining instructions will be available in the portal.",
                "Umekubali udahili wako katika {programme}, {institution}. Barua ya udahili na maelekezo ya kujiunga vitapatikana kwenye mfumo.", s);
        add("OFFER_DECLINED", "Offer declined", "An applicant declines the offer", "Admission",
                List.of("programme", "institution"), Set.of(),
                "You have declined your admission offer to {programme}. Thank you for letting us know.",
                "Umekataa nafasi ya udahili katika {programme}. Asante kwa kutujulisha.", s);
        add("OFFER_LAPSED", "Offer lapsed", "An offer is not accepted by the deadline", "Admission",
                List.of("programme", "institution"), Set.of(),
                "Your admission offer to {programme} has lapsed because it was not accepted by the deadline. Contact the admissions office if you need help.",
                "Nafasi yako ya udahili katika {programme} imefutwa kwa sababu haikukubaliwa kabla ya muda wa mwisho. Wasiliana na ofisi ya udahili ukihitaji msaada.", s);
        add("LETTER_ISSUED", "Admission letter ready", "The admission letter is issued", "Admission",
                List.of("programme", "verificationCode", "institution"), Set.of(),
                "Your admission letter for {programme} is ready. Download it with the joining instructions from the admission portal. Verification code: {verificationCode}",
                "Barua yako ya udahili ya {programme} iko tayari. Ipakue pamoja na maelekezo ya kujiunga kwenye mfumo wa udahili. Namba ya uhakiki: {verificationCode}", s);
        add("LETTER_REISSUED", "Corrected admission letter", "A corrected admission letter is issued", "Admission",
                List.of("programme", "verificationCode", "institution"), Set.of(),
                "A corrected admission letter for {programme} is ready. Download it from the admission portal. Verification code: {verificationCode}",
                "Barua ya udahili iliyosahihishwa ya {programme} iko tayari. Ipakue kwenye mfumo wa udahili. Namba ya uhakiki: {verificationCode}", s);
        add("CONTROL_NUMBER", "Control number issued", "GePG issues a control number for an invoice", "Fees",
                List.of("controlNo", "amount", "description", "institution"), Set.of("controlNo"),
                "Control number {controlNo} for {amount} ({description}). Pay via bank or mobile money.",
                "Namba ya malipo {controlNo} kwa {amount} ({description}). Lipa kupitia benki au simu.", s);
        add("FEE_REMINDER", "Instalment reminder", "An instalment of the year's fees is due soon, due today or overdue", "Fees",
                List.of("amount", "instalment", "when", "controlNo", "description", "institution"), Set.of("amount"),
                "{institution}: instalment {instalment} of {description}, {amount}, {when}. Pay with number {controlNo}.",
                "{institution}: awamu ya {instalment} ya {description}, {amount}, {when}. Lipa kwa namba {controlNo}.", s);
        add("FEE_PENALTY", "Late payment penalty", "A late-payment penalty is added to an invoice after the grace period", "Fees",
                List.of("amount", "instalment", "description", "institution"), Set.of("amount"),
                "{institution}: a late payment penalty of {amount} was added for instalment {instalment} of {description}. Contact the Bursar's office if you have paid.",
                "{institution}: adhabu ya kuchelewa kulipa ya {amount} imeongezwa kwa awamu ya {instalment} ya {description}. Wasiliana na ofisi ya Mhasibu Mkuu kama umeshalipa.", s);
        add("PAYMENT_REFERENCE", "Payment reference issued", "CIMS issues a payment reference for an invoice (private and faith-based institutions)", "Fees",
                List.of("controlNo", "amount", "description", "channels", "institution"), Set.of("controlNo"),
                "Payment reference {controlNo} for {amount} ({description}). Pay {institution} via {channels} and quote this reference.",
                "Namba ya kumbukumbu ya malipo {controlNo} kwa {amount} ({description}). Lipa {institution} kupitia {channels} ukitaja namba hii.", s);
        add("PAYMENT_RECEIVED", "Payment received", "A payment is received", "Fees",
                List.of("amount", "receiptNo", "institution"), Set.of(),
                "Payment of {amount} received. Receipt {receiptNo}.",
                "Malipo ya {amount} yamepokelewa. Stakabadhi {receiptNo}.", s);
        add("STUDENT_WELCOME", "Welcome (with reporting date)", "Registry hands an accepted applicant over as a student", "Registry",
                List.of("institution", "registrationNo", "reportingDate", "venue"), Set.of("registrationNo"),
                "Welcome to {institution}! Your registration number is {registrationNo}. Report on {reportingDate} at {venue} with your original documents. "
                        + "Sign in to the student portal with your registration number and your password.",
                "Karibu {institution}! Namba yako ya usajili ni {registrationNo}. Ripoti tarehe {reportingDate} {venue} ukiwa na vyeti vyako halisi. "
                        + "Ingia kwenye tovuti ya mwanafunzi kwa namba yako ya usajili na nenosiri lako.", s);
        add("STUDENT_WELCOME_NO_DATE", "Welcome (no reporting date set)", "As above, when the window has no joining instructions", "Registry",
                List.of("institution", "registrationNo"), Set.of("registrationNo"),
                "Welcome to {institution}! Your registration number is {registrationNo}. Bring your original documents when you report. "
                        + "Sign in to the student portal with your registration number and your password.",
                "Karibu {institution}! Namba yako ya usajili ni {registrationNo}. Leta vyeti vyako halisi utakaporipoti. "
                        + "Ingia kwenye tovuti ya mwanafunzi kwa namba yako ya usajili na nenosiri lako.", s);
        add("REGISTRATION_COMPLETE", "Registration complete", "Registry approves first registration", "Registry",
                List.of("institution", "registrationNo"), Set.of(),
                "Your registration at {institution} is complete. Registration number: {registrationNo}. Welcome!",
                "Usajili wako {institution} umekamilika. Namba ya usajili: {registrationNo}. Karibu!", s);
        add("STUDENT_STATUS_CHANGED", "Student status changed", "An approved status change (postponement, suspension, resumption, withdrawal...) takes effect", "Registry",
                List.of("change", "date", "institution", "registrationNo"), Set.of("change"),
                "{institution}: your studies are {change} from {date} ({registrationNo}). Contact Registry if you have questions.",
                "{institution}: hali ya masomo yako imebadilika ({change}) kuanzia {date} ({registrationNo}). Wasiliana na Ofisi ya Usajili kwa maswali.", s);
        add("STUDENT_TRANSFERRED", "Transfer decided", "The Deputy Principal (Academic) approves or rejects a programme or campus transfer", "Registry",
                List.of("outcome", "registrationNo", "institution"), Set.of("outcome"),
                "{institution}: your transfer request {outcome}. Your registration number {registrationNo} stays the same.",
                "{institution}: ombi lako la kuhamia {outcome}. Namba yako ya usajili {registrationNo} haibadiliki.", s);
        add("SEMESTER_REGISTRATION_APPROVED", "Semester registration approved", "Registry approves a student's semester registration", "Registry",
                List.of("semester", "modules", "institution"), Set.of(),
                "Your registration for {semester} is approved: {modules} modules. See them on the student portal.",
                "Usajili wako wa {semester} umeidhinishwa: moduli {modules}. Ziangalie kwenye tovuti ya mwanafunzi.", s);
        add("SEMESTER_REGISTRATION_REJECTED", "Semester registration returned", "Registry returns a semester registration for correction", "Registry",
                List.of("semester", "reason", "institution"), Set.of("reason"),
                "Your registration for {semester} needs correcting: {reason}. Update it on the student portal.",
                "Usajili wako wa {semester} unahitaji marekebisho: {reason}. Urekebishe kwenye tovuti ya mwanafunzi.", s);
        add("NHIF_CARD_ISSUED", "NHIF card issued", "NHIF registers a student sent by the institution under the student package", "Registry",
                List.of("cardNo", "validTo", "institution"), Set.of("cardNo"),
                "{institution}: NHIF has registered you for health insurance. Your NHIF card number is {cardNo}, valid until {validTo}.",
                "{institution}: NHIF imekusajili kwa bima ya afya. Namba ya kadi yako ya NHIF ni {cardNo}, inatumika hadi {validTo}.", s);
        add("NHIF_REGISTRATION_REJECTED", "NHIF registration not accepted", "NHIF does not register a student sent by the institution", "Registry",
                List.of("reason", "institution"), Set.of(),
                "{institution}: NHIF did not register you for health insurance: {reason}. See Health insurance on the student portal or contact Registry.",
                "{institution}: NHIF haikukusajili kwa bima ya afya: {reason}. Angalia Bima ya afya kwenye tovuti ya mwanafunzi au wasiliana na Usajili.", s);
        add("HELPDESK_RECEIVED", "Help request received", "An applicant, student or staff member asks for help", "Helpdesk",
                List.of("ticketNo", "subject", "due", "institution"), Set.of("ticketNo"),
                "{institution}: we have received your request {ticketNo} ({subject}). We aim to reply by {due}. Follow it under Help on the portal.",
                "{institution}: tumepokea ombi lako {ticketNo} ({subject}). Tunalenga kukujibu kabla ya {due}. Lifuatilie kwenye Msaada katika tovuti.", s);
        add("HELPDESK_RECEIVED_GUEST", "Help request received (no account)", "Someone without an account asks for help on the public help page", "Helpdesk",
                List.of("ticketNo", "code", "due", "institution"), Set.of("ticketNo", "code"),
                "{institution}: we have received your request {ticketNo}. Follow it on the help page with code {code}. We aim to reply by {due}.",
                "{institution}: tumepokea ombi lako {ticketNo}. Lifuatilie kwenye ukurasa wa msaada kwa nambari ya siri {code}. Tunalenga kukujibu kabla ya {due}.", s);
        add("HELPDESK_REPLY", "Help request answered", "Staff reply to a help request", "Helpdesk",
                List.of("ticketNo", "subject", "institution"), Set.of("ticketNo"),
                "{institution}: there is a reply to your request {ticketNo} ({subject}). Read it under Help on the portal.",
                "{institution}: kuna jibu la ombi lako {ticketNo} ({subject}). Lisome kwenye Msaada katika tovuti.", s);
        add("HELPDESK_REPLY_GUEST", "Help request answered (no account)", "Staff reply to a request made on the public help page", "Helpdesk",
                List.of("ticketNo", "institution"), Set.of("ticketNo"),
                "{institution}: there is a reply to your request {ticketNo}. Open the help page and enter the request number with your code.",
                "{institution}: kuna jibu la ombi lako {ticketNo}. Fungua ukurasa wa msaada na uweke namba ya ombi pamoja na nambari yako ya siri.", s);
        add("HELPDESK_RESOLVED", "Help request resolved", "Staff mark a help request resolved", "Helpdesk",
                List.of("ticketNo", "subject", "institution"), Set.of("ticketNo"),
                "{institution}: your request {ticketNo} has been answered and marked resolved. If you still need help, reply within 7 days.",
                "{institution}: ombi lako {ticketNo} limejibiwa na kuwekwa kuwa limetatuliwa. Kama bado unahitaji msaada, jibu ndani ya siku 7.", s);
        add("RESULTS_PUBLISHED", "Results published", "The Examinations Officer publishes confirmed semester results", "Examinations",
                List.of("semester", "gpa", "decision", "institution"), Set.of(),
                "Your results for {semester} are out: GPA {gpa}, {decision}. See your grades on the student portal.",
                "Matokeo yako ya {semester} yametoka: GPA {gpa}, {decision}. Angalia alama zako kwenye tovuti ya mwanafunzi.", s);
        add("RESULT_AMENDED", "Result corrected", "An approved amendment changes a confirmed result", "Examinations",
                List.of("module", "semester", "grade", "institution"), Set.of(),
                "Your result in {module} for {semester} has been corrected: grade {grade}. See the student portal.",
                "Matokeo yako ya {module} ya {semester} yamesahihishwa: daraja {grade}. Angalia tovuti ya mwanafunzi.", s);
        add("APPEAL_OUTCOME", "Remark or appeal decided", "A student's remark or appeal of a module result is decided", "Examinations",
                List.of("module", "outcome", "institution"), Set.of("module", "outcome"),
                "{institution}: your remark/appeal in {module} has been decided: {outcome}. See the student portal.",
                "{institution}: ombi lako la kusahihishwa upya/rufaa katika {module} limeamuliwa: {outcome}. Angalia tovuti ya mwanafunzi.", s);
        add("COMPETENCE_NYC", "Not yet competent", "A verified NVA unit shows a trainee not yet competent in one or more tasks", "Examinations",
                List.of("module", "institution"), Set.of("module"),
                "{institution}: you are not yet competent in {module}. You will be re-assessed; see the student portal for the tasks.",
                "{institution}: bado hujafikia umahiri katika {module}. Utapimwa tena; angalia tovuti ya mwanafunzi kuona kazi husika.", s);
        add("FIN_ADJUSTMENT_DECIDED", "Waiver or refund decided", "A waiver or refund requested for a student is approved or rejected", "Finance",
                List.of("kind", "amount", "decision", "institution"), Set.of("kind", "amount", "decision"),
                "{institution}: your {kind} of {amount} has been {decision}. See the student portal for your statement.",
                "{institution}: ombi lako la {kind} la {amount}: {decision}. Angalia taarifa ya akaunti kwenye tovuti ya mwanafunzi.", s);
        add("SPONSOR_ASSIGNED", "Sponsorship recorded", "The bursary records that a sponsor pays part of a student's fees", "Finance",
                List.of("sponsor", "institution"), Set.of("sponsor"),
                "{institution}: your fees are now sponsored by {sponsor}. Your statement shows the sponsor's share.",
                "{institution}: ada yako sasa inadhaminiwa na {sponsor}. Taarifa ya akaunti yako inaonyesha sehemu ya mdhamini.", s);
        add("HOSTEL_REPAIR_UPDATED", "Hostel repair updated", "The warden updates a repair a student reported", "Hostels",
                List.of("place", "status", "institution"), Set.of("status"),
                "{institution}: your repair request for {place} is now: {status}.",
                "{institution}: ombi lako la matengenezo kwa {place} sasa ni: {status}.", s);
        add("ATTENDANCE_LOW", "Low attendance warning", "A student's attendance in a module falls below the examination threshold", "Teaching",
                List.of("module", "percent", "threshold", "institution"), Set.of(),
                "Your attendance in {module} is {percent}. You need at least {threshold} to sit the examination. Please attend all classes.",
                "Mahudhurio yako katika {module} ni {percent}. Unahitaji angalau {threshold} ili kufanya mtihani. Tafadhali hudhuria vipindi vyote.", s);
        add("TIMETABLE_PUBLISHED", "Class timetable published", "Academic management publishes the class timetable of a semester", "Teaching",
                List.of("semester", "institution"), Set.of(),
                "{institution}: the class timetable for {semester} is out. See your classes and venues under Timetable on the student portal.",
                "{institution}: ratiba ya vipindi ya {semester} imetoka. Angalia vipindi na kumbi zako kwenye Ratiba katika tovuti ya mwanafunzi.", s);
        add("EXAM_TIMETABLE_PUBLISHED", "Examination timetable published", "The Examinations Office publishes the examination timetable of a semester", "Examinations",
                List.of("semester", "institution"), Set.of(),
                "{institution}: the examination timetable for {semester} is out. See the dates and venues of your papers under Timetable on the student portal.",
                "{institution}: ratiba ya mitihani ya {semester} imetoka. Angalia tarehe na kumbi za mitihani yako kwenye Ratiba katika tovuti ya mwanafunzi.", s);
        add("EXAM_NOT_ELIGIBLE", "Not eligible for an examination", "The eligibility list shows a student may not sit a module's examination", "Examinations",
                List.of("module", "semester", "reason", "institution"), Set.of(),
                "You are not eligible to sit the {module} examination for {semester}: {reason}. Contact the Examinations Office.",
                "Hujakidhi vigezo vya kufanya mtihani wa {module} wa {semester}: {reason}. Wasiliana na Ofisi ya Mitihani.", s);
        add("EXAM_EXEMPTION_APPROVED", "Examination exemption approved", "An exemption from the eligibility rules is approved", "Examinations",
                List.of("module", "semester", "institution"), Set.of(),
                "You have been allowed to sit the {module} examination for {semester}.",
                "Umeruhusiwa kufanya mtihani wa {module} wa {semester}.", s);
        add("CLEARANCE_HELD", "Clearance held", "A clearance office holds a finalist's clearance", "Graduation",
                List.of("office", "reason", "institution"), Set.of("reason"),
                "Your clearance is held by {office}: {reason}. Sort it out with the office, then check the student portal.",
                "Kibali chako kimezuiliwa na {office}: {reason}. Shughulikia na ofisi hiyo, kisha angalia tovuti ya mwanafunzi.", s);
        add("CLEARANCE_COMPLETE", "Clearance complete", "Every office has cleared a finalist", "Graduation",
                List.of("institution"), Set.of(),
                "Congratulations! Every office has cleared you. You will be listed for graduation.",
                "Hongera! Ofisi zote zimekupa kibali. Utaorodheshwa kwa ajili ya mahafali.", s);
        add("GRADUATION_APPROVED", "Graduation approved", "The Governing Board approves the graduand list", "Graduation",
                List.of("award", "graduation", "date", "institution"), Set.of(),
                "Congratulations! The Governing Board has approved your {award}. You graduate at the {graduation} on {date}.",
                "Hongera! Bodi ya Uongozi imeidhinisha tuzo yako ya {award}. Utahitimu katika {graduation} tarehe {date}.", s);
        add("HOSTEL_ALLOCATED", "Hostel bed allocated", "A hostel bed is allocated to a student", "Hostel",
                List.of("place", "amount", "deadline", "controlNo", "institution"), Set.of(),
                "You have been allocated {place}. Pay {amount} with payment number {controlNo} by {deadline}, or the bed will be released.",
                "Umepangiwa {place}. Lipa {amount} kwa namba ya malipo {controlNo} kabla ya {deadline}, la sivyo nafasi itatolewa kwa mwingine.", s);
        add("HOSTEL_BED_CHANGED", "Hostel bed changed", "The hostel office moves a student to another bed", "Hostel",
                List.of("place", "institution"), Set.of(),
                "Your hostel bed has been changed to {place}. Any change in the hostel fee is shown under Fees in the student portal.",
                "Nafasi yako ya hosteli imebadilishwa kwenda {place}. Mabadiliko yoyote ya ada ya hosteli yanaonekana kwenye Ada katika tovuti ya mwanafunzi.", s);
        add("HOSTEL_WAITLISTED", "Hostel waiting list", "No suitable bed is free for a hostel applicant", "Hostel",
                List.of("window", "institution"), Set.of(),
                "No hostel bed is free for you yet ({window}). You are on the waiting list and will get an SMS if one becomes free.",
                "Bado hakuna nafasi ya hosteli kwako ({window}). Uko kwenye orodha ya kusubiri na utapata SMS nafasi ikipatikana.", s);
        add("HOSTEL_RELEASED", "Hostel bed released", "A hostel allocation is released or cancelled", "Hostel",
                List.of("reason", "institution"), Set.of(),
                "Your hostel bed has been released: {reason}. Contact the hostel office if you still need accommodation.",
                "Nafasi yako ya hosteli imetolewa: {reason}. Wasiliana na ofisi ya hosteli kama bado unahitaji malazi.", s);
        add("IPT_PLACEMENT_CONFIRMED", "IPT placement confirmed", "The IPT Coordinator confirms a student's field placement", "IPT",
                List.of("organisation", "start", "institution"), Set.of(),
                "Your industrial practical training at {organisation} is confirmed from {start}. Download your placement letter on the student portal.",
                "Mafunzo yako kwa vitendo katika {organisation} yamethibitishwa kuanzia {start}. Pakua barua yako ya utambulisho kwenye tovuti ya mwanafunzi.", s);
        add("IPT_PLACEMENT_DECLINED", "IPT placement request declined", "The IPT Coordinator declines a student's placement request", "IPT",
                List.of("organisation", "reason", "institution"), Set.of("reason"),
                "Your IPT placement request for {organisation} was not approved: {reason}. Please see the IPT Coordinator.",
                "Ombi lako la mafunzo kwa vitendo katika {organisation} halikukubaliwa: {reason}. Tafadhali onana na Mratibu wa Mafunzo kwa Vitendo.", s);
        add("IPT_LOGBOOK_RETURNED", "IPT logbook week returned", "A supervisor returns a logbook week for correction", "IPT",
                List.of("week", "reason", "institution"), Set.of("reason"),
                "Your IPT logbook for week {week} was returned: {reason}. Correct it on the student portal and submit it again.",
                "Daftari lako la mafunzo kwa vitendo la wiki {week} limerudishwa: {reason}. Rekebisha kwenye tovuti ya mwanafunzi na uwasilishe tena.", s);
        add("RESULTS_WITHHELD", "Results withheld", "Results are published but withheld until fees are cleared", "Examinations",
                List.of("semester", "reason", "institution"), Set.of(),
                "Your results for {semester} are ready but withheld: {reason}. They will show on the student portal once fees are cleared.",
                "Matokeo yako ya {semester} yapo tayari lakini yamezuiliwa: {reason}. Yataonekana kwenye tovuti ya mwanafunzi ada ikilipwa.", s);
        add("DETAILS_CHANGE_APPROVED", "Details update approved", "Registry approves a student's request to update details", "Registry",
                List.of("institution"), Set.of(),
                "Your request to update your details has been approved by Registry.",
                "Ombi lako la kubadilisha taarifa zako limekubaliwa na Ofisi ya Usajili.", s);
        add("DETAILS_CHANGE_REJECTED", "Details update not approved", "Registry rejects a student's request to update details", "Registry",
                List.of("reason", "institution"), Set.of("reason"),
                "Your request to update your details was not approved: {reason}",
                "Ombi lako la kubadilisha taarifa zako halikukubaliwa: {reason}", s);
        add("NOTICE_ALERT", "New notice", "A notice is published with an SMS alert", "Student portal",
                List.of("institution", "title"), Set.of("title"),
                "{institution} notice: {title}. Read it on the student portal.",
                "Tangazo la {institution}: {title}. Lisome kwenye tovuti ya mwanafunzi.", s);
    }

    private MessageTemplates() {
    }

    public static Definition require(String code) {
        Definition d = ALL.get(code);
        if (d == null) throw new IllegalArgumentException("Unknown message template " + code);
        return d;
    }
}
