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
     * Reads an X.509 / SubjectPublicKeyInfo PEM file (first line
     * {@code -----BEGIN PUBLIC KEY-----}), as produced by
     * {@code openssl rsa -pubout}.
     *
     * @throws IllegalStateException if the file is missing, unreadable or not a
     *                               valid RSA public key in that format
     */
    public static PublicKey loadPublicKey(String path) {
        try {
            String pem = Files.readString(Path.of(path));
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(decodePem(pem)));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Could not load public key from " + path + ": " + e, e);
        }
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
                    "File contains a private key. The tasks API must only be given public.pem");
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
