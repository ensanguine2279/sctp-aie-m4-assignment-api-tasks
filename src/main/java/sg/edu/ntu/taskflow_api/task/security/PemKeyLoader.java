package sg.edu.ntu.taskflow_api.task.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads an RSA public key from a PEM file.
 *
 * This class deliberately has no code path that can read a private key.
 * The tasks API only verifies tokens, so it must never be able to sign them.
 */
public final class PemKeyLoader {

    private static final String PUBLIC_BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String PUBLIC_END = "-----END PUBLIC KEY-----";

    private PemKeyLoader() {
    }

    /**
     * Loads an X.509 / SubjectPublicKeyInfo RSA public key.
     *
     * <p>
     * When {@code base64Key} is provided, it takes precedence over {@code path}.
     * The Base64 string may contain either Base64-encoded PEM content or raw PEM
     * text.
     *
     * @param base64Key raw PEM text or Base64-encoded PEM content (can be
     *                  null/blank)
     * @param path      file path to a public key PEM file (can be null/blank)
     * @return the loaded RSA {@link PublicKey}
     * @throws IllegalStateException if key loading or parsing fails, or if neither
     *                               source is provided
     */
    public static PublicKey loadPublicKey(String base64Key, String path) {
        if (base64Key != null && !base64Key.isBlank()) {
            try {
                String pemContent = decodeBase64OrRawPem(base64Key.trim());
                return parsePublicKey(pemContent);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to load JWT public key from Base64 environment variable: " + e.getMessage(), e);
            }
        }

        if (path != null && !path.isBlank()) {
            try {
                String pem = Files.readString(Path.of(path));
                return parsePublicKey(pem);
            } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
                throw new IllegalStateException(
                        "Could not load public key from " + path + ": " + e.getMessage(), e);
            }
        }

        throw new IllegalStateException(
                "Neither 'jwt.public-key-base64' nor 'jwt.public-key-path' was provided.");
    }

    /**
     * Reads an X.509 / SubjectPublicKeyInfo PEM file from a path.
     *
     * @throws IllegalStateException if the file is missing, unreadable or not a
     *                               valid RSA public key
     */
    public static PublicKey loadPublicKey(String path) {
        return loadPublicKey(null, path);
    }

    private static PublicKey parsePublicKey(String pem) throws GeneralSecurityException {
        return KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(decodePem(pem)));
    }

    private static String decodeBase64OrRawPem(String input) {
        if (input.contains(PUBLIC_BEGIN)) {
            return input;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(input);
            String decodedStr = new String(decoded).trim();
            if (decodedStr.contains(PUBLIC_BEGIN) || decodedStr.contains("PRIVATE KEY")) {
                return decodedStr;
            }
        } catch (IllegalArgumentException ignored) {
            // Not a base64-encoded string wrapper; let decodePem handle validation error
        }
        return input;
    }

    /**
     * Strips the PEM header, footer and whitespace and Base64-decodes the body.
     * Rejects anything that is not a {@code BEGIN PUBLIC KEY} block, including
     * PKCS#1 public keys and, as a safety net, private keys.
     */
    static byte[] decodePem(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("PEM content is empty");
        }
        if (pem.contains("PRIVATE KEY")) {
            throw new IllegalArgumentException(
                    "File or variable contains a private key. The tasks API must only be given public.pem");
        }
        if (!pem.contains(PUBLIC_BEGIN) || !pem.contains(PUBLIC_END)) {
            throw new IllegalArgumentException(
                    "Expected a PEM block starting with " + PUBLIC_BEGIN
                            + " (a BEGIN RSA PUBLIC KEY file is PKCS#1 and is not supported)");
        }
        String body = pem
                .replace(PUBLIC_BEGIN, "")
                .replace(PUBLIC_END, "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
