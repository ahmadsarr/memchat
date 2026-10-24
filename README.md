# MemGPT

MemGPT gere une memoire hierarchique pour permettre a un LLM de travailler au-dela de sa fenetre de contexte.

# MemGPT: Towards LLMs as Operating Systems

Charles Packer Affiliation: University of California, Berkeley Correspondence
to: [cpacker@berkeley.edu](mailto:cpacker@berkeley.edu)    Sarah Wooders Affiliation: University of California, Berkeley
   Kevin Lin Affiliation: University of California, Berkeley    Vivian Fang Affiliation: University of California,
Berkeley    Shishir G. Patil Affiliation: University of California, Berkeley    Ion Stoica Affiliation: University of
California, Berkeley    Joseph E. Gonzalez Affiliation: University of California, Berkeley
## Build and regression tests

Use JDK 21 and Maven, then run:

```sh
mvn --batch-mode --no-transfer-progress clean verify
```

The compiler plugin is pinned and preserves Java parameter names (`-parameters`),
which the agent uses for tool schemas and JSON argument binding. Tests invoke tools
with those declared names rather than compiler-generated names such as `arg0`.
The GitHub Actions workflow runs the same verification on pull requests and master.
The tests use temporary memory files and local HTTP servers; no LLM service or API
key is needed.

## Lucene archival search

The CLI keeps using `memory.json` by default, so existing archives remain available.
Pass `--archive-index DIRECTORY` to use a persistent Lucene archive instead. The
directory is created when a conversation starts and is closed when it ends,
including when agent setup or execution fails.

Lucene searches plain text with BM25 ranking. Punctuation is analyzed as text;
Lucene query operators and metadata field selectors are not exposed. This backend
is lexical; the standalone semantic module below is not connected to it.

The Lucene archive is separate from `memory.json`; existing JSON memories are
not automatically imported. Programmatic callers can supply a
`LuceneArchivalMemoryRepository` to `AgentBuilder.archivalMemoryRepository(...)`
and must close the repository after closing the agent. `LuceneSearchAdapter`
owns its supplied directory and analyzer and closes them even if opening fails.

## Standalone semantic search

`search.semantic` is an independent module. It does not change `Main`, the agent,
archival memory, BM25, or the `--archive-index` option. It reuses only the search
contracts (`SearchReader`, `SearchWriter`, and `SearchDocument`). Nothing starts
an embedding request or creates a semantic index unless this module is called.

`LuceneSemanticSearch` stores embeddings in a dedicated Lucene HNSW index and ranks
nearest candidates by cosine similarity. `OpenAiEmbeddingModel` obtains embeddings
from an OpenAI-compatible `/embeddings` endpoint, such as Ollama's `/v1` API.
Applications may instead supply their own `EmbeddingModel`.

```java
import java.nio.file.Path;
import java.util.Map;
import org.iaproject.memchat.search.api.SearchDocument;
import org.iaproject.memchat.search.semantic.LuceneSemanticSearch;
import org.iaproject.memchat.search.semantic.OpenAiEmbeddingModel;

// The embedding model must already be available on the configured server.
try (var model = new OpenAiEmbeddingModel(
        "http://127.0.0.1:11434/v1", "nomic-embed-text", "", 768);
     var search = new LuceneSemanticSearch(Path.of("semantic-index"), model)) {
    search.index(new SearchDocument("transport", "The automobile needs repairs", Map.of()));
    var nearest = search.search("fix my car", 5);
    System.out.println(nearest.results());
}
```

The HTTP client sends `model`, `input`, `encoding_format: float`, and `dimensions`.
Use an embedding model that supports the configured output dimension; this Lucene
codec accepts 1–1024 dimensions. OpenAI models with larger default embeddings
must support reduced dimensions. Both document and query vectors use the same
model. The model ID, dimension, and schema are persisted, and reopening an index
with a different model or opening a BM25 directory is rejected. A model upgrade
requires rebuilding into a new directory; no archive is imported automatically.

Search returns up to 100 nearest candidates. Scores are Lucene's cosine scores
`(1 + cosine) / 2`, in descending order; `totalHits` counts the returned candidates,
not all documents in the corpus. There is no relevance cutoff or hybrid reranking.
Documents are embedded as a whole; chunking and provider input limits are the
caller's responsibility. Empty, wrong-sized, zero, or non-finite embeddings are
rejected before replacing an existing document.

Close the index before the embedding model. The index owns its Lucene resources
but does not close a supplied model. `OpenAiEmbeddingModel` owns its HTTP client,
uses bounded connection/request timeouts, preserves interruption, and propagates
HTTP or response-validation failures. Its advanced constructor accepts a custom
HTTP client and request timeout.

The semantic tests use deterministic vector fixtures and a local HTTP embedding
server; they require neither an API key nor a running embedding model. They verify
ranking and client/index behavior, not the quality of a particular real model.

## Agent lifecycle and results

`AgentLoop.run(String)` synchronously returns an `AgentRunResult` on the caller's
thread. Sessions are not thread-safe or reentrant: the application owns exclusive
access, asynchronous execution and shutdown coordination. Use `try-with-resources`
to close the session at the end of a conversation. See [the lifecycle contract](docs/agent-lifecycle.md)
for results, interruption, event delivery, memory recovery, and migration notes.
