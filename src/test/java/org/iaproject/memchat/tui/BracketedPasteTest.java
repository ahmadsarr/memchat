package org.iaproject.memchat.tui;

import com.googlecode.lanterna.input.DefaultKeyDecodingProfile;
import com.googlecode.lanterna.input.InputDecoder;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BracketedPasteTest {
    @Test
    void preservesMultilineAndControlCharactersWithoutTurningThemIntoCommands() throws Exception {
        String text = "  /quit\r\n\t\u0003\033[A" + BracketedPaste.START + "界🙂".repeat(1000) + "\n ";
        var decoder = decoder(BracketedPaste.START + text + BracketedPaste.END + "\n\u0003");
        var originals = decoder.getPatterns();
        var output = new ByteArrayOutputStream();
        try (var paste = BracketedPaste.enable(decoder, output)) {
            var events = readAvailable(decoder);
            assertInstanceOf(BracketedPaste.Event.class, events.getFirst());
            assertEquals(text, pasteText(events));
            assertEquals(KeyType.Enter, events.get(events.size() - 2).getKeyType());
            assertTrue(events.getLast().isCtrlDown());
            assertEquals('c', events.getLast().getCharacter());
        }
        assertEquals(originals, decoder.getPatterns());
        assertEquals("\033[?2004h\033[?2004l", output.toString(StandardCharsets.US_ASCII));
    }

    @Test
    void keepsAnIncompleteEndMarkerAcrossInputPolls() throws Exception {
        String text = BracketedPaste.START + "first\nsecond" + BracketedPaste.END + "\n";
        var reader = new PausedReader(text, text.length() - 4);
        var decoder = new InputDecoder(reader);
        decoder.addProfile(new DefaultKeyDecodingProfile());
        try (var paste = BracketedPaste.enable(decoder, new ByteArrayOutputStream())) {
            var first = readAvailable(decoder);
            assertEquals("first\nsecond", pasteText(first));
            assertFalse(first.stream().anyMatch(key -> key instanceof BracketedPaste.Event event
                    && event.kind == BracketedPaste.Kind.END));
            reader.available = text.length();
            var rest = readAvailable(decoder);
            assertTrue(rest.stream().anyMatch(key -> key instanceof BracketedPaste.Event event
                    && event.kind == BracketedPaste.Kind.END));
            assertEquals(KeyType.Enter, rest.getLast().getKeyType());
        }
    }

    @Test
    void restoresTheDecoderIfEnablingTheTerminalModeFails() {
        var decoder = decoder("hello");
        var originals = decoder.getPatterns();
        var failingOutput = new java.io.OutputStream() {
            public void write(int value) throws IOException { throw new IOException("output unavailable"); }
        };
        assertThrows(IOException.class, () -> BracketedPaste.enable(decoder, failingOutput));
        assertEquals(originals, decoder.getPatterns());
    }

    static List<KeyStroke> decodePaste(String text) throws IOException {
        var decoder = decoder(BracketedPaste.START + text + BracketedPaste.END);
        try (var paste = BracketedPaste.enable(decoder, new ByteArrayOutputStream())) {
            return readAvailable(decoder);
        }
    }

    private static InputDecoder decoder(String input) {
        var decoder = new InputDecoder(new StringReader(input));
        decoder.addProfile(new DefaultKeyDecodingProfile());
        return decoder;
    }

    private static List<KeyStroke> readAvailable(InputDecoder decoder) throws IOException {
        var result = new ArrayList<KeyStroke>();
        KeyStroke key;
        while ((key = decoder.getNextCharacter(false)) != null && key.getKeyType() != KeyType.EOF) result.add(key);
        return result;
    }

    private static String pasteText(List<KeyStroke> keys) {
        StringBuilder text = new StringBuilder();
        for (var key : keys) {
            if (key instanceof BracketedPaste.Event event && event.kind == BracketedPaste.Kind.TEXT) text.append(event.text);
        }
        return text.toString();
    }

    private static final class PausedReader extends Reader {
        private final String text;
        private int offset;
        int available;
        PausedReader(String text, int available) { this.text = text; this.available = available; }
        public boolean ready() { return offset < available; }
        public int read(char[] target, int start, int length) {
            int count = Math.min(length, available - offset);
            if (count == 0) return -1;
            text.getChars(offset, offset + count, target, start);
            offset += count;
            return count;
        }
        public void close() {}
    }
}
