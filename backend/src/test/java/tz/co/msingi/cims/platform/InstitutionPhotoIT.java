package tz.co.msingi.cims.platform;

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
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The administrator uploads the institution's campus photo, which the admission portal shows to everyone. */
@IntegrationTest
class InstitutionPhotoIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    AdmissionScenario s, other;
    String admin, admissions;

    @BeforeEach
    void setUp() throws Exception {
        String tenant = "F" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestData.Institution inst = data.institution(tenant, BigDecimal.ZERO);
        data.staff(inst.tenant(), "admin", Role.INSTITUTION_ADMIN);
        data.staff(inst.tenant(), "admissions", Role.ADMISSIONS_OFFICER);
        s = new AdmissionScenario(mvc, json, tenant, inst);
        admin = s.staffToken("admin");
        admissions = s.staffToken("admissions");
        String tenant2 = "G" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        other = new AdmissionScenario(mvc, json, tenant2, data.institution(tenant2, BigDecimal.ZERO));
    }

    static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    MockMultipartHttpServletRequestBuilder upload(byte[] bytes, String name, String caption) {
        MockMultipartHttpServletRequestBuilder b = multipart("/api/admin/institution/photos/campus");
        b.file(new MockMultipartFile("file", name, "application/octet-stream", bytes));
        b.with(r -> { r.setMethod("PUT"); return r; });
        if (caption != null) b.param("caption", caption);
        return b;
    }

    @Test
    void adminUploadsTheCampusPhotoAndThePortalShowsIt() throws Exception {
        s.call(get("/api/public/institution"), null, null).andExpect(status().isOk()).andExpect(jsonPath("$.campusPhoto").doesNotExist());
        byte[] photo = png(1200, 700);

        s.call(upload(photo, "campus.png", "Main campus, Dodoma"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.width").value(1200))
                .andExpect(jsonPath("$.caption").value("Main campus, Dodoma"));

        String url = s.body(s.call(get("/api/public/institution"), null, null)).get("campusPhoto").get("url").asText();
        assertThat(url).startsWith("/api/public/institution/photos/campus?v=");
        s.call(get("/api/public/institution/photos/campus"), null, null).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age")))
                .andExpect(content().bytes(photo));

        // a new caption and a replaced photo change the address, so browsers fetch the new one
        s.call(patch("/api/admin/institution/photos/campus"), admin, "{\"caption\":\"Kikuyu campus\"}").andExpect(jsonPath("$.caption").value("Kikuyu campus"));
        s.call(upload(png(1600, 900), "new.png", null), admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.width").value(1600));
        assertThat(s.body(s.call(get("/api/public/institution"), null, null)).get("campusPhoto").get("url").asText()).isNotEqualTo(url);

        // another institution does not see it
        other.call(get("/api/public/institution/photos/campus"), null, null).andExpect(status().isNotFound());

        s.call(delete("/api/admin/institution/photos/campus"), admin, null).andExpect(status().isNoContent());
        s.call(get("/api/public/institution/photos/campus"), null, null).andExpect(status().isNotFound());
        s.call(get("/api/public/institution"), null, null).andExpect(jsonPath("$.campusPhoto").doesNotExist());
    }

    @Test
    void onlyRealPhotosOfAUsefulSizeAreAccepted() throws Exception {
        s.call(upload("not an image".getBytes(), "campus.jpg", null), admin, null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PHOTO_TYPE"));
        s.call(upload(png(400, 300), "small.png", null), admin, null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PHOTO_TOO_SMALL"));
        s.call(upload(png(1200, 700), "campus.png", "x".repeat(151)), admin, null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CAPTION_TOO_LONG"));
    }

    @Test
    void onlyTheAdministratorManagesPhotos() throws Exception {
        s.call(upload(png(1200, 700), "campus.png", null), admissions, null).andExpect(status().isForbidden());
        s.call(upload(png(1200, 700), "campus.png", null), null, null).andExpect(status().isUnauthorized());
        s.call(delete("/api/admin/institution/photos/campus"), admissions, null).andExpect(status().isForbidden());
    }
}
