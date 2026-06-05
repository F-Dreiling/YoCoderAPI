package dev.dreiling.YoCoderAPI.service;

import dev.dreiling.YoCoderAPI.model.ProjectFile;
import dev.dreiling.YoCoderAPI.model.RefactorRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the LLM prompt from the target file and any context files
 * explicitly selected by the user on the frontend.
 */
@Service
public class ContextService {

    private static final Logger log = LoggerFactory.getLogger(ContextService.class);

    private final FileService fileService;

    public ContextService(FileService fileService) {
        this.fileService = fileService;
    }

    public BuiltContext buildContext(RefactorRequest req) {

        // 1. Read target file
        String targetContent;
        try {
            targetContent = fileService.readFileContent(req.getProjectRoot(), req.getTargetFile());
        } catch (Exception e) {
            throw new RuntimeException("Cannot read target file '" + req.getTargetFile() + "': " + e.getMessage(), e);
        }

        // 2. Load user-selected context files (if any)
        List<ProjectFile> contextFiles = new ArrayList<>();
        List<String> selected = req.getContextFiles();
        if (selected != null && !selected.isEmpty()) {
            contextFiles = fileService.readFiles(req.getProjectRoot(), selected);
            log.info("Loaded {} user-selected context file(s)", contextFiles.size());
        }

        // 3. Assemble prompt
        String prompt = assemblePrompt(req, targetContent, contextFiles);

        List<String> usedPaths = contextFiles.stream()
                .map(ProjectFile::getRelativePath)
                .collect(Collectors.toList());
        usedPaths.add(0, req.getTargetFile());

        return new BuiltContext(prompt, usedPaths, prompt.length());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Prompt Assembly
    // ─────────────────────────────────────────────────────────────────────────

    private String assemblePrompt(RefactorRequest req, String targetContent, List<ProjectFile> contextFiles) {

        StringBuilder sb = new StringBuilder();

        sb.append("You are an expert software engineer helping with a code task.\n\n");

        // Context files
        if (!contextFiles.isEmpty()) {
            sb.append("## CONTEXT FILES (for reference only — do NOT modify these)\n\n");
            for (ProjectFile pf : contextFiles) {
                sb.append("### ").append(pf.getRelativePath());
                if (pf.isTruncated()) sb.append(" [truncated]");
                sb.append("\n```").append(extToLang(pf.getExtension())).append("\n");
                sb.append(pf.getContent()).append("\n```\n\n");
            }
        }

        // Target file
        sb.append("## TARGET FILE: `").append(req.getTargetFile()).append("`\n");
        sb.append("```").append(extToLang(fileService.getExtension(req.getTargetFile()))).append("\n");
        sb.append(targetContent).append("\n```\n\n");

        // Task
        sb.append("## TASK\n");
        sb.append(req.getPrompt()).append("\n\n");

        sb.append("Respond naturally. When you need to show changed or new code for a file, ");
        sb.append("precede each code block with a marker on its own line: `##FILE: <relative/path/to/file>` ");
        sb.append("followed immediately by the code (no markdown fences). ");
        sb.append("You may output as much or as little code as the task requires — ");
        sb.append("a full file, a single method, or just a snippet. ");
        sb.append("If multiple files need changes, use a ##FILE: marker for each.");

        return sb.toString();
    }

    private String extToLang(String ext) {
        return switch (ext) {
            case ".java"  -> "java";
            case ".php"   -> "php";
            case ".js"    -> "javascript";
            case ".ts"    -> "typescript";
            case ".vue"   -> "vue";
            case ".py"    -> "python";
            case ".go"    -> "go";
            case ".kt"    -> "kotlin";
            case ".cs"    -> "csharp";
            case ".rs"    -> "rust";
            case ".rb"    -> "ruby";
            case ".cpp", ".c", ".h" -> "cpp";
            case ".sql"   -> "sql";
            case ".html"  -> "html";
            case ".css", ".scss" -> "css";
            case ".xml"   -> "xml";
            case ".yaml", ".yml" -> "yaml";
            case ".json"  -> "json";
            default       -> "";
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Result
    // ─────────────────────────────────────────────────────────────────────────

    public static class BuiltContext {
        public final String prompt;
        public final List<String> filesUsed;
        public final int promptCharCount;

        public BuiltContext(String prompt, List<String> filesUsed, int promptCharCount) {
            this.prompt = prompt;
            this.filesUsed = filesUsed;
            this.promptCharCount = promptCharCount;
        }
    }
}