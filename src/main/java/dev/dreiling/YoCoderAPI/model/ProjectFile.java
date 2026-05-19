package dev.dreiling.YoCoderAPI.model;

import lombok.Data;

import java.util.List;

@Data
public class ProjectFile {

    private String absolutePath;
    private String relativePath;
    private String content;
    private String extension;
    private long sizeBytes;
    private boolean truncated;
    private List<String> imports;
    private double relevanceScore;

    // ── Constructor ─────────────────────────────────────────────────────────

    public ProjectFile(String absolutePath, String relativePath, String extension, long sizeBytes) {
        this.absolutePath = absolutePath;
        this.relativePath = relativePath;
        this.extension = extension;
        this.sizeBytes = sizeBytes;
    }
}