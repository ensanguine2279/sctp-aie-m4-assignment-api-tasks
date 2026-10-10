package sg.edu.ntu.taskflow_api.task.dto;

import java.time.Instant;

public record ErrorResponseDto(String message, Instant timestamp) {
}
