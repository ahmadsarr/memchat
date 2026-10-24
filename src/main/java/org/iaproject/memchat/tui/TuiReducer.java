package org.iaproject.memchat.tui;

import org.iaproject.memchat.agent.AgentEvent;

import java.util.ArrayList;
import java.util.List;

/** Pure event reducer. It has no terminal, provider or thread dependency. */
public final class TuiReducer {
    private TuiReducer() {
    }

    public static TuiState reduce(TuiState state, AgentEvent event) {
        if (event == null) {
            return state;
        }
        List<TuiState.TranscriptEntry> transcript = new ArrayList<>(state.transcript());
        List<TuiState.ToolActivity> activities = new ArrayList<>(state.activities());
        TuiState.Status status = state.status();
        int step = state.step();
        String notice = state.notice();

        switch (event.type()) {
            case AGENT_MESSAGE -> {
                if (event.message() != null && !event.message().isBlank()) {
                    addIfNew(transcript,
                            new TuiState.TranscriptEntry(TuiState.Role.ASSISTANT, event.message()));
                }
                closeRunningTools(activities, TuiState.ToolStatus.OK);
                status = TuiState.Status.IDLE;
            }
            case TOOL_EXECUTION -> {
                activities.add(new TuiState.ToolActivity(event.message(), "{}",
                        TuiState.ToolStatus.RUNNING, -1, "", step));
                status = TuiState.Status.TOOL;
            }
            case ERROR -> {
                addIfNew(transcript, new TuiState.TranscriptEntry(TuiState.Role.ERROR, event.message()));
                closeRunningTools(activities, TuiState.ToolStatus.ERROR);
                status = TuiState.Status.ERROR;
            }
            case RUN_COMPLETED -> {
                if (event.result() != null && event.result().failure() != null) {
                    var failure = event.result().failure();
                    String detail = failure.message() == null || failure.message().isBlank()
                            ? failure.type() : failure.message();
                    addIfNew(transcript, new TuiState.TranscriptEntry(TuiState.Role.ERROR,
                            failure.stage() + ": " + detail));
                    closeRunningTools(activities, TuiState.ToolStatus.ERROR);
                }
                status = state.status() == TuiState.Status.ERROR
                        || "FAILED".equalsIgnoreCase(event.message())
                        ? TuiState.Status.ERROR : TuiState.Status.IDLE;
            }
            case SYSTEM -> notice = event.message();
        }
        return new TuiState(transcript, activities, state.model(), step, state.usage(),
                status, state.input(), notice);
    }

    public static TuiState clear(TuiState state) {
        return new TuiState(List.of(), List.of(), state.model(), 0, null,
                TuiState.Status.IDLE, state.input(), "Transcript cleared");
    }

    public static TuiState userMessage(TuiState state, String message) {
        List<TuiState.TranscriptEntry> transcript = new ArrayList<>(state.transcript());
        addIfNew(transcript, new TuiState.TranscriptEntry(TuiState.Role.USER, message));
        return new TuiState(transcript, state.activities(), state.model(), state.step(), state.usage(),
                TuiState.Status.THINKING, state.input(), null);
    }

    private static void addIfNew(List<TuiState.TranscriptEntry> entries, TuiState.TranscriptEntry entry) {
        if (entry.text().isBlank()) return;
        if (entries.isEmpty() || !entries.getLast().equals(entry)) entries.add(entry);
    }

    private static void closeRunningTools(List<TuiState.ToolActivity> activities, TuiState.ToolStatus status) {
        for (int i = 0; i < activities.size(); i++) {
            TuiState.ToolActivity activity = activities.get(i);
            if (activity.status() == TuiState.ToolStatus.RUNNING) {
                activities.set(i, new TuiState.ToolActivity(activity.name(), activity.arguments(),
                        status, activity.durationMillis() < 0 ? 0 : activity.durationMillis(),
                        activity.result(), activity.step()));
            }
        }
    }
}
