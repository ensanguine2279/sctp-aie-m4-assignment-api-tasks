# Module 3 Assignment - Task Management API

## About the assignment

The assignment is to build a simple TaskFlow API, a task management layered backend, using Spring Boot project,  and push it to [GitHub](https://github.com/ensanguine2279/sctp-aie-m3-assignment/tree/main).

Click [here](./Module3_Assignment_mandatory.md) for the assignment details & requirements.

## API documentation

|**Method**|**Path**|**What it does**|
|---|---|---|
|GET|`/api/tasks`|Get all tasks|
|GET|`/api/tasks/{id}`|Get one task by id|
|GET|`/api/tasks/summarize`|Summarize all tasks|
|POST|`/api/tasks`|Create a new task|
|PUT|`/api/tasks/{id}`|Update an existng task|
|PUT|`/api/tasks/{id}/`<br>`complete`|Mark a task as complete|
|DELETE|`/api/tasks/{id}`|Delete a task|


Click [here](./SCTP%20AIE%20M3%20-%20Assignment.postman_collection.json) for the Postman collection of the API endpoints. 

You can download the collection and import it into Postman for testing.

<br>

![Postman import button](./src/main/resources/static/images/postman-import.png)

<br>

<img src="./src/main/resources/static/images/postman-import-select-file.png" style="width: 50%; height: auto;" alt="Postman import select file">

## Testing APIs

The endpoints have been deployed **live** on [Render](https://render.com/) free tier. 

You can test the APIs from the base url https://sctp-aie-m3-assignment.onrender.com/api/tasks.  

> **Postman Collection Variable**: The base url is defined in the collection variable `{{url}}` in the postman collection. If you were to deploy the APIs into another local/cloud environment/platform, you will need to update the value of the `{{url}}` variable

![Postman collection variable - url](./src/main/resources/static/images/postman-collection-variable.png)

> **Free Tier Inactivity**: Note that Render spins down free-tier web services after 15 minutes of inactivity. A new incoming request will experience a "cold start" delay of about 30–50 seconds while the instance wakes back up.

## Configuring the APIs

### LLM model settings

You can configure the model used for summarizing tasks and the associated temperature hyperparameter using the following settings in the [application.properties](./src/main/resources/application.properties).

> **Never expose your API key**: You should not hardcode your API key in the `application.properties`. Always inject the key as an environment variable in the hosting environment.  Spring Boot will derive the value of `${OPENAI_API_KEY}` and set the property during runtime.

``` yaml
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.chat.model=gpt-4o-mini
spring.ai.openai.chat.temperature=0.7
```

### System prompt settings

Refine and update the components of the system prompt with these four properties (in [application.properties](./src/main/resources/application.properties)) to enhance responses from the model to deliver a precise, targeted summary.

- Role: Define who or what persona the AI should embody (e.g., "Act as a senior software architect").

- Scope: Define the boundaries, domain, and limitations of what the AI is allowed to address.

- Tone: Define the persona, voice, attitude, and emotional resonance of the AI's output. It dictates how the message feels to the reader, ensuring the delivery matches the intended audience and context.

- Boundaries/Constraints: Establish the rules, limits, or formatting requirements (e.g., "Keep the response under 200 words and use a bulleted list").

``` yaml
spring.ai.taskflow.sysprompt.role=You are an expert task management... 
spring.ai.taskflow.sysprompt.scope=You help generate a plain-English...
spring.ai.taskflow.sysprompt.tone=Maintain a helpful, analytical...
spring.ai.taskflow.sysprompt.boundaries=Do not invent task details...
```

### Network port settings

This property sets the exact network port the Spring Boot application listens on. 
- `${PORT}` tells Spring Boot to check for the environment variable `PORT` injected by the hosting platform at startup and use it.
-  `:10000` is the fallback default value. If no `PORT` variable is found it defaults to port 10000 (or whichever port specified).

> Render's load balancer is configured to route external incoming requests from ports 80/443 to the internal container port of 10000.  This is the reason why this setting is necessary for the Spring Boot application to be accessible from public. 

``` yaml
server.port=${PORT:10000}
```

Should you decide to deploy on another cloud host other than Render, the port settings will be different.  Consult the provider's documentation for the correct porting number to use.

## Deploying the APIs (on Render)

The [Dockerfile](./Dockerfile) is essential in the deployment process on Render.  It defines a multi-stage Docker build designed to compile the Spring Boot project and package it into a lightweight, secure production image.

### Stage 1: The Build Environment

`FROM eclipse-temurin:21-jdk-alpine AS build`: Pulls a lightweight Alpine Linux image containing the Java 21 JDK (Java Development Kit, which includes the compiler needed to build code) and names this first stage build.

`WORKDIR /app`: Sets the working directory inside the container to /app.

`COPY . .`: Copies all your project source files and code from your computer into the container's /app folder.

`RUN ./mvnw clean package -DskipTests`: Uses the Maven wrapper to compile your code and build the executable JAR file. The -DskipTests flag skips running unit tests to speed up the container build process.

### Stage 2: The Production Runtime
`FROM eclipse-temurin:21-jre-alpine`: Starts a completely fresh, minimal image using only the Java 21 JRE (Java Runtime Environment). Because it doesn't need compiler tools, this image is much smaller and more secure for production.

`WORKDIR /app`: Sets the working directory in this final runtime image to /app.

`COPY --from=build /app/target/*.jar app.jar`: Grabs only the compiled .jar file from the first build stage and copies it into this clean runtime image, naming it app.jar.

`EXPOSE 10000`: Exposes that the containerized application on port 10000.

`ENTRYPOINT ["java", "-jar", "app.jar"]`: Defines the default command that runs when the container starts up, launching the Spring Boot app.

## Architecture Overview

- **Controller Layer** (`sg.edu.ntu.taskflow_api.controller`): `TaskController` handles incoming HTTP requests, delegating core business logic execution to the service layer.

- **Service Layer** (`sg.edu.ntu.taskflow_api.service`): Contains `TaskService` and `AiService` interfaces alongside their implementations (TaskServiceImpl, AiServiceImpl) to manage task rules and external AI interactions.

- **Repository Layer** (`sg.edu.ntu.taskflow_api.repository`): `TaskRepository` simulates a Spring Data JPA repository to provide CRUD operations for the `Task` entity (using a `HashMap`).

- **Model Layer** (`sg.edu.ntu.taskflow_api.model`): Consists of the `Task` class (acting as a JPA entity) and the `TaskPriority` enumeration.

- **Exception Handling** (`sg.edu.ntu.taskflow_api.exception`): Custom runtime exceptions such as `TaskNotFoundException` handle invalid identifier lookups uniformly across controllers.

### Class Diagram

```mermaid
classDiagram
    direction TB

    class TaskflowApiApplication {
        +main(String[] args)
    }

    class TaskController {
        -TaskService taskService
        -AiService aiService
        +getAllTasks()
        +getTaskById(Long id)
        +createTask(Task task)
        +updateTask(Long id, Task task)
        +deleteTask(Long id)
    }

    class TaskService {
        <<interface>>
        +getAllTasks()
        +getTaskById(Long id)
        +createTask(Task task)
        +updateTask(Long id, Task task)
        +deleteTask(Long id)
    }

    class TaskServiceImpl {
        -TaskRepository taskRepository
        +getAllTasks()
        +getTaskById(Long id)
        +createTask(Task task)
        +updateTask(Long id, Task task)
        +deleteTask(Long id)
    }

    class AiService {
        <<interface>>
    }

    class AiServiceImpl {
        -RestTemplate restTemplate / WebClient
    }

    class TaskRepository {
        <<interface>>
    }

    class Task {
        -Long id
        -String title
        -String description
        -TaskPriority priority
        -Boolean completed
    }

    class TaskPriority {
        <<enumeration>>
        LOW
        MEDIUM
        HIGH
    }

    class TaskNotFoundException {
        +TaskNotFoundException(String message)
    }

    TaskController --> TaskService : injects
    TaskController --> AiService : injects
    TaskService <|.. TaskServiceImpl : implements
    AiService <|.. AiServiceImpl : implements
    TaskServiceImpl --> TaskRepository : injects
    TaskRepository --> Task : manages entity
    Task --> TaskPriority : uses
    TaskServiceImpl ..> TaskNotFoundException : throws