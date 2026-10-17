package sg.edu.ntu.taskflow_api.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import sg.edu.ntu.taskflow_api.task.entity.Task;
import sg.edu.ntu.taskflow_api.task.entity.TaskPriority;

/**
 * Request body for creating a task.
 *
 * Deliberately has no {@code id}: the database generates it, and a client must
 * never be able to name one (a non-zero id would make save() overwrite that
 * task).
 *
 * {@code userId} is still supplied by the client for now. Deriving it from the
 * token needs the user id in the token, which is a users-service decision.
 *
 * {@code completed} is mandatory. It is a Boolean (wrapper), not a boolean, on
 * purpose:
 * Jackson rejects a request that omits a primitive record component before
 * validation
 * runs, which would surface as a generic 400 "Malformed request body". As a
 * wrapper,
 * an omitted value reaches @NotNull and the client gets "completed: Completed
 * status is mandatory".
 */
public record CreateTaskRequestDto(
        @NotBlank(message = "Title is mandatory") String title,
        String description,
        @NotNull(message = "Priority is mandatory") TaskPriority priority,
        @NotNull(message = "Completed status is mandatory") Boolean completed,
        @NotNull(message = "User is mandatory") @Positive(message = "User ID must be positive") Long userId) {

    /** Call only after validation: userId and completed are unboxed here. */
    public Task toEntity() {
        return new Task(title, description, priority, completed, userId);
    }
}