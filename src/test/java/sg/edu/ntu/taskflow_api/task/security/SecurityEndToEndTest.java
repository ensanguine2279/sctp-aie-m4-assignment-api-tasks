package sg.edu.ntu.taskflow_api.task.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Spring Boot 4 moved this annotation. On Boot 3 it was org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * The full HTTP flow through the real filter chain, entry point and
 * GlobalExceptionHandler, using test-only endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityEndToEndTest.ProbeController.class)
class SecurityEndToEndTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestJwtKeys.register(registry);
        // The main properties use PostgreSQL, so tests point at H2.
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:tasks-e2e;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @RestController
    public static class ProbeController {

        @GetMapping("/test-probe/secured")
        public String secured(Authentication authentication) {
            return authentication.getName();
        }

        @GetMapping("/test-probe/admin")
        @PreAuthorize("hasRole('ADMIN')")
        public String admin() {
            return "admin-ok";
        }
    }

    @Autowired
    MockMvc mockMvc;

    private static String bearer(List<String> roles) {
        return "Bearer " + TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", roles));
    }

    // ---------- 200 ----------

    @Test
    void validTokenReachesAProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/test-probe/secured").header(HttpHeaders.AUTHORIZATION, bearer(List.of("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(content().string("alice"));
    }

    @Test
    void adminRoleReachesAnAdminOnlyEndpoint() throws Exception {
        mockMvc.perform(get("/test-probe/admin").header(HttpHeaders.AUTHORIZATION, bearer(List.of("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().string("admin-ok"));
    }

    // ---------- 401 ----------

    @Test
    void noTokenIs401WithBearerChallengeAndErrorBody() throws Exception {
        mockMvc.perform(get("/test-probe/secured"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.message").value("Authentication failed"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void expiredTokenIs401() throws Exception {
        String expired = TestJwtKeys.sign(TestJwtKeys.expiringIn("alice", List.of("ROLE_USER"), -120_000));

        mockMvc.perform(get("/test-probe/secured").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.message").value("Token has expired"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void tokenSignedByAnotherKeyIs401() throws Exception {
        String forged = TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", List.of("ROLE_ADMIN")),
                TestJwtKeys.otherKeyPair().getPrivate());

        mockMvc.perform(get("/test-probe/admin").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Token signature is invalid"));
    }

    @Test
    void hs256TokenKeyedWithThePublicKeyIs401() throws Exception {
        String forged = TestJwtKeys.baseBuilder("mallory", List.of("ROLE_ADMIN"))
                .signWith(Keys.hmacShaKeyFor(TestJwtKeys.keyPair().getPublic().getEncoded()), Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(get("/test-probe/admin").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void garbageTokenIs401() throws Exception {
        mockMvc.perform(get("/test-probe/secured").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Token is malformed"));
    }

    // ---------- 403 ----------

    @Test
    void authenticatedWithoutTheRoleIs403WithErrorBody() throws Exception {
        mockMvc.perform(get("/test-probe/admin").header(HttpHeaders.AUTHORIZATION, bearer(List.of("ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not have permission to access this resource"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ---------- Spring's own errors keep their status ----------

    @Test
    void unknownRouteIs404NotAn500() throws Exception {
        mockMvc.perform(get("/no-such-route").header(HttpHeaders.AUTHORIZATION, bearer(List.of("ROLE_USER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Not Found"));
    }

    @Test
    void wrongMethodIs405NotAn500() throws Exception {
        mockMvc.perform(post("/test-probe/secured").header(HttpHeaders.AUTHORIZATION, bearer(List.of("ROLE_USER"))))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.message").value("Method Not Allowed"));
    }
}