package sg.edu.ntu.taskflow_api.task.security;

import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import sg.edu.ntu.taskflow_api.task.entity.Task;

/**
 * Who may see and complete a given task.
 *
 * ADMIN and MANAGER may access every task. Anyone else may access only a task
 * whose userId is their own id from the token. It fails closed: an unexpected
 * principal or a task with no owner means no access.
 *
 * This is separate from the role rules (@PreAuthorize), which decide who may
 * call a route at all. Callers turn a refusal into a 404, so a user cannot tell
 * another user's task from one that does not exist.
 */
@Component
public class TaskAccessPolicy {

    private static final Set<String> PRIVILEGED_ROLES = Set.of("ROLE_ADMIN", "ROLE_MANAGER");

    public boolean canAccess(Authentication authentication, Task task) {
        if (authentication == null) {
            return false;
        }
        boolean privileged = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(PRIVILEGED_ROLES::contains);
        if (privileged) {
            return true;
        }
        return task != null
                && task.getUserId() != null
                && authentication.getPrincipal() instanceof AuthenticatedUser user
                && task.getUserId() == user.userId();
    }
}