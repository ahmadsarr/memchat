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
Lucene query operators and metadata field selectors are not exposed. This is
lexical search; embeddings and vector similarity are not implemented yet.

The Lucene archive is separate from `memory.json`; existing JSON memories are
not automatically imported. Programmatic callers can supply a
`LuceneArchivalMemoryRepository` to `AgentBuilder.archivalMemoryRepository(...)`
and must close the repository after closing the agent. `LuceneSearchAdapter`
owns its supplied directory and analyzer and closes them even if opening fails.

## Agent lifecycle and results

`AgentLoop.run(String)` synchronously returns an `AgentRunResult` on the caller's
thread. Sessions are not thread-safe or reentrant: the application owns exclusive
access, asynchronous execution and shutdown coordination. Use `try-with-resources`
to close the session at the end of a conversation. See [the lifecycle contract](docs/agent-lifecycle.md)
for results, interruption, event delivery, memory recovery, and migration notes.
