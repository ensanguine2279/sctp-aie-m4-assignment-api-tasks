package sg.edu.ntu.taskflow_api.task.entity;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Builder
@JsonPropertyOrder({ "id", "title", "description", "priority", "completed" })
@Entity
@Table(name = "tasks")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "title", nullable = false)
    @NotBlank(message = "Title is mandatory")
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "priority", nullable = false)
    @Enumerated(EnumType.STRING)
    @NotNull(message = "Priority is mandatory")
    private TaskPriority priority;

    @Column(name = "completed", nullable = false)
    @NotNull(message = "Completed status is mandatory")
    private boolean completed;

    @Column(name = "user_id", nullable = false)
    @NotNull(message = "User is mandatory")
    @Positive(message = "User ID must be positive")
    private Long userId;

    // Constructor for creating a task with a unique ID generated automatically
    public Task(String title, String description, TaskPriority priority, boolean completed, long userId) {
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.completed = completed;
        this.userId = userId;
    }
}
