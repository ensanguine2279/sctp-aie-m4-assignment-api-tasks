package sg.edu.ntu.taskflow_api.task.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PemKeyLoaderTest {

        @TempDir
        Path dir;

        // ---------- File Path Tests ----------

        @Test
        void loadsValidPublicKey() {
                PublicKey loaded = PemKeyLoader.loadPublicKey(TestJwtKeys.publicKeyPath());

                assertThat(loaded).isEqualTo(TestJwtKeys.keyPair().getPublic());
        }

        @Test
        void toleratesWindowsLineEndings() throws Exception {
                String pem = TestJwtKeys.toPem("PUBLIC KEY", TestJwtKeys.keyPair().getPublic().getEncoded())
                                .replace("\n", "\r\n");
                Path file = Files.writeString(dir.resolve("crlf.pem"), pem);

                assertThat(PemKeyLoader.loadPublicKey(file.toString()))
                                .isEqualTo(TestJwtKeys.keyPair().getPublic());
        }

        @Test
        void rejectsMissingFile() {
                String path = dir.resolve("nope.pem").toString();

                assertThatThrownBy(() -> PemKeyLoader.loadPublicKey(path))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("Could not load public key")
                                .hasMessageContaining(path);
        }

        @Test
        void rejectsEmptyFile() throws Exception {
                Path file = Files.writeString(dir.resolve("empty.pem"), "");

                assertThatThrownBy(() -> PemKeyLoader.loadPublicKey(file.toString()))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("Could not load public key");
        }

        @Test
        void rejectsPrivateKeyFile() throws Exception {
                String pem = TestJwtKeys.toPem("PRIVATE KEY", TestJwtKeys.keyPair().getPrivate().getEncoded());
                Path file = Files.writeString(dir.resolve("private.pem"), pem);

                assertThatThrownBy(() -> PemKeyLoader.loadPublicKey(file.toString()))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("private key");
        }

        @Test
        void rejectsPkcs1PublicKey() throws Exception {
                Path file = Files.writeString(dir.resolve("pkcs1.pem"),
                                "-----BEGIN RSA PUBLIC KEY-----\nAAAA\n-----END RSA PUBLIC KEY-----\n");

                assertThatThrownBy(() -> PemKeyLoader.loadPublicKey(file.toString()))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("PKCS#1");
        }

        @Test
        void rejectsCorruptBody() throws Exception {
                Path file = Files.writeString(dir.resolve("corrupt.pem"),
                                "-----BEGIN PUBLIC KEY-----\nbm90IGEga2V5\n-----END PUBLIC KEY-----\n");

                assertThatThrownBy(() -> PemKeyLoader.loadPublicKey(file.toString()))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("Could not load public key");
        }

        // ---------- Base64 & Dual-Source Tests ----------

        @Test
        void loadsValidPublicKeyFromBase64Variable() {
                String rawPem = TestJwtKeys.toPem("PUBLIC KEY", TestJwtKeys.keyPair().getPublic().getEncoded());
                String encodedPem = Base64.getEncoder().encodeToString(rawPem.getBytes());

                PublicKey loadedFromRaw = PemKeyLoader.loadPublicKey(rawPem, null);
                PublicKey loadedFromEncoded = PemKeyLoader.loadPublicKey(encodedPem, null);

                assertThat(loadedFromRaw).isEqualTo(TestJwtKeys.keyPair().getPublic());
                assertThat(loadedFromEncoded).isEqualTo(TestJwtKeys.keyPair().getPublic());
        }

        @Test
        void base64KeyTakesPrecedenceOverFilePath() {
                String rawPem = TestJwtKeys.toPem("PUBLIC KEY", TestJwtKeys.keyPair().getPublic().getEncoded());
                String missingPath = dir.resolve("non-existent.pem").toString();

                PublicKey loaded = PemKeyLoader.loadPublicKey(rawPem, missingPath);

                assertThat(loaded).isEqualTo(TestJwtKeys.keyPair().getPublic());
        }

        @Test
        void rejectsWhenBothBase64KeyAndPathAreBlank() {
                assertThatThrownBy(() -> PemKeyLoader.loadPublicKey("   ", ""))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining(
                                                "Neither 'jwt.public-key-base64' nor 'jwt.public-key-path' was provided");
        }

        @Test
        void rejectsPrivateKeyInBase64Variable() {
                String privateKeyPem = TestJwtKeys.toPem("PRIVATE KEY",
                                TestJwtKeys.keyPair().getPrivate().getEncoded());
                String base64PrivateKey = Base64.getEncoder().encodeToString(privateKeyPem.getBytes());

                assertThatThrownBy(() -> PemKeyLoader.loadPublicKey(base64PrivateKey, null))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("Failed to load JWT public key from Base64 environment variable")
                                .hasRootCauseMessage(
                                                "File or variable contains a private key. The tasks API must only be given public.pem");
        }

        @Test
        void rejectsPkcs1PrivateKeyInBase64Variable() {
                String pkcs1PrivateKey = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEAu6\n-----END RSA PRIVATE KEY-----\n";

                assertThatThrownBy(() -> PemKeyLoader.loadPublicKey(pkcs1PrivateKey, null))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("private key");
        }
}