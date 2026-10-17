package sg.edu.ntu.taskflow_api.task.config;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import sg.edu.ntu.taskflow_api.task.entity.Task;
import sg.edu.ntu.taskflow_api.task.entity.TaskPriority;
import sg.edu.ntu.taskflow_api.task.repository.TaskRepository;

@Profile("dev")
@Component
public class DataLoader {
        private final TaskRepository taskRepository;

        public DataLoader(TaskRepository taskRepository) {
                this.taskRepository = taskRepository;
        }

        @PostConstruct
        public void loadData() {
                // Clear the database first
                taskRepository.deleteAll();

                // Load sample tasks
                Task task1 = new Task(
                                "Refactor task manager service",
                                "Team has decide to code to an interface for the service component.",
                                TaskPriority.MEDIUM, false, 2);
                Task task2 = new Task(
                                "Resolve bug in authentication module",
                                "Unexpected error occurs when user tries to login with valid credentials.",
                                TaskPriority.HIGH, false, 2);
                Task task3 = new Task(
                                "Update project documentation",
                                "Ensure that the project documentation is up-to-date with the latest changes.",
                                TaskPriority.LOW, false, 3);
                Task task4 = new Task(
                                "Backlog grooming",
                                "Review and prioritize tasks in the backlog for the upcoming sprint.",
                                TaskPriority.MEDIUM, false, 4);
                Task task5 = new Task(
                                "Migrate database to new server",
                                "Move the existing database to a new server for better performance and scalability.",
                                TaskPriority.HIGH, false, 4);

                List<Task> tasks = List.of(task1, task2, task3, task4, task5);
                tasks.forEach(taskRepository::save);
        }
}
