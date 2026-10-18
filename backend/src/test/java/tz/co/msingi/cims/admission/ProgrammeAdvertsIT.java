package tz.co.msingi.cims.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tz.co.msingi.cims.platform.security.Role;
import tz.co.msingi.cims.support.AdmissionScenario;
import tz.co.msingi.cims.support.IntegrationTest;
import tz.co.msingi.cims.support.TestData;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Programme pages and adverts: the public list and page of each programme on offer (fee and instalments from the approved fee
 * structure, requirements in English and Kiswahili, selling points), the short link with its preview card, the posters, the
 * institution's contacts and social media, and its logo.
 */
@IntegrationTest
class ProgrammeAdvertsIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    TestData.Institution inst;
    AdmissionScenario s;
    String admin, officer, accountant, bursar, viewer;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "A" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        inst = data.institution(tenant, new BigDecimal("10000"));
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "accountant", Role.ACCOUNTANT);
        data.staff(inst.tenant(), "bursar", Role.BURSAR);
        data.staff(inst.tenant(), "viewer", Role.MANAGEMENT_VIEWER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        officer = s.staffToken("officer");
        accountant = s.staffToken("accountant");
        bursar = s.staffToken("bursar");
        viewer = s.staffToken("viewer");
    }

    @Test
    void publicProgrammePageWithFeesRequirementsAndSellingPoints() throws Exception {
        JsonNode list = s.body(s.call(get("/api/public/programmes"), null, null).andExpect(status().isOk()));
        assertThat(list.size()).isEqualTo(1);
        JsonNode a = list.get(0);
        assertThat(a.get("code").asText()).isEqualTo("BTCIT");
        assertThat(a.get("open").asBoolean()).isTrue();
        assertThat(a.get("path").asText()).isEqualTo("/p/BTCIT");
        assertThat(a.path("fee").isMissingNode() || a.get("fee").isNull()).isTrue();   // no approved fee structure yet
        assertThat(a.get("requirementsEn").asText()).isEqualTo("Four CSEE (Form Four) passes at D or above, including Basic Mathematics (D or above).");
        assertThat(a.get("requirementsSw").asText()).isEqualTo("Ufaulu wa masomo manne kidato cha nne kwa daraja D au zaidi, yakiwemo Basic Mathematics (daraja D au zaidi).");

        // the year's fee and its instalments come from the approved fee structure
        approvedStructure();
        JsonNode fee = s.body(s.call(get("/api/public/programmes/btcit"), null, null).andExpect(status().isOk())).get("fee");
        assertThat(fee.get("total").decimalValue()).isEqualByComparingTo("1220000");
        assertThat(fee.get("instalments").asInt()).isEqualTo(2);
        assertThat(fee.get("firstInstalment").decimalValue()).isEqualByComparingTo("620000");

        // the admissions office writes the selling points (at most six short lines); viewers cannot
        String path = "/api/admission/windows/" + inst.windowId() + "/programmes/" + inst.windowProgrammeId() + "/highlights";
        s.call(put(path), officer, "{\"highlights\":\"Practical training at the hospital\\n\\n  Hostel on campus  \\n\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.highlights").value("Practical training at the hospital\nHostel on campus"));
        s.call(put(path), officer, "{\"highlights\":\"" + "x\\n".repeat(7) + "\"}").andExpect(status().is4xxClientError());
        s.call(put(path), officer, "{\"highlights\":\"" + "y".repeat(81) + "\"}").andExpect(status().is4xxClientError());
        s.call(put(path), viewer, "{\"highlights\":\"Mine\"}").andExpect(status().isForbidden());
        JsonNode hl = s.body(s.call(get("/api/public/programmes/BTCIT"), null, null)).get("highlights");
        assertThat(hl.size()).isEqualTo(2);
        assertThat(hl.get(1).asText()).isEqualTo("Hostel on campus");

        // a programme not on offer
        s.call(get("/api/public/programmes/NOPE"), null, null).andExpect(status().isNotFound());
    }

    @Test
    void shortLinkShowsPreviewCardAndOpensTheProgrammePage() throws Exception {
        String html = s.text(s.call(get("/p/BTCIT").param("lang", "sw").param("src", "whatsapp").header("Host", "chuo.example.ac.tz")
                .header("X-Forwarded-Proto", "https"), null, null).andExpect(status().isOk()));
        assertThat(html).contains("<meta property=\"og:title\" content=\"Basic Technician Certificate in IT")
                .contains("og:image\" content=\"https://chuo.example.ac.tz/api/public/programmes/BTCIT/poster.png?format=square&#38;lang=sw\"")
                .contains("og:url\" content=\"https://chuo.example.ac.tz/p/BTCIT?lang=sw\"")
                .contains("Sifa: Ufaulu wa masomo manne")
                .contains("Mwisho wa maombi")
                .contains("location.replace(\"https://chuo.example.ac.tz/programmes/BTCIT?lang=sw&src=whatsapp\")");
        // no sign-in needed; a programme not on offer goes to the home page
        assertThat(s.text(s.call(get("/p/NOPE"), null, null).andExpect(status().isOk()))).contains("location.replace(").doesNotContain("og:image");
    }

    @Test
    void postersSquarePortraitPngAndPdf() throws Exception {
        byte[] png = s.call(get("/api/public/programmes/BTCIT/poster.png"), null, null).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png")).andReturn().getResponse().getContentAsByteArray();
        BufferedImage sq = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(sq.getWidth()).isEqualTo(1080);
        assertThat(Math.abs(sq.getHeight() - 1080)).isLessThanOrEqualTo(2);
        BufferedImage pt = ImageIO.read(new ByteArrayInputStream(s.call(get("/api/public/programmes/BTCIT/poster.png?format=portrait&lang=sw&download=true"), null, null)
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("advert-BTCIT-portrait-sw.png")))
                .andReturn().getResponse().getContentAsByteArray()));
        assertThat(Math.abs(pt.getHeight() - 1350)).isLessThanOrEqualTo(2);
        byte[] pdf = s.call(get("/api/public/programmes/BTCIT/poster.pdf"), null, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    void contactsSocialMediaAndLogo() throws Exception {
        s.call(put("/api/admin/institution/profile"), admin, "{\"contactEmail\":\"info@chuo.ac.tz\",\"contactPhone\":\"0754 000 111\","
                + "\"admissionsEmail\":\"Udahili@Chuo.ac.tz\",\"phones\":[\"0754 000 111\",\"0713-222-333\",\"\"],\"website\":\"chuo.ac.tz\","
                + "\"whatsapp\":\"+255 754 000 111\",\"facebook\":\"chuoafyankinga\",\"instagram\":\"@chuo_afya\",\"youtube\":null,\"tiktok\":\"\",\"x\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admissionsEmail").value("udahili@chuo.ac.tz"))
                .andExpect(jsonPath("$.phones.length()").value(1))       // the main phone is not repeated
                .andExpect(jsonPath("$.phones[0]").value("0713 222 333"))
                .andExpect(jsonPath("$.website").value("https://chuo.ac.tz"))
                .andExpect(jsonPath("$.facebook").value("https://facebook.com/chuoafyankinga"))
                .andExpect(jsonPath("$.instagram").value("https://instagram.com/chuo_afya"));
        s.call(put("/api/admin/institution/profile"), admin, "{\"contactEmail\":\"not-an-email\",\"phones\":[]}").andExpect(jsonPath("$.code").value("INVALID_EMAIL"));
        s.call(put("/api/admin/institution/profile"), admin, "{\"phones\":[\"12ab\"]}").andExpect(jsonPath("$.code").value("INVALID_PHONE"));
        s.call(put("/api/admin/institution/profile"), viewer, "{\"phones\":[]}").andExpect(status().isForbidden());
        assertThat(s.body(s.call(get("/api/admin/institution/profile"), admin, null)).get("whatsapp").asText()).isEqualTo("+255 754 000 111");

        // the logo: PNG or JPEG, at least 200 pixels wide; shown to the public and on the posters
        s.call(logo(png(150, 150), "small.png"), admin, null).andExpect(status().is4xxClientError());
        byte[] logo = png(300, 300);
        s.call(logo(logo, "logo.png"), admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("LOGO"));
        String url = s.body(s.call(get("/api/public/institution"), null, null)).at("/logo/url").asText();
        assertThat(url).startsWith("/api/public/institution/photos/logo");
        // the logo is not the campus photo; the poster renders with it
        assertThat(s.body(s.call(get("/api/public/institution"), null, null)).path("campusPhoto").isMissingNode()
                || s.body(s.call(get("/api/public/institution"), null, null)).get("campusPhoto").isNull()).isTrue();
        s.call(get("/api/public/programmes/BTCIT/poster.png"), null, null).andExpect(status().isOk());
    }

    static byte[] png(int w, int h) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    MockMultipartHttpServletRequestBuilder logo(byte[] bytes, String name) {
        MockMultipartHttpServletRequestBuilder b = multipart("/api/admin/institution/photos/logo");
        b.file(new MockMultipartFile("file", name, "application/octet-stream", bytes));
        b.with(r -> { r.setMethod("PUT"); return r; });
        return b;
    }

    private void approvedStructure() throws Exception {
        long yearId = s.body(s.call(get("/api/admission/windows/" + inst.windowId()), officer, null)).get("academicYearId").asLong();
        long reg = s.body(s.call(post("/api/finance/fee-items"), accountant,
                "{\"code\":\"REG\",\"name\":\"Registration fee\",\"category\":\"REGISTRATION\",\"allocationPriority\":10,\"active\":true}")).get("id").asLong();
        long tuition = s.body(s.call(post("/api/finance/fee-items"), accountant,
                "{\"code\":\"TUITION\",\"name\":\"Tuition fee\",\"category\":\"TUITION\",\"allocationPriority\":50,\"active\":true}")).get("id").asLong();
        long id = s.body(s.call(post("/api/finance/fee-structures"), accountant, "{\"academicYearId\":" + yearId + ",\"programmeId\":" + inst.programmeId()
                + ",\"yearOfStudy\":1,\"lines\":[{\"feeItemId\":" + reg + ",\"amount\":20000,\"instalment\":1},{\"feeItemId\":" + tuition
                + ",\"amount\":600000,\"instalment\":1},{\"feeItemId\":" + tuition + ",\"amount\":600000,\"instalment\":2}]}")).get("id").asLong();
        s.call(post("/api/finance/fee-structures/" + id + "/approve"), bursar, null).andExpect(status().isOk());
    }
}
