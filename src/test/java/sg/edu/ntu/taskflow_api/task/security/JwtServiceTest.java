package sg.edu.ntu.taskflow_api.task.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import sg.edu.ntu.taskflow_api.task.dto.ClaimsDto;
import sg.edu.ntu.taskflow_api.task.exception.InvalidTokenException;
import sg.edu.ntu.taskflow_api.task.exception.InvalidTokenException.Reason;

class JwtServiceTest {

    @TempDir
    Path dir;

    private JwtService service;

    @BeforeEach
    void setUp() {
        service = new JwtService(TestJwtKeys.publicKeyPath(), TestJwtKeys.ISSUER, TestJwtKeys.AUDIENCE);
    }

    /**
     * Runs extractClaims and returns the rejection. Fails if the token is accepted.
     */
    private InvalidTokenException rejected(String token) {
        try {
            service.extractClaims(token);
        } catch (InvalidTokenException ex) {
            return ex;
        }
        throw new AssertionError("Token was accepted but should have been rejected");
    }

    // ---------- accepted ----------

    @Test
    void validTokenRoundTrip() {
        String token = TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", List.of("ROLE_USER", "ROLE_ADMIN")));

        ClaimsDto claims = service.extractClaims(token);

        assertThat(claims.username()).isEqualTo("alice");
        assertThat(claims.roles()).containsExactly("ROLE_USER", "ROLE_ADMIN");
        assertThat(claims.expiration()).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void tokenExpiredWithinClockSkewIsStillAccepted() {
        String token = TestJwtKeys.sign(TestJwtKeys.expiringIn("alice", List.of("ROLE_USER"), -5_000));

        assertThat(service.extractClaims(token).username()).isEqualTo("alice");
    }

    /** Pins the token shape the users service issues, including aud as an array. */
    @Test
    void acceptsTokenShapedLikeTheUsersService() {
        String token = TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", List.of("ROLE_ADMIN")));

        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(payload)
                .contains("\"iss\":\"users-service\"")
                .contains("\"aud\":[\"tasks-api\"]")
                .contains("\"sub\":\"alice\"")
                .contains("\"roles\":[\"ROLE_ADMIN\"]")
                .contains("\"iat\":")
                .contains("\"exp\":");

        ClaimsDto claims = service.extractClaims(token);
        assertThat(claims.username()).isEqualTo("alice");
        assertThat(claims.roles()).containsExactly("ROLE_ADMIN");
    }

    // ---------- rejected ----------

    @Test
    void rejectsTokenExpiredBeyondSkew() {
        // Must exceed the 30s skew: -5_000 would still be accepted.
        String token = TestJwtKeys.sign(TestJwtKeys.expiringIn("alice", List.of("ROLE_USER"), -120_000));

        assertThat(rejected(token).getReason()).isEqualTo(Reason.EXPIRED);
    }

    @Test
    void rejectsWrongSigner() {
        String token = TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", List.of("ROLE_USER")),
                TestJwtKeys.otherKeyPair().getPrivate());

        assertThat(rejected(token).getReason()).isEqualTo(Reason.BAD_SIGNATURE);
    }

    @Test
    void rejectsTamperedPayload() {
        String genuine = TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", List.of("ROLE_USER")));
        String forged = TestJwtKeys.sign(TestJwtKeys.baseBuilder("mallory", List.of("ROLE_ADMIN")));
        String[] a = genuine.split("\\.");
        String[] b = forged.split("\\.");
        // genuine header + forged payload + genuine signature
        String tampered = a[0] + "." + b[1] + "." + a[2];

        assertThat(rejected(tampered).getReason()).isEqualTo(Reason.BAD_SIGNATURE);
    }

    // For the next three, only the exception type is asserted: jjwt reports
    // these through different exception classes, so the reason is not pinned.

    @Test
    void rejectsWrongIssuer() {
        String token = TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", List.of("ROLE_USER")).issuer("someone-else"));

        assertThatThrownBy(() -> service.extractClaims(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsWrongAudience() {
        String token = TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", List.of("ROLE_USER"))
                .audience().clear().add("another-api").and());

        assertThatThrownBy(() -> service.extractClaims(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsHs256TokenKeyedWithThePublicKey() {
        // The classic algorithm-confusion attack.
        SecretKey forgedKey = Keys.hmacShaKeyFor(TestJwtKeys.keyPair().getPublic().getEncoded());
        String token = TestJwtKeys.baseBuilder("mallory", List.of("ROLE_ADMIN"))
                .signWith(forgedKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> service.extractClaims(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsUnsignedToken() {
        String token = TestJwtKeys.baseBuilder("mallory", List.of("ROLE_ADMIN")).compact();

        assertThatThrownBy(() -> service.extractClaims(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsMissingSubject() {
        String token = TestJwtKeys.sign(TestJwtKeys.baseBuilder(null, List.of("ROLE_USER")));

        assertThat(rejected(token).getReason()).isEqualTo(Reason.MALFORMED);
    }

    @Test
    void rejectsMissingExpiry() {
        String token = TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", List.of("ROLE_USER")).expiration(null));

        assertThat(rejected(token).getReason()).isEqualTo(Reason.MALFORMED);
    }

    @Test
    void rejectsGarbage() {
        assertThat(rejected("garbage").getReason()).isEqualTo(Reason.MALFORMED);
    }

    @Test
    void rejectsEmptyBlankAndNull() {
        assertThat(rejected("").getReason()).isEqualTo(Reason.EMPTY);
        assertThat(rejected("   ").getReason()).isEqualTo(Reason.EMPTY);
        assertThat(rejected(null).getReason()).isEqualTo(Reason.EMPTY);
    }

    // ---------- roles ----------

    @Test
    void missingRolesClaimMeansNoAuthorities() {
        String token = TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", null));

        assertThat(service.extractClaims(token).roles()).isEmpty();
    }

    @Test
    void nonListRolesClaimMeansNoAuthorities() {
        String token = TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", null).claim("roles", "ROLE_ADMIN"));

        assertThat(service.extractClaims(token).roles()).isEmpty();
    }

    @Test
    void rolesWithoutThePrefixAreIgnored() {
        String token = TestJwtKeys.sign(
                TestJwtKeys.baseBuilder("alice", List.of("ROLE_USER", "ADMIN", "FACTOR_PASSWORD")));

        assertThat(service.extractClaims(token).roles()).containsExactly("ROLE_USER");
    }

    @Test
    void nonStringRoleEntriesAreIgnored() {
        List<Object> mixed = Arrays.asList(1, "ROLE_ADMIN", null);
        String token = TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", null).claim("roles", mixed));

        assertThat(service.extractClaims(token).roles()).containsExactly("ROLE_ADMIN");
    }

    // ---------- construction (fail fast) ----------

    @Test
    void failsFastWhenKeyFileIsMissing() {
        String missing = dir.resolve("nope.pem").toString();

        assertThatThrownBy(() -> new JwtService(missing, TestJwtKeys.ISSUER, TestJwtKeys.AUDIENCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not load public key");
    }

    @Test
    void failsFastWhenGivenAPrivateKey() throws Exception {
        Path file = Files.writeString(dir.resolve("private.pem"),
                TestJwtKeys.toPem("PRIVATE KEY", TestJwtKeys.keyPair().getPrivate().getEncoded()));

        assertThatThrownBy(() -> new JwtService(file.toString(), TestJwtKeys.ISSUER, TestJwtKeys.AUDIENCE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastWhenIssuerOrAudienceIsBlank() {
        assertThatThrownBy(() -> new JwtService(TestJwtKeys.publicKeyPath(), " ", TestJwtKeys.AUDIENCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.issuer");
        assertThatThrownBy(() -> new JwtService(TestJwtKeys.publicKeyPath(), TestJwtKeys.ISSUER, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.audience");
    }
}