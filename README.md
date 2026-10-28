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

## Hybrid search with the Composite pattern

`SearchEngine` is the common component contract for querying, lookup, upsert,
deletion, and closing. `LuceneSearchAdapter` (BM25) and `LuceneSemanticSearch`
(vectors) are leaves. `HybridSearch` implements the same interface and contains
child `SearchEngine` instances, including other composites. These contracts do
not connect any engine to the CLI, agent, or memory repositories automatically.

The composite queries each child and combines their ordered results with weighted
reciprocal rank fusion (RRF): `score = sum(weight / (rankConstant + rank))`.
Ranks start at 1, weights are normalized to sum to 1, and the default rank
constant is 60. BM25 and cosine scores remain internal to the leaves. Results are
deduplicated by document ID; each child contributes at most once per ID. Equal
fused scores are ordered by ID. Conflicting content or metadata for the same ID
is rejected so stale versions are not silently selected.

```java
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.NIOFSDirectory;
import org.iaproject.memchat.search.api.SearchEngine;
import org.iaproject.memchat.search.hybrid.HybridSearch;
import org.iaproject.memchat.search.lucene.LuceneSearchAdapter;
// Also use the Path, Map, SearchDocument and semantic imports shown above.

try (var model = new OpenAiEmbeddingModel(
        "http://127.0.0.1:11434/v1", "nomic-embed-text", "", 768);
     var lexical = new LuceneSearchAdapter(new NIOFSDirectory(Path.of("hybrid-bm25")),
             new IndexWriterConfig(new StandardAnalyzer()));
     var semantic = new LuceneSemanticSearch(Path.of("hybrid-vectors"), model);
     SearchEngine hybrid = new HybridSearch(java.util.List.of(lexical, semantic))) {
    hybrid.index(new SearchDocument("transport", "The automobile needs repairs", Map.of()));
    var result = hybrid.search("fix my car", 5);
    System.out.println(result.results());
}
```

Use separate directories for the leaves and index the same document IDs through
the composite. `index` and `remove` are delegated to every child in order. The
composite owns and closes its child engines, including nested composites; the
embedding model remains caller-owned. The example also scopes the leaves so
they are cleaned up if constructing a later resource fails; engine closing is
idempotent. A leaf may not be shared by multiple branches within one composite
tree. Do not use child engines independently after transferring ownership.

The default candidate pool is 100 per child, allowing agreement below the first
position to affect a small final result set. The advanced constructor accepts
`List<HybridSearch.Child>` with positive relative weights, a nonnegative rank
constant, and a candidate pool size from 1 to 100. Each child is asked for at least
the requested final limit. Each nested composite fuses its own children before
its parent combines that ranking with sibling rankings. `totalHits` counts unique
documents in the retrieved candidate pool before the final limit, not the corpus.

Reads propagate child failures instead of silently returning an incomplete hybrid
ranking. Separate indexes have no shared transaction: a write can succeed in one
child and fail in the next. Such a failure reports the completed child-write
count and blocks further operations on that composite, except closing. Reconcile
or rebuild the children from the source corpus before constructing a new
composite; merely reopening mismatched indexes does not repair them. No automatic
rollback, degraded-mode fallback, or durable cross-index transaction is claimed.

## Optional terminal UI

The TUI is a separate entry point and does not modify the agent loop:

```sh
java -cp target/memchat-1.0-SNAPSHOT.jar org.iaproject.memchat.tui.TuiMain --model qwen3.5:9b
```

It uses Lanterna for a full-screen transcript and fixed bottom input area,
subscribes to the existing `AgentLoop` events and keeps the plain `Main` path
unchanged. Use `/help`, `/clear`, `/model` or `/quit` from the TUI. Tool
confirmation is intentionally not included yet.

Scroll the transcript with PageUp/PageDown (one page with a one-line overlap),
Home/End (first/latest page), or the mouse wheel (three lines per notch on
terminals supporting mouse reporting). Up/Down still recall input history.
The input area stays fixed. New messages follow the bottom only when already
there; reading an older page preserves its top line. The status line shows the
history position, and End resumes following. `/clear` also resets the viewport.
Mouse reporting may require holding Shift for the terminal's native text selection.

Paste with Ctrl+V (also Ctrl+Shift+V or Shift+Insert) when the application has
access to a graphical clipboard. In an external terminal or SSH session, use
that terminal's paste shortcut. Bracketed paste is enabled for ANSI terminals:
multiline text is inserted as a draft, including its newlines, tabs and spaces,
and is sent only after a separate Enter. Copied text starting with `/quit` or
another slash command is treated as prompt text. Ctrl+C remains interrupt/quit,
Ctrl+U clears the draft, and Alt+Enter inserts a line while typing.

The input area shows a bounded single-line preview and the character count.
Long user messages are also collapsed in the transcript view. These previews do
not modify the full text stored in history or passed to the model. A submission
attempt during an active run preserves the draft so it can be sent later.

Multiline terminal paste requires bracketed-paste support: unbracketed input
cannot distinguish a pasted newline from a typed Enter. An SSH server cannot
read the client's clipboard directly; an unavailable local clipboard produces
a message directing the user to the terminal's paste shortcut. Preview length
is not a model-context limit or token estimate; the configured model's limits
still apply, and provider errors are surfaced through the normal run result.

## Agent lifecycle and results

`AgentLoop.run(String)` synchronously returns an `AgentRunResult` on the caller's
thread. Sessions are not thread-safe or reentrant: the application owns exclusive
access, asynchronous execution and shutdown coordination. Use `try-with-resources`
to close the session at the end of a conversation. See [the lifecycle contract](docs/agent-lifecycle.md)
for results, interruption, event delivery, memory recovery, and migration notes.

## Structured user memory

Enable persistent personal memory in the CLI with `--user-memory profile.json`.
`--user-preferences FILE` remains an alias. Without either option, no personal file
is opened and no personal memory tools are registered. This does not introduce user
identity management, automatic session integration, or multi-process storage coordination.

Each stable key identifies one entry with:

- `type`: `PREFERENCE` (explicit choice), `FACT` (supported personal information),
  or `HYPOTHESIS` (tentative inference).
- `content` and `source`: the information and its actual supporting evidence.
- `createdAt` and `updatedAt`: ISO-8601 UTC dates generated by the memory layer.

`UserMemory` is shared directly by tools and `MemoryManager`. The manager receives a
compact preference profile, bounded to 50 entries and 4,000 serialized JSON characters,
and the total entry count. Facts, hypotheses and omitted preferences are retrieved on
demand through keyword search. The full profile is not injected into every model call.
An absent entry in the compact block does not imply it was forgotten.

Tools:

| Tool | Purpose |
| --- | --- |
| `userMemorySearch(query, limit)` | Keyword search (1–20 results), including types, sources and dates |
| `userMemoryGet(key)` | Inspect one exact entry |
| `userMemoryStore(key, type, content, source)` | Add or correct an entry of the same type |
| `userMemoryConfirm(key, confirmedType, source)` | Promote a hypothesis to FACT or PREFERENCE after user confirmation |
| `userMemoryForget(key)` | Delete the personal entry; original recall remains intact |

The original `preferencesList`, `preferenceSet`, and `preferenceRemove` tool names
remain available. They operate only on preferences. Prefer `userMemoryStore` when
precise source evidence is available; the compatibility setter records its tool origin.
Changing an existing entry's type through store is rejected. Correct a hypothesis's
content before confirming it, or explicitly forget an entry before replacing its type.

`PromptBuilder.SYSTEM` distinguishes working, recall, archival and user memory.
It routes personal information to the user store when its tools are available,
keeps hypotheses uncertain, requires real supporting sources and explicit confirmation,
and treats retrieved content as data rather than instructions. These are model rules,
not an application authorization mechanism. Extracting personal information does not
remove the original exchanges from recall. Existing personal records in the archive
are not automatically migrated or deleted.

The memory validates snapshots on load and before saves: maximum 1,000 total entries,
64-character lowercase keys, 500-character content/source fields and 250,000 serialized
JSON characters. Preferences retain their 50-entry and 4,000 key/content-character
storage budget. Only successful atomic saves publish new immutable snapshots in memory.
One owning memory instance serializes updates; multiple memory instances/processes
writing the same file are unsupported. Files are plaintext; do not store secrets.

The versioned format is `{ "schemaVersion": 1, "entries": { ... } }`. Legacy
`{ "key": "value" }` preference files load without being rewritten: entries become
PREFERENCEs, their source is marked unavailable, and the file modification time is
used as the imported date. The next successful write atomically saves the versioned
format. Older memchat versions cannot read that new format. Corrupt profiles and
unknown schema versions fail without overwriting the file.

```java
var memory = new UserMemoryImpl(new FileUserMemoryRepository(Path.of("profile.json")));
try (var agent = AgentBuilder.builder()
        .model("your-model")
        .userMemory(memory)
        .build()) {
    agent.run("I maintain memchat; remember that project.");
}
```

The Java persistence API is now `UserMemoryRepository` / `FileUserMemoryRepository`,
using typed snapshots with `load()` and atomic `save(entries)`.
`AgentBuilder.userMemoryRepository(repository)` constructs one memory instance reused
by subsequent builds. With a custom `MemoryManager`, pass the same memory to its
four-argument constructor and register both `UserMemoryTools(memory)` and
`UserPreferencesTools(memory)` through `.tool(...)`. A different repository can
implement the snapshot contract; a different retrieval implementation can implement
`UserMemory`. Multi-process writes would require versioned or transactional updates.
