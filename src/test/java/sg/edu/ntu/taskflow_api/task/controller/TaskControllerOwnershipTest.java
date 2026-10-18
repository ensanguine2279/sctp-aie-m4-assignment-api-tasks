package sg.edu.ntu.taskflow_api.task.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
// Spring Boot 4 package. On Boot 3 it was org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import sg.edu.ntu.taskflow_api.task.entity.Task;
import sg.edu.ntu.taskflow_api.task.entity.TaskPriority;
import sg.edu.ntu.taskflow_api.task.exception.TaskNotFoundException;
import sg.edu.ntu.taskflow_api.task.security.TestJwtKeys;
import sg.edu.ntu.taskflow_api.task.service.TaskService;

/**
 * Ownership on the two routes open to any signed-in user: reading a task and
 * completing it.
 * A task owned by someone else is a 404, exactly like one that does not exist.
 * TaskService is mocked, so a "nothing changed" check is "the service was never
 * asked to change it".
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerOwnershipTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestJwtKeys.register(registry);
        // The main properties use PostgreSQL, so tests point at H2.
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:tasks-ownership;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    private static final long OWNER_UID = TestJwtKeys.DEFAULT_UID; // 42
    private static final long OTHER_UID = 99L;
    private static final long TASK_ID = 5L;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TaskService taskService;

    @BeforeEach
    void taskOwnedByUser42() {
        Task task = new Task("Mine", "Owned by user 42", TaskPriority.HIGH, false, OWNER_UID);
        task.setId(TASK_ID);
        when(taskService.findTaskById(TASK_ID)).thenReturn(task);
    }

    private static String token(List<String> roles, long uid) {
        return "Bearer " + TestJwtKeys.sign(TestJwtKeys.baseBuilder("someone", roles, uid));
    }

    /** label, roles, the caller's uid, expected status */
    static Stream<Arguments> callers() {
        return Stream.of(
                Arguments.of("owner (ROLE_USER)", List.of("ROLE_USER"), OWNER_UID, 200),
                Arguments.of("owner (no roles)", List.<String>of(), OWNER_UID, 200),
                Arguments.of("another user (ROLE_USER)", List.of("ROLE_USER"), OTHER_UID, 404),
                Arguments.of("another user (no roles)", List.<String>of(), OTHER_UID, 404),
                Arguments.of("a manager who is not the owner", List.of("ROLE_MANAGER"), OTHER_UID, 200),
                Arguments.of("an admin who is not the owner", List.of("ROLE_ADMIN"), OTHER_UID, 200));
    }

    @ParameterizedTest(name = "GET /api/tasks/5 as {0} -> {3}")
    @MethodSource("callers")
    void readingATask(String label, List<String> roles, long uid, int expectedStatus) throws Exception {
        mockMvc.perform(get("/api/tasks/" + TASK_ID).header(HttpHeaders.AUTHORIZATION, token(roles, uid)))
                .andExpect(status().is(expectedStatus));
    }

    @ParameterizedTest(name = "PUT /api/tasks/5/complete as {0} -> {3}")
    @MethodSource("callers")
    void completingATask(String label, List<String> roles, long uid, int expectedStatus) throws Exception {
        mockMvc.perform(put("/api/tasks/" + TASK_ID + "/complete").header(HttpHeaders.AUTHORIZATION, token(roles, uid)))
                .andExpect(status().is(expectedStatus));

        if (expectedStatus == 200) {
            verify(taskService).markTaskAsCompleted(TASK_ID);
        } else {
            // The important part: a refused caller must not change anything.
            verify(taskService, never()).markTaskAsCompleted(anyLong());
        }
    }

    @Test
    void anotherUsersTaskLooksExactlyLikeAMissingOne() throws Exception {
        String notOwned = mockMvc.perform(get("/api/tasks/" + TASK_ID)
                .header(HttpHeaders.AUTHORIZATION, token(List.of("ROLE_USER"), OTHER_UID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn().getResponse().getContentAsString();
        String notOwnedMessage = com.jayway.jsonpath.JsonPath.read(notOwned, "$.message");

        // Now the same id genuinely does not exist.
        when(taskService.findTaskById(TASK_ID)).thenThrow(new TaskNotFoundException(TASK_ID));
        String missing = mockMvc.perform(get("/api/tasks/" + TASK_ID)
                .header(HttpHeaders.AUTHORIZATION, token(List.of("ROLE_USER"), OTHER_UID)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String missingMessage = com.jayway.jsonpath.JsonPath.read(missing, "$.message");

        assertThat(notOwnedMessage).isEqualTo(missingMessage);
    }

    // ---------- regression: a task id is not a user id ----------

    /**
     * The caller's uid equals the TASK id (5), but they are not the owner (42).
     * They must not get in.
     */
    @Test
    void aUserIdThatEqualsTheTaskIdGivesNoAccess() throws Exception {
        String callerWithUid5 = token(List.of("ROLE_USER"), TASK_ID);

        mockMvc.perform(get("/api/tasks/" + TASK_ID).header(HttpHeaders.AUTHORIZATION, callerWithUid5))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/tasks/" + TASK_ID + "/complete").header(HttpHeaders.AUTHORIZATION, callerWithUid5))
                .andExpect(status().isNotFound());

        verify(taskService, never()).markTaskAsCompleted(anyLong());
    }

    /**
     * The owner (42) must be able to read their task even though its id (7) is not
     * 42.
     */
    @Test
    void anOwnerCanReadATaskWhoseIdDiffersFromTheirUserId() throws Exception {
        Task task = new Task("Also mine", "Owned by user 42", TaskPriority.LOW, false, OWNER_UID);
        task.setId(7L);
        when(taskService.findTaskById(7L)).thenReturn(task);

        mockMvc.perform(get("/api/tasks/7").header(HttpHeaders.AUTHORIZATION, token(List.of("ROLE_USER"), OWNER_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
    }

    // ---------- GET /api/tasks/user/{userId} ----------

    /** label, roles, the caller's uid, the userId in the URL, expected status */
    static Stream<Arguments> listCallers() {
        return Stream.of(
                Arguments.of("a user listing their own tasks", List.of("ROLE_USER"), OWNER_UID, OWNER_UID, 200),
                Arguments.of("a user with no roles listing their own tasks", List.<String>of(), OWNER_UID, OWNER_UID,
                        200),
                Arguments.of("a user listing someone else's tasks", List.of("ROLE_USER"), OWNER_UID, OTHER_UID, 200),
                Arguments.of("a manager listing anyone's tasks", List.of("ROLE_MANAGER"), OTHER_UID, OWNER_UID, 200),
                Arguments.of("an admin listing anyone's tasks", List.of("ROLE_ADMIN"), OTHER_UID, OWNER_UID, 200));
    }

    @ParameterizedTest(name = "GET /api/tasks/user/{3} as {0} -> {4}")
    @MethodSource("listCallers")
    void listingTasksByUser(String label, List<String> roles, long uid, long requestedUserId, int expectedStatus)
            throws Exception {
        mockMvc.perform(get("/api/tasks/user/" + requestedUserId).header(HttpHeaders.AUTHORIZATION, token(roles, uid)))
                .andExpect(status().is(expectedStatus));

        boolean canAccess = (uid == requestedUserId) || roles.contains("ROLE_MANAGER") || roles.contains("ROLE_ADMIN");
        if (canAccess) {
            verify(taskService).findTasksByUserId(requestedUserId);
        } else {
            verify(taskService, never()).findTasksByUserId(anyLong());
        }
    }

    @Test
    void theListContainsTheOwnersTasks() throws Exception {
        Task task = new Task("Mine", "Owned by user 42", TaskPriority.HIGH, false, OWNER_UID);
        task.setId(TASK_ID);
        when(taskService.findTasksByUserId(OWNER_UID)).thenReturn(List.of(task));

        mockMvc.perform(get("/api/tasks/user/" + OWNER_UID)
                .header(HttpHeaders.AUTHORIZATION, token(List.of("ROLE_USER"), OWNER_UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(TASK_ID));
    }
}