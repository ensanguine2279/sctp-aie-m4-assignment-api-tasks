package sg.edu.ntu.taskflow_api.task.exception;

public class TaskNotFoundException extends EntityNotFoundException {
    public TaskNotFoundException(long id) {
        super("Task", id);
    }
}
