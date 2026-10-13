package sg.edu.ntu.taskflow_api.task.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import sg.edu.ntu.taskflow_api.task.entity.Task;
import sg.edu.ntu.taskflow_api.task.entity.TaskPriority;

/** Plain unit test of the ownership rule: no Spring context. */
class TaskAccessPolicyTest {

    private static final long OWNER_ID = 42L;
    private static final long OTHER_ID = 99L;

    private final TaskAccessPolicy policy = new TaskAccessPolicy();

    private static Task taskOwnedBy(Long userId) {
        Task task = new Task("Task", "Description", TaskPriority.LOW, false, 1L);
        task.setUserId(userId);
        return task;
    }

    private static Authentication caller(long userId, String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser("someone", userId), null, authorities);
    }

    @Test
    void ownerMayAccessTheirTask() {
        assertThat(policy.canAccess(caller(OWNER_ID, "ROLE_USER"), taskOwnedBy(OWNER_ID))).isTrue();
    }

    @Test
    void ownerWithNoRolesMayStillAccessTheirTask() {
        assertThat(policy.canAccess(caller(OWNER_ID), taskOwnedBy(OWNER_ID))).isTrue();
    }

    /** Regression: a task's own id that happens to equal the caller's id is not ownership. */
    @Test
    void aTaskIdEqualToTheCallersIdIsNotOwnership() {
        Task task = taskOwnedBy(OWNER_ID);
        task.setId(OTHER_ID);

        assertThat(policy.canAccess(caller(OTHER_ID, "ROLE_USER"), task)).isFalse();
    }

    @Test
    void anotherUserMayNot() {
        assertThat(policy.canAccess(caller(OTHER_ID, "ROLE_USER"), taskOwnedBy(OWNER_ID))).isFalse();
        assertThat(policy.canAccess(caller(OTHER_ID), taskOwnedBy(OWNER_ID))).isFalse();
    }

    @Test
    void adminAndManagerMayAccessAnyTask() {
        assertThat(policy.canAccess(caller(OTHER_ID, "ROLE_ADMIN"), taskOwnedBy(OWNER_ID))).isTrue();
        assertThat(policy.canAccess(caller(OTHER_ID, "ROLE_MANAGER"), taskOwnedBy(OWNER_ID))).isTrue();
    }

    @Test
    void aTaskWithNoOwnerIsOnlyForAdminAndManager() {
        assertThat(policy.canAccess(caller(OWNER_ID, "ROLE_USER"), taskOwnedBy(null))).isFalse();
        assertThat(policy.canAccess(caller(OWNER_ID, "ROLE_ADMIN"), taskOwnedBy(null))).isTrue();
    }

    @Test
    void failsClosedForAnUnexpectedPrincipalType() {
        Authentication stringPrincipal = UsernamePasswordAuthenticationToken.authenticated(
                "someone", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        assertThat(policy.canAccess(stringPrincipal, taskOwnedBy(OWNER_ID))).isFalse();
    }

    @Test
    void failsClosedWithoutAuthenticationOrTask() {
        assertThat(policy.canAccess(null, taskOwnedBy(OWNER_ID))).isFalse();
        assertThat(policy.canAccess(caller(OWNER_ID, "ROLE_USER"), null)).isFalse();
    }

    @Test
    void anotherRoleDoesNotGrantAccess() {
        assertThat(policy.canAccess(caller(OTHER_ID, "ROLE_SUPERVISOR"), taskOwnedBy(OWNER_ID))).isFalse();
    }
}