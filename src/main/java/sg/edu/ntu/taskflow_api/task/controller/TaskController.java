package sg.edu.ntu.taskflow_api.task.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import sg.edu.ntu.taskflow_api.task.service.TaskService;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<List<Task>> getAllTasks() {
        return ResponseEntity.ok(taskService.findAllTasks());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Task> getTaskById(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.findTaskById(id));
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
    public ResponseEntity<Task> markTaskAsCompleted(@PathVariable Long id) {
        taskService.markTaskAsCompleted(id);
        return ResponseEntity.ok(taskService.findTaskById(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ResponseEntity<Task> deleteTask(@PathVariable Long id) {
        Task deletedTask = taskService.findTaskById(id);
        taskService.deleteTask(id);
        return ResponseEntity.ok(deletedTask);
    }
}