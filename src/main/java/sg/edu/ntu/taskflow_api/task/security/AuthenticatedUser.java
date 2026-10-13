package sg.edu.ntu.taskflow_api.task.security;

import java.security.Principal;

/**
 * Who is calling, as read from a verified token: the username ("sub") and the
 * user's database id ("uid"). It implements Principal so
 * Authentication.getName() still returns the username.
 */
public record AuthenticatedUser(String username, long userId) implements Principal {

    @Override
    public String getName() {
        return username;
    }
}