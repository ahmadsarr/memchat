package org.iaproject.memchat.tui;

import java.util.List;
import java.util.Objects;

/** Immutable view state used by the terminal renderer and unit tests. */
public record TuiState(
        List<TranscriptEntry> transcript,
        List<ToolActivity> activities,
        String model,
        int step,
        Usage usage,
        Status status,
        String input,
        String notice
) {
    public TuiState {
        transcript = List.copyOf(transcript == null ? List.of() : transcript);
        activities = List.copyOf(activities == null ? List.of() : activities);
        model = model == null ? "unknown" : model;
        status = status == null ? Status.IDLE : status;
        input = input == null ? "" : input;
    }

    public static TuiState initial(String model) {
        return new TuiState(List.of(), List.of(), model, 0, null,
                Status.IDLE, "", null);
    }

    public TuiState withInput(String value) {
        return new TuiState(transcript, activities, model, step, usage, status, value, notice);
    }

    public TuiState withNotice(String value) {
        return new TuiState(transcript, activities, model, step, usage, status, input, value);
    }

    public TuiState withModel(String value) {
        return new TuiState(transcript, activities, value, step, usage, status, input, notice);
    }

    public enum Status { IDLE, THINKING, TOOL, ERROR }

    public record TranscriptEntry(Role role, String text) {
        public TranscriptEntry {
            Objects.requireNonNull(role);
            text = text == null ? "" : text;
        }
    }

    public enum Role { USER, ASSISTANT, SYSTEM, ERROR }

    public record ToolActivity(String name, String arguments, ToolStatus status,
                               long durationMillis, String result, int step) {
        public ToolActivity {
            name = name == null ? "unknown" : name;
            arguments = arguments == null ? "{}" : arguments;
            status = status == null ? ToolStatus.RUNNING : status;
            result = result == null ? "" : result;
        }
    }

    public enum ToolStatus { RUNNING, OK, ERROR }

    public record Usage(long promptTokens, long completionTokens) {
    }
}
