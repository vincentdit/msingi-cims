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
                Map.entry("code", "482913"), Map.entry("institution", "Msingi Demo Technical College"), Map.entry("minutes", "10"),
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
                Map.entry("start", "6 July 2028"), Map.entry("week", "3"));

        add("OTP_CODE", "Verification code", "An applicant registers, signs in for the first time or resets a password", "Accounts",
                List.of("code", "institution", "minutes"), Set.of("code"),
                "{code} is your {institution} verification code. It expires in {minutes} minutes. Do not share it.",
                "{code} ni namba yako ya uthibitisho ya {institution}. Itaisha baada ya dakika {minutes}. Usimpe mtu yeyote.", s);
        add("APPLICATION_RECEIVED", "Application received", "An applicant submits an application", "Admission",
                List.of("referenceNo", "institution"), Set.of("referenceNo"),
                "Your application {referenceNo} has been received. We will notify you of the outcome by SMS.",
                "Maombi yako {referenceNo} yamepokelewa. Tutakujulisha matokeo kwa SMS.", s);
        add("ADMISSION_WINDOW_CLOSED", "Admission window closed early", "The Admissions Office closes a window before its closing date", "Admission",
                List.of("window", "reason", "institution"), Set.of("window"),
                "{institution}: applications for {window} have closed. Your application was not submitted. {reason}",
                "{institution}: maombi ya {window} yamefungwa. Maombi yako hayakuwasilishwa. {reason}", s);
        add("ADMISSION_WINDOW_REOPENED", "Admission window reopened", "The Admissions Office reopens a closed window", "Admission",
                List.of("window", "deadline", "institution"), Set.of("window", "deadline"),
                "{institution}: applications for {window} are open again until {deadline}. Complete and submit your application before then.",
                "{institution}: maombi ya {window} yamefunguliwa tena hadi {deadline}. Kamilisha na uwasilishe maombi yako kabla ya hapo.", s);
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
        add("PAYMENT_RECEIVED", "Payment received", "A GePG payment is received", "Fees",
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
        add("SEMESTER_REGISTRATION_APPROVED", "Semester registration approved", "Registry approves a student's semester registration", "Registry",
                List.of("semester", "modules", "institution"), Set.of(),
                "Your registration for {semester} is approved: {modules} modules. See them on the student portal.",
                "Usajili wako wa {semester} umeidhinishwa: moduli {modules}. Ziangalie kwenye tovuti ya mwanafunzi.", s);
        add("SEMESTER_REGISTRATION_REJECTED", "Semester registration returned", "Registry returns a semester registration for correction", "Registry",
                List.of("semester", "reason", "institution"), Set.of("reason"),
                "Your registration for {semester} needs correcting: {reason}. Update it on the student portal.",
                "Usajili wako wa {semester} unahitaji marekebisho: {reason}. Urekebishe kwenye tovuti ya mwanafunzi.", s);
        add("RESULTS_PUBLISHED", "Results published", "The Examinations Officer publishes confirmed semester results", "Examinations",
                List.of("semester", "gpa", "decision", "institution"), Set.of(),
                "Your results for {semester} are out: GPA {gpa}, {decision}. See your grades on the student portal.",
                "Matokeo yako ya {semester} yametoka: GPA {gpa}, {decision}. Angalia alama zako kwenye tovuti ya mwanafunzi.", s);
        add("RESULT_AMENDED", "Result corrected", "An approved amendment changes a confirmed result", "Examinations",
                List.of("module", "semester", "grade", "institution"), Set.of(),
                "Your result in {module} for {semester} has been corrected: grade {grade}. See the student portal.",
                "Matokeo yako ya {module} ya {semester} yamesahihishwa: daraja {grade}. Angalia tovuti ya mwanafunzi.", s);
        add("ATTENDANCE_LOW", "Low attendance warning", "A student's attendance in a module falls below the examination threshold", "Teaching",
                List.of("module", "percent", "threshold", "institution"), Set.of(),
                "Your attendance in {module} is {percent}. You need at least {threshold} to sit the examination. Please attend all classes.",
                "Mahudhurio yako katika {module} ni {percent}. Unahitaji angalau {threshold} ili kufanya mtihani. Tafadhali hudhuria vipindi vyote.", s);
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
                "You have been allocated {place}. Pay {amount} with control number {controlNo} by {deadline}, or the bed will be released.",
                "Umepangiwa {place}. Lipa {amount} kwa namba ya malipo {controlNo} kabla ya {deadline}, la sivyo nafasi itatolewa kwa mwingine.", s);
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
