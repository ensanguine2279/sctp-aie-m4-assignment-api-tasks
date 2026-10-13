package sg.edu.ntu.taskflow_api.task.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import org.springframework.test.context.DynamicPropertyRegistry;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;

/**
 * Test helper. Generates throwaway RSA key pairs once per JVM, writes the
 * public half to a temp file, and builds tokens shaped like the users
 * service's.
 *
 * The values below deliberately differ from the production defaults, which is
 * fine because tests override the properties through register(...).
 *
 * Every @SpringBootTest must call register(...) from a @DynamicPropertySource,
 * or the context fails on "Could not resolve placeholder
 * 'jwt.public-key-path'".
 */
public final class TestJwtKeys {

    public static final String ISSUER = "users-service";
    public static final String AUDIENCE = "tasks-api";
    /** The "uid" claim that baseBuilder puts in every token. */
    public static final long DEFAULT_UID = 42L;

    private static final KeyPair SIGNER = generate();
    private static final KeyPair OTHER = generate();
    private static final Path PUBLIC_KEY_FILE = writePublicKeyFile(SIGNER.getPublic());

    private TestJwtKeys() {
    }

    /** The pair the "users service" signs with. */
    public static KeyPair keyPair() {
        return SIGNER;
    }

    /** An unrelated pair, for wrong-signer tests. */
    public static KeyPair otherKeyPair() {
        return OTHER;
    }

    public static String publicKeyPath() {
        return PUBLIC_KEY_FILE.toString();
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key-path", TestJwtKeys::publicKeyPath);
        registry.add("jwt.issuer", () -> ISSUER);
        registry.add("jwt.audience", () -> AUDIENCE);
    }

    /**
     * A builder with the same claims as the users service: iss, aud (serialised
     * as an array), sub, uid, iat, exp (+1h) and roles. Not yet signed, so a test
     * can change any claim before calling sign(...). Passing a null username or
     * null roles leaves that claim out.
     */
    public static JwtBuilder baseBuilder(String username, List<String> roles) {
        Date now = new Date();
        return Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 3_600_000L))
                .claim("roles", roles)
                .claim("uid", DEFAULT_UID);
    }

    /** Like baseBuilder, but with a chosen "uid" claim. */
    public static JwtBuilder baseBuilder(String username, List<String> roles, long uid) {
        return baseBuilder(username, roles).claim("uid", uid);
    }

    /**
     * Like baseBuilder, but expiring offsetMs from now (negative = already
     * expired).
     */
    public static JwtBuilder expiringIn(String username, List<String> roles, long offsetMs) {
        long exp = System.currentTimeMillis() + offsetMs;
        return baseBuilder(username, roles)
                .issuedAt(new Date(exp - 3_600_000L))
                .expiration(new Date(exp));
    }

    public static String sign(JwtBuilder builder) {
        return sign(builder, SIGNER.getPrivate());
    }

    public static String sign(JwtBuilder builder, PrivateKey key) {
        return builder.signWith(key, Jwts.SIG.RS256).compact();
    }

    public static String toPem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writePublicKeyFile(PublicKey key) {
        try {
            Path file = Files.createTempFile("taskflow-test-public", ".pem");
            Files.writeString(file, toPem("PUBLIC KEY", key.getEncoded()));
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}