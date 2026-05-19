package dev.dreiling.YoCoderAPI.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ProjectScanResult {

    private String projectRoot;
    private List<String> files;
    private Map<String, Long> fileSizes;
    private int totalFiles;
    private int skippedFiles;
    private boolean success;
    private String errorMessage;

    public static ProjectScanResult success(
            String root,
            List<String> files,
            Map<String, Long> fileSizes,
            int skipped) {
        ProjectScanResult r = new ProjectScanResult();
        r.success = true;
        r.projectRoot = root;
        r.files = files;
        r.fileSizes = fileSizes;
        r.totalFiles = files.size();
        r.skippedFiles = skipped;
        return r;
    }

    public static ProjectScanResult error(String message) {
        ProjectScanResult r = new ProjectScanResult();
        r.success = false;
        r.errorMessage = message;
        return r;
    }
}