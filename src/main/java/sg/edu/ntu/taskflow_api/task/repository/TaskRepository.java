package sg.edu.ntu.taskflow_api.task.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sg.edu.ntu.taskflow_api.task.entity.Task;

public interface TaskRepository extends JpaRepository<Task, Long> {

}
