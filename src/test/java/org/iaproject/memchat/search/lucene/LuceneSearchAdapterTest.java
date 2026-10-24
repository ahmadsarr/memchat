package org.iaproject.memchat.search.lucene;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.iaproject.memchat.search.api.SearchDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LuceneSearchAdapterTest {
    @TempDir Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"Java (", "\"Java", "Java OR", "C:\\temp"})
    void treatsSearchInputAsPlainText(String query) throws Exception {
        try (var adapter = new LuceneSearchAdapter(new ByteBuffersDirectory(),
                new IndexWriterConfig(new StandardAnalyzer()))) {
            adapter.index(new SearchDocument("one", "Java and C temp directories", Map.of()));
            assertEquals("one", adapter.search(query, 10).results().getFirst().document().id());
        }
    }

    @Test
    void punctuationCannotMatchAllDocumentsOrSearchMetadata() throws Exception {
        try (var adapter = new LuceneSearchAdapter(new ByteBuffersDirectory(),
                new IndexWriterConfig(new StandardAnalyzer()))) {
            adapter.index(new SearchDocument("one", "unrelated content", Map.of("type", "secret")));
            assertTrue(adapter.search("*:*", 10).results().isEmpty());
            assertTrue(adapter.search("type:secret", 10).results().isEmpty());
        }
    }

    @Test
    void constructorFailureClosesTheSuppliedDirectoryAndAnalyzer() throws Exception {
        try (var lockedDirectory = new NIOFSDirectory(temporaryDirectory);
             var analyzer = new StandardAnalyzer();
             var writer = new IndexWriter(lockedDirectory, new IndexWriterConfig(analyzer))) {
            var directory = new TrackingDirectory(temporaryDirectory);
            var failedAnalyzer = new TrackingAnalyzer();
            try {
                assertThrows(IOException.class, () -> new LuceneSearchAdapter(directory,
                        new IndexWriterConfig(failedAnalyzer)));
                assertTrue(directory.closed, "Failed construction must close its directory");
                assertTrue(failedAnalyzer.closed, "Failed construction must close its analyzer");
            } finally {
                directory.close();
                failedAnalyzer.close();
            }
        }
    }

    @Test
    void closingTheAdapterClosesItsAnalyzerAndDirectory() throws Exception {
        var directory = new TrackingDirectory(temporaryDirectory);
        var analyzer = new TrackingAnalyzer();
        try (var adapter = new LuceneSearchAdapter(directory, new IndexWriterConfig(analyzer))) {
            adapter.index(new SearchDocument("one", "Java", Map.of()));
        }
        assertTrue(directory.closed);
        assertTrue(analyzer.closed);
    }

    private static final class TrackingDirectory extends FilterDirectory {
        boolean closed;

        TrackingDirectory(Path path) throws IOException {
            super(new NIOFSDirectory(path));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class TrackingAnalyzer extends Analyzer {
        boolean closed;

        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            return new TokenStreamComponents(new StandardTokenizer());
        }

        @Override
        public void close() {
            closed = true;
            super.close();
        }
    }

    @Test
    void indexesSearchesAndRemovesDocuments() throws Exception {
        var directory = new ByteBuffersDirectory();
        try (var adapter = new LuceneSearchAdapter(
                directory,
                new IndexWriterConfig(new StandardAnalyzer())
        )) {
            adapter.index(new SearchDocument(
                    "memory-1",
                    "The user prefers Java and Maven",
                    Map.of("type", "preference")
            ));

            try (var reader = DirectoryReader.open(directory)) {
                assertEquals(1, reader.numDocs());
            }

            var result = adapter.search("Java", 10);

            assertEquals(1, result.totalHits());
            assertEquals("memory-1", result.results().getFirst().document().id());
            assertEquals("preference", result.results().getFirst().document().metadata().get("type"));
            assertTrue(result.results().getFirst().score() > 0);

            adapter.remove("memory-1");

            assertEquals(0, adapter.search("Java", 10).totalHits());
        }
    }
}
