package org.iaproject.memchat.tui;

import org.iaproject.memchat.agent.AgentEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TuiReducerTest {
    @Test
    void reducesConversationToolActivityAndUsage() {
        TuiState state = TuiState.initial("test-model");
        state = TuiReducer.userMessage(state, "hello");
        state = TuiReducer.reduce(state, new AgentEvent(AgentEvent.Type.TOOL_EXECUTION, "search"));
        state = TuiReducer.reduce(state, new AgentEvent(AgentEvent.Type.AGENT_MESSAGE, "**done**"));

        assertEquals(2, state.transcript().size());
        assertEquals("search", state.activities().getFirst().name());
        assertEquals(TuiState.ToolStatus.OK, state.activities().getFirst().status());
        assertEquals(TuiState.Status.IDLE, state.status());
    }

    @Test
    void rendersErrorsAndClearsTheView() {
        TuiState state = TuiState.initial("test")
                .withInput("draft");
        state = TuiReducer.reduce(state, new AgentEvent(AgentEvent.Type.ERROR, "timeout"));
        assertEquals(TuiState.Role.ERROR, state.transcript().getFirst().role());
        assertEquals(TuiState.Status.ERROR, state.status());
        assertTrue(TuiReducer.clear(state).transcript().isEmpty());
    }
}
