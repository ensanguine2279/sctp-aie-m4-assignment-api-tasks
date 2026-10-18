package sg.edu.ntu.taskflow_api.task.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
        assertThat(claims.userId()).isEqualTo(TestJwtKeys.DEFAULT_UID);
        assertThat(claims.roles()).containsExactly("ROLE_USER", "ROLE_ADMIN");
        assertThat(claims.expiration()).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void tokenExpiredWithinClockSkewIsStillAccepted() {
        String token = TestJwtKeys.sign(TestJwtKeys.expiringIn("alice", List.of("ROLE_USER"), -5_000));

        assertThat(service.extractClaims(token).username()).isEqualTo("alice");
    }

    @Test
    void acceptsTokenShapedLikeTheUsersService() {
        String token = TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", List.of("ROLE_ADMIN")));

        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(payload)
                .contains("\"iss\":\"users-service\"")
                .contains("\"aud\":[\"tasks-api\"]")
                .contains("\"sub\":\"alice\"")
                .contains("\"uid\":42")
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
        String tampered = a[0] + "." + b[1] + "." + a[2];

        assertThat(rejected(tampered).getReason()).isEqualTo(Reason.BAD_SIGNATURE);
    }

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

    // ---------- uid ----------

    @Test
    void uidBeyondTheIntRangeIsAccepted() {
        long big = 5_000_000_000L;
        String token = TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", List.of("ROLE_USER")).claim("uid", big));

        assertThat(service.extractClaims(token).userId()).isEqualTo(big);
    }

    static Stream<Arguments> invalidUids() {
        return Stream.of(
                Arguments.of("missing", null),
                Arguments.of("a string", "42"),
                Arguments.of("a boolean", true),
                Arguments.of("zero", 0),
                Arguments.of("negative", -5),
                Arguments.of("fractional", 1.5));
    }

    @ParameterizedTest(name = "uid {0} is rejected")
    @MethodSource("invalidUids")
    void rejectsAnInvalidUid(String label, Object uid) {
        String token = TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", List.of("ROLE_USER")).claim("uid", uid));

        assertThat(rejected(token).getReason()).isEqualTo(Reason.MALFORMED);
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
    void loadsFromBase64KeyVariable() {
        String rawPem = TestJwtKeys.toPem("PUBLIC KEY", TestJwtKeys.keyPair().getPublic().getEncoded());
        JwtService base64Service = new JwtService(rawPem, null, TestJwtKeys.ISSUER, TestJwtKeys.AUDIENCE);

        String token = TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", List.of("ROLE_USER")));
        ClaimsDto claims = base64Service.extractClaims(token);

        assertThat(claims.username()).isEqualTo("alice");
    }

    @Test
    void failsFastWhenBothBase64AndPathAreMissing() {
        assertThatThrownBy(() -> new JwtService("   ", "", TestJwtKeys.ISSUER, TestJwtKeys.AUDIENCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Neither 'jwt.public-key-base64' nor 'jwt.public-key-path'");
    }

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