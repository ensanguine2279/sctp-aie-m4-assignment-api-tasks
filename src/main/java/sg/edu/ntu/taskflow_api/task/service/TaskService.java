package sg.edu.ntu.taskflow_api.task.service;

import java.util.List;

import sg.edu.ntu.taskflow_api.task.entity.Task;
import sg.edu.ntu.taskflow_api.task.exception.TaskNotFoundException;

public interface TaskService {
    List<Task> findAllTasks();

    List<Task> findTasksByUserId(long userId);

    Task findTaskById(long id) throws TaskNotFoundException;

    void createTask(Task task);

    void markTaskAsCompleted(long id) throws TaskNotFoundException;

    Task updateTask(long id, Task task) throws TaskNotFoundException;

    void deleteTask(long id) throws TaskNotFoundException;
}
