package dev.dreiling.YoCoderAPI.model;

import lombok.Data;

import java.util.List;

@Data
public class RefactorResponse {

    private String refactoredCode;
    private String explanation;
    private List<String> contextFilesUsed;
    private int promptCharCount;
    private List<String> warnings;
    private boolean success;
    private String errorMessage;

    // ── Static factory helpers ──────────────────────────────────────────────

    public static RefactorResponse success(
            String code,
            String explanation,
            List<String> contextFiles,
            int promptCharCount,
            List<String> warnings) {
        RefactorResponse r = new RefactorResponse();
        r.success = true;
        r.refactoredCode = code;
        r.explanation = explanation;
        r.contextFilesUsed = contextFiles;
        r.promptCharCount = promptCharCount;
        r.warnings = warnings;
        return r;
    }

    public static RefactorResponse error(String message) {
        RefactorResponse r = new RefactorResponse();
        r.success = false;
        r.errorMessage = message;
        return r;
    }
}