package sg.edu.ntu.taskflow_api.task.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Rules used from @PreAuthorize expressions as @userSecurity.
 *
 * The caller's id comes from the verified token (the "uid" claim), so there is
 * no database lookup. It fails closed: no authentication, or an unexpected
 * principal type, means "not self".
 * 
 * Use it only where the path holds a USER id (for example
 * /api/tasks/user/{userId}). Where the path holds a TASK id, the owner has to
 * be read from the task, which is what TaskAccessPolicy does.
 */
@Component("userSecurity")
public class UserSecurity {
    public boolean isSelf(long id, Authentication authentication) {
        return authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser user
                && user.userId() == id;
    }
}