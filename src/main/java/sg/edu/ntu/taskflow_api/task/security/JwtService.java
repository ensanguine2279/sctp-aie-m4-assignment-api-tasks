package sg.edu.ntu.taskflow_api.task.security;

import java.security.PublicKey;
import java.util.Date;
import java.util.List;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import sg.edu.ntu.taskflow_api.task.dto.ClaimsDto;
import sg.edu.ntu.taskflow_api.task.exception.InvalidTokenException;
import sg.edu.ntu.taskflow_api.task.exception.InvalidTokenException.Reason;

/**
 * Verifies RS256 tokens issued by the users service.
 *
 * This class only holds the PUBLIC key. It has no way to create a token, and
 * it must stay that way: the tasks API can never mint credentials.
 */
@Service
public class JwtService {
    private static final Logger logger = LoggerFactory.getLogger(JwtService.class);

    /** Absorbs small clock differences between services. */
    private static final long CLOCK_SKEW_SECONDS = 30;

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String USER_ID_CLAIM = "uid";

    private final PublicKey publicKey;
    private final String issuer;
    private final String audience;

    public JwtService(
            @Value("${jwt.public-key-path}") String publicKeyPath,
            @Value("${jwt.issuer}") String issuer,
            @Value("${jwt.audience}") String audience) {

        logger.info("Loading public key from path: {}", publicKeyPath);

        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalStateException("jwt.issuer and jwt.audience must both be set");
        }
        // Fails fast (IllegalStateException) if the key is missing or malformed.
        this.publicKey = PemKeyLoader.loadPublicKey(publicKeyPath);
        this.issuer = issuer;
        this.audience = audience;
    }

    private Claims parseToken(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException(Reason.EMPTY, "Token is missing");
        }
        try {
            // verifyWith(PublicKey) pins verification to an asymmetric signature,
            // so HS256 tokens (algorithm confusion) and unsigned tokens are refused.
            return Jwts.parser()
                    .verifyWith(publicKey)
                    .requireIssuer(issuer)
                    .requireAudience(audience)
                    .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw new InvalidTokenException(Reason.EXPIRED, "Token has expired", ex);
        } catch (SignatureException ex) {
            throw new InvalidTokenException(Reason.BAD_SIGNATURE, "Token signature is invalid", ex);
        } catch (MalformedJwtException | UnsupportedJwtException ex) {
            throw new InvalidTokenException(Reason.MALFORMED, "Token is malformed", ex);
        } catch (IllegalArgumentException ex) {
            throw new InvalidTokenException(Reason.EMPTY, "Token is missing", ex);
        } catch (JwtException ex) {
            // Wrong issuer or audience, HS256 or unsigned tokens, anything else from jjwt.
            throw new InvalidTokenException(Reason.MALFORMED, "Token is invalid", ex);
        }
    }

    public ClaimsDto extractClaims(String token) {
        // The signature is verified before the payload is read, so a tampered
        // token throws before getSubject() is reached.
        Claims claims = parseToken(token);

        String username = claims.getSubject();
        if (username == null || username.isBlank()) {
            throw new InvalidTokenException(Reason.MALFORMED, "Token has no subject");
        }

        Date expiration = claims.getExpiration();
        if (expiration == null) {
            throw new InvalidTokenException(Reason.MALFORMED, "Token has no expiry");
        }

        long userId = extractUserId(claims);

        return new ClaimsDto(username, extractRoles(claims), expiration.getTime(), userId);
    }

    /**
     * The users service puts the caller's database id in the "uid" claim, as a JSON
     * number. It is required: without it the tasks API cannot tell whose task is
     * whose, so a token lacking a valid uid is rejected like one lacking a subject.
     * Strings, booleans, fractions, zero and negative values are all refused.
     */
    private long extractUserId(Claims claims) {
        Object uid = claims.get(USER_ID_CLAIM);
        if (uid instanceof Number number) {
            long value = number.longValue();
            // The double comparison rejects 1.5 and numbers too large for a long.
            if (value > 0 && number.doubleValue() == (double) value) {
                return value;
            }
        }
        throw new InvalidTokenException(Reason.MALFORMED, "Token has no valid uid");
    }

    private List<String> extractRoles(Claims claims) {
        Object rolesClaim = claims.get("roles");
        if (!(rolesClaim instanceof List<?> roles)) {
            return List.of();
        }

        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(role -> role.startsWith(ROLE_PREFIX))
                .toList();
    }
}