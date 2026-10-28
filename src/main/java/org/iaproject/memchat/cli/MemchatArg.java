package org.iaproject.memchat.cli;


import lombok.ToString;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import java.nio.file.Path;

@ToString
@CommandLine.Command(name = "memchat", mixinStandardHelpOptions = true,
        description = "MemGPT gere une memoire hierarchique pour permettre a un LLM de travailler au-dela de sa fenetre de contexte.")
public class MemchatArg implements Runnable {
    @Option(names = "--api-key", defaultValue = "ollama")
    public String apiKey;
    @Option(names = "--base-url", defaultValue = "http://127.0.0.1:11434/v1")
    public String baseUrl;
    @Option(names = "--model", required = true)
    public String model;
    @Option(names = "--instruction", required = false)
    public String instruction;
    @Option(names = "--max-steps", defaultValue = "50")
    public int maxSteps;
    @Option(names = "--raw-history-n", defaultValue = "1")
    public int rawHistoryN;
    @Option(names = "--dry-run")
    public boolean dryRun;
    @Option(names = "--archive-index", paramLabel = "DIRECTORY",
            description = "Use a Lucene BM25 archive in DIRECTORY instead of the default memory.json file")
    public Path archiveIndex;

    @Option(names = {"--user-memory", "--user-preferences"}, paramLabel = "FILE",
            description = "Enable structured user memory stored in FILE (--user-preferences is a compatibility alias)")
    public Path userPreferences;

    @Override
    public void run() {
        System.out.println(this);
    }
}
