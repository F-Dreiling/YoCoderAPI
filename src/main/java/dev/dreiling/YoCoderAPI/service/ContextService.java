package dev.dreiling.YoCoderAPI.service;

import dev.dreiling.YoCoderAPI.model.RefactorRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ContextService {

    private static final Logger log = LoggerFactory.getLogger(ContextService.class);

    public BuiltContext buildContext(RefactorRequest req) {

        Map<String, String> contextContents = req.getContextFileContents();
        int contextCount = contextContents != null ? contextContents.size() : 0;
        log.info("Building prompt for: {} with {} context file(s)", req.getTargetFilePath(), contextCount);

        String prompt = assemblePrompt(req);

        List<String> filesUsed = contextContents != null
                ? List.copyOf(contextContents.keySet())
                : List.of();

        return new BuiltContext(prompt, filesUsed, prompt.length());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Prompt Assembly
    // ─────────────────────────────────────────────────────────────────────────

    private String assemblePrompt(RefactorRequest req) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are an expert software engineer helping with a code task.\n\n");

        // Context files
        Map<String, String> ctx = req.getContextFileContents();
        if (ctx != null && !ctx.isEmpty()) {
            sb.append("## CONTEXT FILES (for reference only — do NOT modify these)\n\n");
            for (Map.Entry<String, String> entry : ctx.entrySet()) {
                String path = entry.getKey();
                String content = entry.getValue();
                sb.append("### ").append(path).append("\n");
                sb.append("```").append(extToLang(getExtension(path))).append("\n");
                sb.append(content).append("\n```\n\n");
            }
        }

        // Target file
        String targetPath = req.getTargetFilePath();
        sb.append("## TARGET FILE: `").append(targetPath).append("`\n");
        sb.append("```").append(extToLang(getExtension(targetPath))).append("\n");
        sb.append(req.getTargetFileContent()).append("\n```\n\n");

        // Task
        sb.append("## TASK\n");
        sb.append(req.getPrompt()).append("\n\n");

        sb.append("Respond naturally. When showing code that belongs to a file, wrap it with markers on their own lines:\n");
        sb.append("##FILE: <relative/path/to/file>\n");
        sb.append("<code — no markdown fences>\n");
        sb.append("##ENDFILE\n");
        sb.append("You may output a full file, a single method, or just a snippet — whatever the task requires. ");
        sb.append("Use one ##FILE:/##ENDFILE pair per file. All explanation and commentary must go outside these markers.");

        return sb.toString();
    }

    private String getExtension(String path) {
        if (path == null) return "";
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot).toLowerCase() : "";
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