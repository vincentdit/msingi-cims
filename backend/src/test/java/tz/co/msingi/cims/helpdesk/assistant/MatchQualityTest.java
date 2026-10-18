package tz.co.msingi.cims.helpdesk.assistant;

import org.junit.jupiter.api.Test;
import tz.co.msingi.cims.helpdesk.assistant.MatchIndex.Doc;
import tz.co.msingi.cims.helpdesk.assistant.MatchIndex.Hit;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The assistant's matching on real-looking questions, in English and Kiswahili, with typing mistakes. */
class MatchQualityTest {

    static final String[][] ANSWERS = {
            {"index", "The portal says my index number is not found", "Check that you typed the index number exactly as on your NECTA results slip, for example S0123/0045, and chose the year you sat the examination. Private candidates' numbers start with P. If it is still not found, ask us for help and attach a photo of your results slip."},
            {"sms", "I did not receive the SMS code", "Wait two minutes, check that your phone has network and that the number you entered is yours, then use Send a new code. If you entered a wrong number, start the sign-up again with the right one. Still no code? Ask us for help with your mobile number."},
            {"password", "I forgot my password", "On the sign-in page choose Forgot password, enter your mobile number and the code we send, then choose a new password. Students sign in with their registration number or mobile number."},
            {"paid", "I paid but the payment does not show", "Payments usually show within minutes. Make sure you paid with the control number or payment reference shown in the portal, not another one. If it has not shown after one working day, ask us for help and attach the payment receipt or SMS from your bank or mobile money."},
            {"hostel", "How do I apply for a hostel room?", "Hostel applications open before each academic year under Hostel in the student portal. Choose your preferred hostel and apply; allocations are sent by SMS."},
            {"transcript", "How do I get my transcript?", "Request a statement of results from the Examinations Office under Results in the student portal; it is ready within three working days."},
    };

    static MatchIndex<String> index() {
        return new MatchIndex<>(List.of(ANSWERS).stream().map(a -> new Doc<>(a[0], MatchIndex.weighted(a[1], 2.5, a[2], 1))).toList());
    }

    static Hit<String> top(String question) {
        List<Hit<String>> h = index().search(TextAnalyzer.terms(question), 3);
        return h.isEmpty() ? null : h.get(0);
    }

    @Test
    void findsTheAnswerInEnglishAndKiswahiliAndWithMistakes() {
        Map<String, String> cases = Map.ofEntries(
                Map.entry("I paid the application fee yesterday but it is still showing unpaid", "paid"),
                Map.entry("Nimelipa ada ya maombi lakini malipo hayaonekani kwenye tovuti", "paid"),
                Map.entry("Sijapokea ujumbe wa nambari ya siri", "sms"),
                Map.entry("The verification code SMS never arrived on my phone", "sms"),
                Map.entry("I forgot my pasword and cannot login", "password"),
                Map.entry("Nimesahau nenosiri langu", "password"),
                Map.entry("Namba yangu ya mtihani haipatikani", "index"),
                Map.entry("My CSEE index no is not found when I register", "index"),
                Map.entry("Nataka kuomba hosteli, chumba kinapatikanaje?", "hostel"),
                Map.entry("how can i get my transcrpt", "transcript"));
        cases.forEach((q, expected) -> {
            Hit<String> h = top(q);
            assertThat(h).as(q).isNotNull();
            assertThat(h.item()).as(q).isEqualTo(expected);
            assertThat(h.confidence()).as(q + " confidence").isGreaterThanOrEqualTo(HelpdeskAssistant.SUGGEST);
        });
    }

    @Test
    void clearQuestionsAreConfidentAndUnrelatedOnesAreNot() {
        assertThat(top("Nimesahau nenosiri langu, nifanyeje?").confidence()).isGreaterThanOrEqualTo(HelpdeskAssistant.INSTANT);
        assertThat(top("I did not receive the SMS code").confidence()).isGreaterThanOrEqualTo(HelpdeskAssistant.INSTANT);
        Hit<String> unrelated = top("The lecturer for ITU 05102 did not come to class on Monday");
        assertThat(unrelated == null || unrelated.confidence() < HelpdeskAssistant.SUGGEST).as("unrelated: " + (unrelated == null ? "" : unrelated.confidence())).isTrue();
    }

    @Test
    void readsKiswahiliVerbsAndEnglishEndings() {
        assertThat(TextAnalyzer.terms("Nimelipa")).containsKey("~pay");
        assertThat(TextAnalyzer.terms("sikupokea")).containsKey("~arrive");
        assertThat(TextAnalyzer.terms("payments not showing")).containsKeys("~pay", "~show");
        assertThat(TextAnalyzer.terms("control number")).containsKey("~controlno").doesNotContainKey("~phone");
        assertThat(TextAnalyzer.stem("arriving")).isEqualTo("arriv");
    }
}
