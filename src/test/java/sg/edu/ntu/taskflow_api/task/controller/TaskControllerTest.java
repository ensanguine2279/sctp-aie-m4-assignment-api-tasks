package sg.edu.ntu.taskflow_api.task.controller;

import static org.hamcrest.Matchers.startsWith;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
// Spring Boot 4 package. On Boot 3 it was org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * What TaskController does with requests and responses: bodies, validation,
 * error mapping. TaskService is mocked, and every call is made as an admin,
 * because who may call what is covered by TaskControllerSecurityTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerTest {

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
                TestJwtKeys.register(registry);
                // The main properties use PostgreSQL, so tests point at H2.
                registry.add("spring.datasource.url", () -> "jdbc:h2:mem:tasks-controller-behaviour;DB_CLOSE_DELAY=-1");
                registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
                registry.add("spring.datasource.username", () -> "sa");
                registry.add("spring.datasource.password", () -> "");
                registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        }

        private static final String VALID_CREATE = """
                        {"title":"Write tests","description":"Role rules","priority":"HIGH","completed":false,"userId":1}""";
        private static final String VALID_UPDATE = """
                        {"title":"New title","description":"New description","priority":"LOW","completed":true}""";

        @Autowired
        MockMvc mockMvc;

        @MockitoBean
        TaskService taskService;

        private static String adminToken() {
                return "Bearer " + TestJwtKeys.sign(TestJwtKeys.baseBuilder("alice", List.of("ROLE_ADMIN")));
        }

        private static Task storedTask(long id) {
                Task task = new Task("Write tests", "Role rules", TaskPriority.HIGH, false, 1L);
                task.setId(id);
                return task;
        }

        // ---------- POST /api/tasks ----------

        @Test
        void createReturns201WithTheGeneratedIdAndPassesTheFieldsToTheService() throws Exception {
                doAnswer(invocation -> {
                        ((Task) invocation.getArgument(0)).setId(42); // what save() does
                        return null;
                }).when(taskService).createTask(any(Task.class));

                mockMvc.perform(post("/api/tasks").header(HttpHeaders.AUTHORIZATION, adminToken())
                                .contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.id").value(42))
                                .andExpect(jsonPath("$.title").value("Write tests"))
                                .andExpect(jsonPath("$.description").value("Role rules"))
                                .andExpect(jsonPath("$.priority").value("HIGH"))
                                .andExpect(jsonPath("$.completed").value(false))
                                .andExpect(jsonPath("$.userId").value(1));

                ArgumentCaptor<Task> sent = ArgumentCaptor.forClass(Task.class);
                verify(taskService).createTask(sent.capture());
                assertThat(sent.getValue().getTitle()).isEqualTo("Write tests");
                assertThat(sent.getValue().getPriority()).isEqualTo(TaskPriority.HIGH);
                assertThat(sent.getValue().getUserId()).isEqualTo(1L);
        }

        /*
        @Test
        void createReturns409WhenConstraintIsViolated() throws Exception {
                doThrow(new org.springframework.dao.DataIntegrityViolationException("Duplicate task title"))
                                .when(taskService).createTask(any(Task.class));

                mockMvc.perform(post("/api/tasks").header(HttpHeaders.AUTHORIZATION, adminToken())
                                .contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.message").exists());
        }
        */
       
        @ParameterizedTest(name = "create: invalid userId = {0}")
        @ValueSource(longs = { 0L, -1L })
        void createRejectsNonPositiveUserId(long invalidUserId) throws Exception {
                String body = String.format(
                                "{\"title\":\"t\",\"priority\":\"HIGH\",\"completed\":false,\"userId\":%d}",
                                invalidUserId);

                mockMvc.perform(post("/api/tasks").header(HttpHeaders.AUTHORIZATION, adminToken())
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("userId")));

                verifyNoInteractions(taskService);
        }

        /**
         * Regression: a client-supplied id must never reach save(), or POST would
         * overwrite that task.
         */
        @Test
        void createIgnoresAnIdSuppliedByTheClient() throws Exception {
                String withId = """
                                {"id":99,"title":"Write tests","priority":"HIGH","completed":false,"userId":1}""";

                mockMvc.perform(post("/api/tasks").header(HttpHeaders.AUTHORIZATION, adminToken())
                                .contentType(MediaType.APPLICATION_JSON).content(withId))
                                .andExpect(status().isCreated());

                ArgumentCaptor<Task> sent = ArgumentCaptor.forClass(Task.class);
                verify(taskService).createTask(sent.capture());
                assertThat(sent.getValue().getId()).isZero();
        }

        @Test
        void createAcceptsMissingDescription() throws Exception {
                String payloadWithoutDescription = """
                                {"title":"No description task","priority":"HIGH","completed":false,"userId":1}""";

                mockMvc.perform(post("/api/tasks").header(HttpHeaders.AUTHORIZATION, adminToken())
                                .contentType(MediaType.APPLICATION_JSON).content(payloadWithoutDescription))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.nullValue()));
        }

        @Test
        void createAcceptsCompletedTrue() throws Exception {
                String completedTrue = """
                                {"title":"Write tests","priority":"HIGH","completed":true,"userId":1}""";

                mockMvc.perform(post("/api/tasks").header(HttpHeaders.AUTHORIZATION, adminToken())
                                .contentType(MediaType.APPLICATION_JSON).content(completedTrue))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.completed").value(true));
        }

        static Stream<Arguments> invalidCreateBodies() {
                return Stream.of(
                                Arguments.of("missing title", """
                                                {"priority":"HIGH","completed":false,"userId":1}""",
                                                "title: Title is mandatory"),
                                Arguments.of("blank title", """
                                                {"title":"   ","priority":"HIGH","completed":false,"userId":1}""",
                                                "title: Title is mandatory"),
                                Arguments.of("missing priority", """
                                                {"title":"t","completed":false,"userId":1}""",
                                                "priority: Priority is mandatory"),
                                Arguments.of("missing completed", """
                                                {"title":"t","priority":"HIGH","userId":1}""",
                                                "completed: Completed status is mandatory"),
                                Arguments.of("completed is null", """
                                                {"title":"t","priority":"HIGH","completed":null,"userId":1}""",
                                                "completed: Completed status is mandatory"),
                                Arguments.of("missing userId", """
                                                {"title":"t","priority":"HIGH","completed":false}""",
                                                "userId: User is mandatory"),
                                Arguments.of("everything missing", "{}",
                                                "completed: Completed status is mandatory; priority: Priority is mandatory; "
                                                                + "title: Title is mandatory; userId: User is mandatory"),
                                Arguments.of("unknown priority", """
                                                {"title":"t","priority":"URGENT","completed":false,"userId":1}""",
                                                "Malformed request body"),
                                Arguments.of("completed is not a boolean", """
                                                {"title":"t","priority":"HIGH","completed":"maybe","userId":1}""",
                                                "Malformed request body"),
                                Arguments.of("userId is not a number", """
                                                {"title":"t","priority":"HIGH","completed":false,"userId":"abc"}""",
                                                "Malformed request body"),
                                Arguments.of("truncated JSON", "{\"title\":", "Malformed request body"),
                                Arguments.of("empty body", "", "Malformed request body"));
        }

        @ParameterizedTest(name = "create: {0}")
        @MethodSource("invalidCreateBodies")
        void createRejectsInvalidBodiesWith400(String label, String body, String expectedMessage) throws Exception {
                mockMvc.perform(post("/api/tasks").header(HttpHeaders.AUTHORIZATION, adminToken())
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message").value(expectedMessage))
                                .andExpect(jsonPath("$.timestamp").exists());

                verifyNoInteractions(taskService);
        }

        @Test
        void createWithAnUnsupportedContentTypeIs415NotAn500() throws Exception {
                mockMvc.perform(post("/api/tasks").header(HttpHeaders.AUTHORIZATION, adminToken())
                                .contentType(MediaType.TEXT_PLAIN).content(VALID_CREATE))
                                .andExpect(status().isUnsupportedMediaType())
                                .andExpect(jsonPath("$.message").value("Unsupported Media Type"));

                verifyNoInteractions(taskService);
        }

        // ---------- PUT /api/tasks/{id} ----------

        @Test
        void updateReturnsTheUpdatedTaskAndPassesOnlyTheEditableFields() throws Exception {
                Task updated = storedTask(1);
                updated.setTitle("New title");
                when(taskService.updateTask(eq(1L), any(Task.class))).thenReturn(updated);

                mockMvc.perform(put("/api/tasks/1").header(HttpHeaders.AUTHORIZATION, adminToken())
                                .contentType(MediaType.APPLICATION_JSON).content(VALID_UPDATE))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(1))
                                .andExpect(jsonPath("$.title").value("New title"));

                ArgumentCaptor<Task> sent = ArgumentCaptor.forClass(Task.class);
                verify(taskService).updateTask(eq(1L), sent.capture());
                assertThat(sent.getValue().getTitle()).isEqualTo("New title");
                assertThat(sent.getValue().getDescription()).isEqualTo("New description");
                assertThat(sent.getValue().getPriority()).isEqualTo(TaskPriority.LOW);
                assertThat(sent.getValue().isCompleted()).isTrue();
        }

        /** Regression: a body cannot move a task to another owner or change its id. */
        @Test
        void updateIgnoresAnIdOrUserIdSuppliedByTheClient() throws Exception {
                when(taskService.updateTask(eq(1L), any(Task.class))).thenReturn(storedTask(1));
                String sneaky = """
                                {"id":77,"userId":99,"title":"t","priority":"LOW","completed":false}""";

                mockMvc.perform(put("/api/tasks/1").header(HttpHeaders.AUTHORIZATION, adminToken())
                                .contentType(MediaType.APPLICATION_JSON).content(sneaky))
                                .andExpect(status().isOk());

                ArgumentCaptor<Task> sent = ArgumentCaptor.forClass(Task.class);
                verify(taskService).updateTask(eq(1L), sent.capture());
                assertThat(sent.getValue().getId()).isZero();
                assertThat(sent.getValue().getUserId()).isNull();
        }

        static Stream<Arguments> invalidUpdateBodies() {
                return Stream.of(
                                Arguments.of("missing title", """
                                                {"priority":"LOW","completed":false}""", "title: Title is mandatory"),
                                Arguments.of("blank title", """
                                                {"title":"","priority":"LOW","completed":false}""",
                                                "title: Title is mandatory"),
                                Arguments.of("missing priority", """
                                                {"title":"t","completed":false}""", "priority: Priority is mandatory"),
                                Arguments.of("missing completed", """
                                                {"title":"t","priority":"LOW"}""",
                                                "completed: Completed status is mandatory"),
                                Arguments.of("completed is null", """
                                                {"title":"t","priority":"LOW","completed":null}""",
                                                "completed: Completed status is mandatory"),
                                Arguments.of("everything missing", "{}",
                                                "completed: Completed status is mandatory; priority: Priority is mandatory; "
                                                                + "title: Title is mandatory"),
                                Arguments.of("unknown priority", """
                                                {"title":"t","priority":"URGENT","completed":false}""",
                                                "Malformed request body"),
                                Arguments.of("completed is not a boolean", """
                                                {"title":"t","priority":"LOW","completed":"maybe"}""",
                                                "Malformed request body"),
                                Arguments.of("truncated JSON", "{\"title\":", "Malformed request body"));
        }

        @ParameterizedTest(name = "update: {0}")
        @MethodSource("invalidUpdateBodies")
        void updateRejectsInvalidBodiesWith400(String label, String body, String expectedMessage) throws Exception {
                mockMvc.perform(put("/api/tasks/1").header(HttpHeaders.AUTHORIZATION, adminToken())
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message").value(expectedMessage));

                verifyNoInteractions(taskService);
        }

        @Test
        void updateOfAMissingTaskIs404() throws Exception {
                when(taskService.updateTask(eq(99L), any(Task.class))).thenThrow(new TaskNotFoundException(99L));

                mockMvc.perform(put("/api/tasks/99").header(HttpHeaders.AUTHORIZATION, adminToken())
                                .contentType(MediaType.APPLICATION_JSON).content(VALID_UPDATE))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.message").exists())
                                .andExpect(jsonPath("$.timestamp").exists());
        }

        // ---------- GET ----------

        @Test
        void getByIdReturnsTheTaskWithFieldsInTheDocumentedOrder() throws Exception {
                when(taskService.findTaskById(7L)).thenReturn(storedTask(7));

                mockMvc.perform(get("/api/tasks/7").header(HttpHeaders.AUTHORIZATION, adminToken()))
                                .andExpect(status().isOk())
                                .andExpect(content().string(startsWith(
                                                "{\"id\":7,\"title\":\"Write tests\",\"description\":\"Role rules\","
                                                                + "\"priority\":\"HIGH\",\"completed\":false")));
        }

        @Test
        void getByIdOfAMissingTaskIs404InTheErrorFormat() throws Exception {
                when(taskService.findTaskById(99L)).thenThrow(new TaskNotFoundException(99L));

                mockMvc.perform(get("/api/tasks/99").header(HttpHeaders.AUTHORIZATION, adminToken()))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.message").exists())
                                .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        void getByIdWithANonNumericIdIs400() throws Exception {
                mockMvc.perform(get("/api/tasks/abc").header(HttpHeaders.AUTHORIZATION, adminToken()))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message").value("Invalid value for 'id'"));

                verifyNoInteractions(taskService);
        }

        @Test
        void getAllReturnsTheList() throws Exception {
                when(taskService.findAllTasks()).thenReturn(List.of(storedTask(1), storedTask(2)));

                mockMvc.perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, adminToken()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.length()").value(2))
                                .andExpect(jsonPath("$[0].id").value(1))
                                .andExpect(jsonPath("$[1].id").value(2));
        }

        // ---------- PUT /api/tasks/{id}/complete ----------

        @Test
        void completeMarksTheTaskAndReturnsIt() throws Exception {
                Task done = storedTask(1);
                done.setCompleted(true);
                when(taskService.findTaskById(1L)).thenReturn(done);

                mockMvc.perform(put("/api/tasks/1/complete").header(HttpHeaders.AUTHORIZATION, adminToken()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.completed").value(true));

                verify(taskService).markTaskAsCompleted(1L);
        }

        @Test
        void completeOfAMissingTaskIs404() throws Exception {
                doThrow(new TaskNotFoundException(99L)).when(taskService).markTaskAsCompleted(99L);

                mockMvc.perform(put("/api/tasks/99/complete").header(HttpHeaders.AUTHORIZATION, adminToken()))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.message").exists());
        }

        // ---------- DELETE ----------

        @Test
        void deleteReturnsTheDeletedTaskAndDeletesIt() throws Exception {
                when(taskService.findTaskById(3L)).thenReturn(storedTask(3));

                mockMvc.perform(delete("/api/tasks/3").header(HttpHeaders.AUTHORIZATION, adminToken()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(3));

                verify(taskService).deleteTask(3L);
        }

        @Test
        void deleteOfAMissingTaskIs404AndDeletesNothing() throws Exception {
                when(taskService.findTaskById(99L)).thenThrow(new TaskNotFoundException(99L));

                mockMvc.perform(delete("/api/tasks/99").header(HttpHeaders.AUTHORIZATION, adminToken()))
                                .andExpect(status().isNotFound());

                verify(taskService, never()).deleteTask(99L);
        }
}