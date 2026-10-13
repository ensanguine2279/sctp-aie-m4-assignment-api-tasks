package sg.edu.ntu.taskflow_api.task.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Plain unit test: no Spring context. The id comes from the token. */
class UserSecurityTest {

    private final UserSecurity userSecurity = new UserSecurity();

    private static Authentication caller(long userId) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser("someone", userId), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    void isSelf_matchingId_isTrue() {
        assertThat(userSecurity.isSelf(42L, caller(42L))).isTrue();
    }

    @Test
    void isSelf_differentId_isFalse() {
        assertThat(userSecurity.isSelf(99L, caller(42L))).isFalse();
    }

    @Test
    void isSelf_largeId_isTrue() {
        long big = 5_000_000_000L;

        assertThat(userSecurity.isSelf(big, caller(big))).isTrue();
    }

    @Test
    void isSelf_failsClosed() {
        Authentication stringPrincipal = UsernamePasswordAuthenticationToken.authenticated(
                "someone", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        assertThat(userSecurity.isSelf(42L, null)).isFalse();
        assertThat(userSecurity.isSelf(42L, stringPrincipal)).isFalse();
    }
}