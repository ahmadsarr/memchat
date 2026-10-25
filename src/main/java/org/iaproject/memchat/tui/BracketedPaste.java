package org.iaproject.memchat.tui;

import com.googlecode.lanterna.input.CharacterPattern;
import com.googlecode.lanterna.input.InputDecoder;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Recognizes terminal paste boundaries and keeps the enclosed text out of normal key decoding. */
final class BracketedPaste implements CharacterPattern, AutoCloseable {
    static final String START = "\033[200~";
    static final String END = "\033[201~";
    private final InputDecoder decoder;
    private final OutputStream output;
    private final List<CharacterPattern> original;
    private boolean pasting;
    private boolean closed;

    enum Kind { START, TEXT, END }

    static final class Event extends KeyStroke {
        final Kind kind;
        final String text;
        Event(Kind kind, String text) {
            super(KeyType.Unknown);
            this.kind = kind;
            this.text = text;
        }
    }

    private BracketedPaste(InputDecoder decoder, OutputStream output) {
        this.decoder = decoder;
        this.output = output;
        original = List.copyOf(decoder.getPatterns());
        original.forEach(decoder::removePattern);
        decoder.addProfile(() -> List.of(this));
    }

    static BracketedPaste enable(InputDecoder decoder, OutputStream output) throws IOException {
        var paste = new BracketedPaste(decoder, output);
        try {
            output.write("\033[?2004h".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            return paste;
        } catch (IOException failure) {
            try { paste.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    @Override
    public Matching match(List<Character> sequence) {
        if (pasting) {
            if (prefixOf(END, sequence)) {
                if (sequence.size() < END.length()) return Matching.NOT_YET;
                pasting = false;
                return new Matching(new Event(Kind.END, ""));
            }
            StringBuilder text = new StringBuilder(sequence.size());
            sequence.forEach(text::append);
            return new Matching(new Event(Kind.TEXT, text.toString()));
        }
        boolean partial = prefixOf(START, sequence);
        if (partial && sequence.size() == START.length()) {
            pasting = true;
            return new Matching(new Event(Kind.START, ""));
        }
        KeyStroke best = null;
        for (CharacterPattern pattern : original) {
            Matching match = pattern.match(sequence);
            if (match != null) {
                partial |= match.partialMatch;
                if (match.fullMatch != null) best = match.fullMatch;
            }
        }
        return partial || best != null ? new Matching(partial, best) : null;
    }

    private static boolean prefixOf(String expected, List<Character> sequence) {
        if (sequence.size() > expected.length()) return false;
        for (int i = 0; i < sequence.size(); i++) if (sequence.get(i) != expected.charAt(i)) return false;
        return true;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        decoder.removePattern(this);
        decoder.addProfile(() -> original);
        output.write("\033[?2004l".getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }
}
