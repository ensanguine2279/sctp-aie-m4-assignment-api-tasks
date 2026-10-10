package sg.edu.ntu.taskflow_api.task.exception;

public class EntityNotFoundException extends RuntimeException {
    public EntityNotFoundException(String type, long id) {
        super(type + " with ID " + id + " not found");
    }
}
