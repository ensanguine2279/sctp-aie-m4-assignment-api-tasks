package sg.edu.ntu.taskflow_api.task.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import sg.edu.ntu.taskflow_api.task.dto.CreateTaskRequestDto;
import sg.edu.ntu.taskflow_api.task.dto.UpdateTaskRequestDto;
import sg.edu.ntu.taskflow_api.task.entity.Task;
import sg.edu.ntu.taskflow_api.task.exception.TaskNotFoundException;
import sg.edu.ntu.taskflow_api.task.security.TaskAccessPolicy;
import sg.edu.ntu.taskflow_api.task.service.TaskService;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskAccessPolicy accessPolicy;

    public TaskController(TaskService taskService, TaskAccessPolicy accessPolicy) {
        this.taskService = taskService;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping("")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<List<Task>> getAllTasks() {
        return ResponseEntity.ok(taskService.findAllTasks());
    }

    /**
     * Any signed-in user, but a plain user only for their own task. The path holds
     * a TASK id,
     * so the owner has to be read from the task: see findAccessibleTask.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Task> getTaskById(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(findAccessibleTask(id, authentication));
    }

    @GetMapping("/user/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER') or @userSecurity.isSelf(#userId, authentication)")
    public ResponseEntity<List<Task>> getTasksByUserId(@PathVariable Long userId) {
        return ResponseEntity.ok(taskService.findTasksByUserId(userId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<Task> createTask(@Valid @RequestBody CreateTaskRequestDto request) {
        Task task = request.toEntity();
        taskService.createTask(task); // save() fills in the generated id on this instance
        return ResponseEntity.status(HttpStatus.CREATED).body(task);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<Task> updateTask(@PathVariable Long id, @Valid @RequestBody UpdateTaskRequestDto request) {
        return ResponseEntity.ok(taskService.updateTask(id, request.toEntity()));
    }

    @PutMapping("/{id}/complete")
    public ResponseEntity<Task> markTaskAsCompleted(@PathVariable Long id, Authentication authentication) {
        findAccessibleTask(id, authentication); // 404 unless the caller may access this task
        taskService.markTaskAsCompleted(id);
        return ResponseEntity.ok(taskService.findTaskById(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<Task> deleteTask(@PathVariable Long id) {
        Task deletedTask = taskService.findTaskById(id);
        taskService.deleteTask(id);
        return ResponseEntity.ok(deletedTask);
    }

    /**
     * Finds a task the caller may access. A task that belongs to someone else gets
     * the same TaskNotFoundException as one that does not exist, so a plain user
     * cannot probe for ids.
     */
    private Task findAccessibleTask(Long id, Authentication authentication) {
        Task task = taskService.findTaskById(id);
        if (!accessPolicy.canAccess(authentication, task)) {
            throw new TaskNotFoundException(id);
        }
        return task;
    }
}