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
