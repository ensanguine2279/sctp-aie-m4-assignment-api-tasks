package sg.edu.ntu.taskflow_api.task;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import sg.edu.ntu.taskflow_api.task.security.TestJwtKeys;

@SpringBootTest
class TaskflowApiApplicationTests {

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		TestJwtKeys.register(registry);
		// Ensure database properties point to in-memory H2 if Postgres isn't running in
		// CI
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:tasks-context-test;DB_CLOSE_DELAY=-1");
		registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
		registry.add("spring.datasource.username", () -> "sa");
		registry.add("spring.datasource.password", () -> "");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
	}

	@Test
	void contextLoads() {
	}
}