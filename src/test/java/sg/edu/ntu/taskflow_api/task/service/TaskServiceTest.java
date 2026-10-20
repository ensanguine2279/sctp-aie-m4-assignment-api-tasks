package sg.edu.ntu.taskflow_api.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sg.edu.ntu.taskflow_api.task.entity.Task;
import sg.edu.ntu.taskflow_api.task.entity.TaskPriority;
import sg.edu.ntu.taskflow_api.task.exception.TaskNotFoundException;
import sg.edu.ntu.taskflow_api.task.repository.TaskRepository;

/** Plain Mockito test of the task logic. No Spring context, no database. */
@ExtendWith(MockitoExtension.class)
class TaskServiceImplTest {

    @Mock
    TaskRepository taskRepository;

    private TaskServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TaskServiceImpl(taskRepository);
    }

    private static Task storedTask(long id, long userId) {
        Task task = new Task("Old title", "Old description", TaskPriority.LOW, false, userId);
        task.setId(id);
        return task;
    }

    // ---------- find ----------

    @Test
    void findAllTasksReturnsWhatTheRepositoryHolds() {
        List<Task> tasks = List.of(storedTask(1, 10), storedTask(2, 10));
        when(taskRepository.findAll()).thenReturn(tasks);

        assertThat(service.findAllTasks()).isEqualTo(tasks);
    }

    @Test
    void findTaskByIdReturnsTheTask() {
        Task task = storedTask(5, 10);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(task));

        assertThat(service.findTaskById(5)).isSameAs(task);
    }

    @Test
    void findTaskByIdThrowsWhenMissing() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findTaskById(99)).isInstanceOf(TaskNotFoundException.class);
    }

    // ---------- create ----------

    @Test
    void createTaskSavesTheTask() {
        Task task = new Task("New", "Desc", TaskPriority.HIGH, false, 10L);

        service.createTask(task);

        verify(taskRepository).save(task);
    }

    // ---------- complete ----------

    @Test
    void markTaskAsCompletedSetsTheFlagAndSaves() {
        Task task = storedTask(5, 10);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(task));

        service.markTaskAsCompleted(5);

        assertThat(task.isCompleted()).isTrue();
        verify(taskRepository).save(task);
    }

    @Test
    void markTaskAsCompletedThrowsWhenMissingAndSavesNothing() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markTaskAsCompleted(99)).isInstanceOf(TaskNotFoundException.class);
        verify(taskRepository, never()).save(any());
    }

    // ---------- update ----------

    @Test
    void updateTaskCopiesEditableFieldsIncludingUserId() {
        Task existing = storedTask(5, 10L);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));

        Task changes = Task.builder()
                .title("New title")
                .description("New description")
                .priority(TaskPriority.HIGH)
                .completed(true)
                .userId(99L)
                .build();

        Task result = service.updateTask(5, changes);

        assertThat(result).isSameAs(existing);
        assertThat(result.getTitle()).isEqualTo("New title");
        assertThat(result.getDescription()).isEqualTo("New description");
        assertThat(result.getPriority()).isEqualTo(TaskPriority.HIGH);
        assertThat(result.isCompleted()).isTrue();
        assertThat(result.getUserId()).isEqualTo(99L); // Asserts userId is updated
        verify(taskRepository).save(existing);
    }

    @Test
    void updateTaskChangesOwnerButNeverChangesId() {
        Task existing = storedTask(5, 10L);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));

        Task changes = new Task("New title", "New description", TaskPriority.HIGH, true, 99L);
        changes.setId(77); // Attempting to alter ID to 77

        Task result = service.updateTask(5, changes);

        assertThat(result.getId()).isEqualTo(5); // ID remains locked to 5
        assertThat(result.getUserId()).isEqualTo(99L); // Owner successfully updated to 99
    }

    @Test
    void updateTaskThrowsWhenMissingAndSavesNothing() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());
        Task changes = Task.builder().title("x").priority(TaskPriority.LOW).build();

        assertThatThrownBy(() -> service.updateTask(99, changes)).isInstanceOf(TaskNotFoundException.class);
        verify(taskRepository, never()).save(any());
    }

    // ---------- delete ----------

    @Test
    void deleteTaskDeletesWhenItExists() {
        when(taskRepository.existsById(5L)).thenReturn(true);

        service.deleteTask(5);

        verify(taskRepository).deleteById(5L);
    }

    @Test
    void deleteTaskThrowsWhenMissingAndDeletesNothing() {
        when(taskRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.deleteTask(99)).isInstanceOf(TaskNotFoundException.class);
        verify(taskRepository, never()).deleteById(anyLong());
    }
}