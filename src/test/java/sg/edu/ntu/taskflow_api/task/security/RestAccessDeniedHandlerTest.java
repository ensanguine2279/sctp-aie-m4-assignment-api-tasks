package sg.edu.ntu.taskflow_api.task.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * SecurityConfig currently has no URL-level role rules, so no HTTP test reaches
 * this handler yet. This pins its behaviour so it works when such rules are
 * added.
 */
class RestAccessDeniedHandlerTest {

    @Test
    void delegatesTheExceptionToTheHandlerExceptionResolver() {
        HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
        RestAccessDeniedHandler handler = new RestAccessDeniedHandler(resolver);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AccessDeniedException denied = new AccessDeniedException("nope");

        handler.handle(request, response, denied);

        verify(resolver).resolveException(request, response, null, denied);
    }
}