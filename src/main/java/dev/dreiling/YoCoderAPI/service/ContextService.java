package dev.dreiling.YoCoderAPI.service;

import dev.dreiling.YoCoderAPI.config.ProjectConfig;
import dev.dreiling.YoCoderAPI.model.ProjectFile;
import dev.dreiling.YoCoderAPI.model.RefactorRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Builds the LLM prompt context from a project.
 *
 * Strategy (smart filtering):
 *  1. Read ALL project files (just names + sizes) from FileService scan.
 *  2. Read the TARGET file fully.
 *  3. Extract imports/dependencies from the target file.
 *  4. Score every other file by relevance to the target:
 *       +30 pts  — directly imported by target
 *       +20 pts  — in same package / same directory
 *       +10 pts  — imports the target (reverse dependency)
 *       + 5 pts  — shares naming tokens with target (e.g. "UserService" and "UserRepository")
 *       + 3 pts  — same file extension
 *  5. Select top-scoring files until maxContextChars budget is exhausted.
 *  6. Assemble the structured prompt string.
 */
@Service
public class ContextService {

    private static final Logger log = LoggerFactory.getLogger(ContextService.class);

    private final FileService fileService;
    private final ProjectConfig config;

    // Language-specific import patterns
    private static final Map<String, Pattern> IMPORT_PATTERNS = Map.of(
            ".java",  Pattern.compile("^\\s*import\\s+([\\w.]+);", Pattern.MULTILINE),
            ".php",   Pattern.compile("(?:use|require|include)\\s+['\"]?([\\w/\\\\_.]+)['\"]?", Pattern.MULTILINE),
            ".js",    Pattern.compile("(?:import|require)\\s*(?:\\{[^}]*\\}|[\\w*]+)\\s*(?:from\\s*)?['\"]([^'\"]+)['\"]", Pattern.MULTILINE),
            ".ts",    Pattern.compile("(?:import|require)\\s*(?:\\{[^}]*\\}|[\\w*]+)\\s*(?:from\\s*)?['\"]([^'\"]+)['\"]", Pattern.MULTILINE),
            ".vue",   Pattern.compile("(?:import|require)\\s*(?:\\{[^}]*\\}|[\\w*]+)\\s*(?:from\\s*)?['\"]([^'\"]+)['\"]", Pattern.MULTILINE),
            ".py",    Pattern.compile("^\\s*(?:import|from)\\s+([\\w.]+)", Pattern.MULTILINE),
            ".go",    Pattern.compile("\"([\\w./]+)\"", Pattern.MULTILINE),
            ".kt",    Pattern.compile("^\\s*import\\s+([\\w.]+)", Pattern.MULTILINE)
    );

    public ContextService(FileService fileService, ProjectConfig config) {
        this.fileService = fileService;
        this.config = config;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    public BuiltContext buildContext(RefactorRequest req, List<String> allRelativePaths) {

        List<String> warnings = new ArrayList<>();

        // 1. Read target file
        String targetContent;
        try {
            targetContent = fileService.readFileContent(req.getProjectRoot(), req.getTargetFile());
        } catch (Exception e) {
            throw new RuntimeException("Cannot read target file '" + req.getTargetFile() + "': " + e.getMessage(), e);
        }

        String targetExt = fileService.getExtension(req.getTargetFile());

        // 2. Extract imports from target
        List<String> targetImports = extractImports(targetContent, targetExt);
        log.debug("Target imports found: {}", targetImports);

        // 3. Score all other files
        List<String> candidates = allRelativePaths.stream()
                .filter(p -> !p.equals(req.getTargetFile()))
                .collect(Collectors.toList());

        Map<String, Double> scores = scoreFiles(req.getTargetFile(), targetContent, targetImports, candidates);

        // 4. Load top-scored files within context budget
        List<String> sortedCandidates = candidates.stream()
                .sorted((a, b) -> Double.compare(scores.getOrDefault(b, 0.0), scores.getOrDefault(a, 0.0)))
                .collect(Collectors.toList());

        List<ProjectFile> contextFiles = new ArrayList<>();
        int charBudget = config.getMaxContextChars() - targetContent.length() - 2000; // reserve for prompt scaffold
        int used = 0;

        // We load files in batches to avoid reading everything upfront
        List<String> toLoad = new ArrayList<>();
        for (String path : sortedCandidates) {
            if (scores.getOrDefault(path, 0.0) <= 0) break; // zero-score files not useful
            toLoad.add(path);
            if (toLoad.size() >= 40) break; // read at most 40 candidates
        }

        List<ProjectFile> loaded = fileService.readFiles(req.getProjectRoot(), toLoad);

        for (ProjectFile pf : loaded) {
            int fileChars = pf.getContent() != null ? pf.getContent().length() : 0;
            if (used + fileChars > charBudget) {
                warnings.add("Skipped " + pf.getRelativePath() + " (context budget exhausted)");
                log.debug("Budget exhausted, skipping: {}", pf.getRelativePath());
                continue;
            }
            contextFiles.add(pf);
            used += fileChars;
        }

        log.info("Context: {} supporting files included, {} chars used of {} budget",
                contextFiles.size(), used, charBudget);

        // 5. Build project structure summary
        String projectSummary = buildProjectSummary(req.getProjectRoot(), allRelativePaths);

        // 6. Assemble the prompt
        String prompt = assemblePrompt(req, targetContent, contextFiles, projectSummary, targetImports);

        List<String> usedPaths = contextFiles.stream()
                .map(ProjectFile::getRelativePath)
                .collect(Collectors.toList());
        usedPaths.add(0, req.getTargetFile()); // target is always first

        return new BuiltContext(prompt, usedPaths, prompt.length(), warnings);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Scoring
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Double> scoreFiles(
            String targetPath,
            String targetContent,
            List<String> targetImports,
            List<String> candidates) {

        Map<String, Double> scores = new HashMap<>();
        String targetName = baseName(targetPath);
        String targetDir = parentDir(targetPath);
        String targetExt = fileService.getExtension(targetPath);
        List<String> targetTokens = nameTokens(targetName);

        for (String candidate : candidates) {
            double score = 0;
            String candName = baseName(candidate);
            String candExt = fileService.getExtension(candidate);

            // Direct import match
            for (String imp : targetImports) {
                if (imp.contains(candName.replaceAll("\\.[^.]+$", ""))
                        || candidate.contains(imp.replace('.', '/'))
                        || candidate.contains(imp.replace('\\', '/'))) {
                    score += 30;
                    break;
                }
            }

            // Same directory
            if (parentDir(candidate).equals(targetDir)) {
                score += 20;
            }

            // Reverse dependency: candidate content mentions target name
            String candBaseName = candName.replaceAll("\\.[^.]+$", "");
            if (targetContent.contains(candBaseName)) {
                score += 15;
            }

            // Shared name tokens (e.g. UserService ↔ UserRepository ↔ UserController)
            List<String> candTokens = nameTokens(candName);
            long sharedTokens = targetTokens.stream().filter(candTokens::contains).count();
            score += sharedTokens * 5;

            // Same extension
            if (candExt.equals(targetExt)) {
                score += 3;
            }

            scores.put(candidate, score);
        }

        return scores;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Import Extraction
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> extractImports(String content, String ext) {
        Pattern p = IMPORT_PATTERNS.get(ext);
        if (p == null) return Collections.emptyList();

        List<String> imports = new ArrayList<>();
        Matcher m = p.matcher(content);
        while (m.find()) {
            imports.add(m.group(1).trim());
        }
        return imports;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Prompt Assembly
    // ─────────────────────────────────────────────────────────────────────────

    private String assemblePrompt(
            RefactorRequest req,
            String targetContent,
            List<ProjectFile> contextFiles,
            String projectSummary,
            List<String> targetImports) {

        StringBuilder sb = new StringBuilder();

        sb.append("You are an expert software engineer performing a focused code refactoring task.\n\n");

        // Project context
        sb.append("## PROJECT CONTEXT\n");
        sb.append(projectSummary).append("\n\n");

        // Supporting files
        if (!contextFiles.isEmpty()) {
            sb.append("## RELATED FILES (for context only — do NOT refactor these)\n");
            sb.append("These files are included so you understand the broader codebase:\n\n");
            for (ProjectFile pf : contextFiles) {
                sb.append("### ").append(pf.getRelativePath());
                if (pf.isTruncated()) sb.append(" [truncated]");
                sb.append("\n```").append(extToLang(pf.getExtension())).append("\n");
                sb.append(pf.getContent()).append("\n```\n\n");
            }
        }

        // Extra context from user
        if (req.getExtraContext() != null && !req.getExtraContext().isBlank()) {
            sb.append("## ADDITIONAL CONTEXT FROM USER\n");
            sb.append(req.getExtraContext()).append("\n\n");
        }

        // The actual task
        sb.append("## YOUR TASK\n");
        sb.append("Refactor the following file according to the instruction below.\n\n");
        sb.append("**File to refactor:** `").append(req.getTargetFile()).append("`\n");
        sb.append("**Instruction:** ").append(req.getPrompt()).append("\n\n");

        sb.append("## RULES\n");
        sb.append("1. If only the target file needs changes, output it using the format below and end with ## EXPLANATION.\n");
        sb.append("2. If the instruction requires changes to OTHER files as well (e.g. a new interface, a config change, a dependency update), output ALL affected files using the same format.\n");
        sb.append("3. Each file must be preceded by exactly this marker on its own line: ##FILE: <relative/path/to/file>\n");
        sb.append("4. After all files, add a section starting with exactly the line `## EXPLANATION` followed by a concise numbered list of changes made.\n");
        sb.append("5. Do NOT add any explanation, markdown fences, or commentary in the code output.\n");
        sb.append("6. Do not change the public API unless explicitly instructed.\n");
        sb.append("7. Do not remove existing functionality.\n");
        sb.append("8. Preserve all existing comments.\n");
        sb.append("9. Preserve the original indentation.\n\n");

        sb.append("## OUTPUT FORMAT EXAMPLE\n");
        sb.append("##FILE: src/main/java/com/example/UserService.java\n");
        sb.append("<full file content here>\n");
        sb.append("##FILE: src/main/java/com/example/UserRepository.java\n");
        sb.append("<full file content here>\n");
        sb.append("## EXPLANATION\n");
        sb.append("1. Changed X\n");
        sb.append("2. Added Y\n\n");

        sb.append("## TARGET FILE: `").append(req.getTargetFile()).append("`\n");
        sb.append("```").append(extToLang(fileService.getExtension(req.getTargetFile()))).append("\n");
        sb.append(targetContent).append("\n```\n\n");

        sb.append("Now output all affected files using ##FILE: markers, then the `## EXPLANATION` section.");

        return sb.toString();
    }

    private String buildProjectSummary(String projectRoot, List<String> allFiles) {
        Map<String, Long> extCounts = new LinkedHashMap<>();
        for (String f : allFiles) {
            String ext = fileService.getExtension(f);
            extCounts.merge(ext, 1L, Long::sum);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Project root: `").append(projectRoot).append("`\n");
        sb.append("Total files in scope: ").append(allFiles.size()).append("\n");
        sb.append("File types: ");
        sb.append(extCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> e.getValue() + "x " + e.getKey())
                .collect(Collectors.joining(", ")));

        // Top-level directory structure
        Set<String> topDirs = new TreeSet<>();
        for (String f : allFiles) {
            int slash = f.indexOf('/');
            if (slash > 0) topDirs.add(f.substring(0, slash));
        }
        if (!topDirs.isEmpty()) {
            sb.append("\nTop-level directories: ").append(String.join(", ", topDirs));
        }

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Utility
    // ─────────────────────────────────────────────────────────────────────────

    private String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private String parentDir(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(0, slash) : "";
    }

    /** Splits camelCase/PascalCase/snake_case into lowercase tokens */
    private List<String> nameTokens(String filename) {
        String noExt = filename.replaceAll("\\.[^.]+$", "");
        return Arrays.stream(noExt.split("(?=[A-Z])|[_\\-.]"))
                .map(String::toLowerCase)
                .filter(s -> s.length() > 2)
                .collect(Collectors.toList());
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
    //  Inner class: result of context building
    // ─────────────────────────────────────────────────────────────────────────

    public static class BuiltContext {
        public final String prompt;
        public final List<String> filesUsed;
        public final int promptCharCount;
        public final List<String> warnings;

        public BuiltContext(String prompt, List<String> filesUsed, int promptCharCount, List<String> warnings) {
            this.prompt = prompt;
            this.filesUsed = filesUsed;
            this.promptCharCount = promptCharCount;
            this.warnings = warnings;
        }
    }
}