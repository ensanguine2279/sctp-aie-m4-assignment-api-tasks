package sg.edu.ntu.taskflow_api.task.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
// Spring Boot 4 package. On Boot 3 it was org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import sg.edu.ntu.taskflow_api.task.entity.Task;
import sg.edu.ntu.taskflow_api.task.entity.TaskPriority;
import sg.edu.ntu.taskflow_api.task.security.TestJwtKeys;
import sg.edu.ntu.taskflow_api.task.service.TaskService;

/**
 * Pins the @PreAuthorize rules on TaskController. Each route is called by every
 * kind of caller, and the expected status is checked. For every denied call it
 * also checks the service was never touched, so a deleted or loosened
 * annotation cannot slip through unnoticed.
 *
 * TaskService is mocked, so this tests security only, not task logic.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerSecurityTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestJwtKeys.register(registry);
        // The main properties use PostgreSQL, so tests point at H2.
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:tasks-controller;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    private static final String ROLE_USER = "ROLE_USER";
    private static final String ROLE_MANAGER = "ROLE_MANAGER";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    /** A valid token whose roles claim is empty. */
    private static final String NO_ROLES = "NO_ROLES";

    private static final Set<String> ADMIN_OR_MANAGER = Set.of(ROLE_ADMIN, ROLE_MANAGER);
    private static final Set<String> ANY_AUTHENTICATED = Set.of(ROLE_USER, ROLE_MANAGER, ROLE_ADMIN, NO_ROLES);

    private static final String TASK_JSON = """
            {"title":"Write tests","description":"Role rules","priority":"HIGH","completed":false,"userId":1}""";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TaskService taskService;

    @BeforeEach
    void stubService() {
        // Owned by the user id every test token carries, so ownership never interferes
        // here.
        // Ownership has its own test: TaskControllerOwnershipTest.
        Task sample = new Task("Write tests", "Role rules", TaskPriority.HIGH, false, TestJwtKeys.DEFAULT_UID);
        when(taskService.findAllTasks()).thenReturn(List.of(sample));
        when(taskService.findTaskById(anyLong())).thenReturn(sample);
        when(taskService.updateTask(anyLong(), any(Task.class))).thenReturn(sample);
    }

    private record Route(HttpMethod method, String path, String body, Set<String> allowed, int successStatus) {
    }

    static Stream<Arguments> matrix() {
        List<Route> routes = List.of(
                new Route(HttpMethod.GET, "/api/tasks", null, ADMIN_OR_MANAGER, 200),
                new Route(HttpMethod.GET, "/api/tasks/1", null, ANY_AUTHENTICATED, 200),
                // every test token carries uid 42: that is "their own" list, and 99 is someone
                // else's
                new Route(HttpMethod.GET, "/api/tasks/user/" + TestJwtKeys.DEFAULT_UID, null, ANY_AUTHENTICATED, 200),
                new Route(HttpMethod.GET, "/api/tasks/user/99", null, ANY_AUTHENTICATED, 200),
                new Route(HttpMethod.POST, "/api/tasks", TASK_JSON, ADMIN_OR_MANAGER, 201),
                new Route(HttpMethod.PUT, "/api/tasks/1", TASK_JSON, ADMIN_OR_MANAGER, 200),
                new Route(HttpMethod.PUT, "/api/tasks/1/complete", null, ANY_AUTHENTICATED, 200),
                new Route(HttpMethod.DELETE, "/api/tasks/1", null, ADMIN_OR_MANAGER, 200));

        // null means no Authorization header at all
        List<String> callers = Arrays.asList(ROLE_USER, ROLE_MANAGER, ROLE_ADMIN, NO_ROLES, null);

        return routes.stream().flatMap(route -> callers.stream().map(caller -> {
            int expected = caller == null ? 401 : route.allowed().contains(caller) ? route.successStatus() : 403;
            String label = route.method() + " " + route.path() + " as " + (caller == null ? "anonymous" : caller)
                    + " -> " + expected;
            return Arguments.of(label, route.method(), route.path(), route.body(), caller, expected);
        }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    void roleRules(String label, HttpMethod method, String path, String body, String caller, int expectedStatus)
            throws Exception {

        MockHttpServletRequestBuilder request = request(method, path);
        if (caller != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(caller));
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }

        mockMvc.perform(request).andExpect(status().is(expectedStatus));

        if (expectedStatus == 401 || expectedStatus == 403) {
            verifyNoInteractions(taskService);
        }
    }

    private static String tokenFor(String caller) {
        List<String> roles = NO_ROLES.equals(caller) ? List.of() : List.of(caller);
        return TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", roles));
    }
}