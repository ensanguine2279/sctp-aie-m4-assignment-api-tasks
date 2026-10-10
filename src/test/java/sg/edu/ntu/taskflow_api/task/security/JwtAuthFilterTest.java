package sg.edu.ntu.taskflow_api.task.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import sg.edu.ntu.taskflow_api.task.exception.InvalidTokenException;
import sg.edu.ntu.taskflow_api.task.exception.InvalidTokenException.Reason;

class JwtAuthFilterTest {

    private JwtAuthFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        JwtService jwtService = new JwtService(TestJwtKeys.publicKeyPath(), TestJwtKeys.ISSUER, TestJwtKeys.AUDIENCE);
        filter = new JwtAuthFilter(jwtService);
        request = new MockHttpServletRequest("GET", "/anything");
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private String validToken() {
        return TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", List.of("ROLE_USER", "ROLE_ADMIN")));
    }

    private InvalidTokenException recordedRejection() {
        return (InvalidTokenException) request.getAttribute(JwtAuthFilter.INVALID_TOKEN_ATTRIBUTE);
    }

    @Test
    void noHeaderContinuesTheChainWithoutAuthentication() throws Exception {
        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(recordedRejection()).isNull();
    }

    @Test
    void nonBearerSchemeIsIgnored() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic YWxpY2U6cGFzcw==");

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(recordedRejection()).isNull();
    }

    @Test
    void validTokenSetsAuthentication() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + validToken());

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(chain.getRequest()).isNotNull();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("alice");
        assertThat(auth.getAuthorities()).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        assertThat(recordedRejection()).isNull();
    }

    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, "bEaReR " + validToken());

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void tokenIsTrimmed() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer   " + validToken() + "  ");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void invalidTokenRecordsTheReasonAndStillContinuesTheChain() throws Exception {
        String expired = TestJwtKeys.sign(TestJwtKeys.expiringIn("alice", List.of("ROLE_USER"), -120_000));
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + expired);

        filter.doFilter(request, response, chain);

        // The filter does not write a 401 itself. The chain continues and the
        // entry point rejects the unauthenticated request using this attribute.
        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(recordedRejection()).isNotNull();
        assertThat(recordedRejection().getReason()).isEqualTo(Reason.EXPIRED);
    }

    @Test
    void bearerWithNoTokenIsRecordedAsEmpty() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer ");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(recordedRejection()).isNotNull();
        assertThat(recordedRejection().getReason()).isEqualTo(Reason.EMPTY);
    }

    @Test
    void invalidTokenClearsAnyExistingAuthentication() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", null, List.of()));
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer garbage");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(recordedRejection().getReason()).isEqualTo(Reason.MALFORMED);
    }
}