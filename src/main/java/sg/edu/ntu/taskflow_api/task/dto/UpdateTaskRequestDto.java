package sg.edu.ntu.taskflow_api.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import sg.edu.ntu.taskflow_api.task.entity.Task;
import sg.edu.ntu.taskflow_api.task.entity.TaskPriority;

/**
 * Request body for updating a task (PUT, a full replacement of these four
 * fields).
 *
 * Has no {@code id} and no {@code userId}: the id comes from the URL, and the
 * owner of a task cannot be changed by an update. TaskServiceImpl.updateTask
 * copies only the four fields below onto the stored task.
 *
 * {@code completed} is mandatory because PUT replaces all four fields. It is a
 * Boolean
 * (wrapper) so that an omitted value is reported by validation, not by Jackson
 * (see CreateTaskRequest).
 */
public record UpdateTaskRequestDto(
        @NotBlank(message = "Title is mandatory") String title,
        String description,
        @NotNull(message = "Priority is mandatory") TaskPriority priority,
        @NotNull(message = "Completed status is mandatory") Boolean completed) {

    /**
     * A Task carrying just the updatable fields, for TaskService.updateTask. Call
     * only after validation.
     */
    public Task toEntity() {
        return Task.builder()
                .title(title)
                .description(description)
                .priority(priority)
                .completed(completed)
                .build();
    }
}