package tz.co.msingi.cims.admission.advert;

import org.junit.jupiter.api.Test;
import tz.co.msingi.cims.admission.advert.ProgrammeAdvertService.Advert;
import tz.co.msingi.cims.admission.advert.ProgrammeAdvertService.Fee;
import tz.co.msingi.cims.admission.eligibility.EntryRules;
import tz.co.msingi.cims.admission.eligibility.EntryRules.Condition;
import tz.co.msingi.cims.admission.eligibility.EntryRules.ConditionType;
import tz.co.msingi.cims.admission.eligibility.EntryRules.Pathway;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Posters render at 1080 pixels wide in both formats and languages; the requirements read as the rules say. */
class PosterRendererTest {

    static final EntryRules RULES = new EntryRules("Four passes including Chemistry and Biology", List.of(
            new Pathway("CSEE", List.of(new Condition(ConditionType.CSEE_MIN_PASSES, 4, "D", null, null, null),
                    new Condition(ConditionType.CSEE_SUBJECT, 2, "D", List.of("CHEMISTRY", "BIOLOGY"), null, null))),
            new Pathway("NVA", List.of(new Condition(ConditionType.PRIOR_AWARD, null, null, null, "NVA", 3)))));

    static Advert advert() {
        return new Advert(1L, "2027/2028 September Intake", "2027/2028", "September intake", OffsetDateTime.now().minusDays(1),
                OffsetDateTime.now().plusDays(40), true, new BigDecimal("10000"), 7L, "PHARM", "Diploma in Pharmaceutical Sciences",
                "Ordinary Diploma", "NTA", (short) 6, 6, "Pharmacy", 60, RULES.summary(), EntryRulesText.sentence(RULES, false),
                EntryRulesText.sentence(RULES, true), EntryRulesText.routes(RULES, false), EntryRulesText.routes(RULES, true),
                List.of("Hostel bure", "Practicals at Nkinga Hospital", "Scholarships"), new Fee(new BigDecimal("1500000"), 4, new BigDecimal("375000")), "/p/PHARM");
    }

    static PosterRenderer.Institution inst() {
        return new PosterRenderer.Institution("Nkinga Institute of Health Sciences", "NIHS", "#0F5C8C", "#F2B705", null, null,
                List.of("0766 699 444", "0755 715 415", "0767 897 481"), "healthinstitute.nkinga@gmail.com", null,
                List.of(new String[]{"WhatsApp", "0766 699 444"}, new String[]{"Facebook", "@chuoafyankinga"}, new String[]{"Instagram", "@chuoafyankinga"}));
    }

    @Test
    void requirementsInWords() {
        assertThat(EntryRulesText.sentence(RULES, false))
                .isEqualTo("Four CSEE (Form Four) passes at D or above, including Chemistry and Biology (D or above); or holder of NVA Level III or above.");
        assertThat(EntryRulesText.sentence(RULES, true))
                .isEqualTo("Ufaulu wa masomo manne kidato cha nne kwa daraja D au zaidi, yakiwemo Chemistry na Biology (daraja D au zaidi); au mwenye NVA ngazi ya III au zaidi.");
    }

    @Test
    void postersAre1080Wide() throws Exception {
        for (PosterRenderer.Format f : PosterRenderer.Format.values()) {
            for (boolean sw : new boolean[]{false, true}) {
                byte[] png = PosterRenderer.png(advert(), inst(), "https://nkinga.ac.tz/p/PHARM", f, sw);
                var img = ImageIO.read(new ByteArrayInputStream(png));
                assertThat(img.getWidth()).isEqualTo(1080);
                assertThat(img.getHeight()).isEqualTo(f == PosterRenderer.Format.SQUARE ? 1080 : 1350);
                if (System.getenv("POSTER_OUT") != null) java.nio.file.Files.write(java.nio.file.Path.of(System.getenv("POSTER_OUT"), "poster-" + f + "-" + (sw ? "sw" : "en") + ".png"), png);
            }
        }
    }
}
