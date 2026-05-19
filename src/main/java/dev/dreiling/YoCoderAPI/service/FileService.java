package dev.dreiling.YoCoderAPI.service;

import dev.dreiling.YoCoderAPI.config.ProjectConfig;
import dev.dreiling.YoCoderAPI.model.ProjectFile;
import dev.dreiling.YoCoderAPI.model.ProjectScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final ProjectConfig config;

    public FileService(ProjectConfig config) {
        this.config = config;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Project Scanning
    // ─────────────────────────────────────────────────────────────────────────

    public ProjectScanResult scanProject(String projectRootPath) {
        Path root;
        try {
            root = Path.of(projectRootPath).toRealPath();
        } catch (IOException e) {
            return ProjectScanResult.error("Cannot access path: " + projectRootPath + " — " + e.getMessage());
        }

        if (!Files.isDirectory(root)) {
            return ProjectScanResult.error("Path is not a directory: " + projectRootPath);
        }

        List<String> relPaths = new ArrayList<>();
        Map<String, Long> fileSizes = new LinkedHashMap<>();
        int[] skipped = {0};

        Set<String> excludedDirs = new HashSet<>(config.getExcludedDirs());
        Set<String> includedExts = new HashSet<>(config.getIncludedExtensions());

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) return FileVisitResult.CONTINUE;
                    String dirName = dir.getFileName().toString();
                    if (excludedDirs.contains(dirName) || dirName.startsWith(".")) {
                        skipped[0]++;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    String ext = getExtension(name);

                    if (!includedExts.contains(ext)) {
                        skipped[0]++;
                        return FileVisitResult.CONTINUE;
                    }

                    String rel = root.relativize(file).toString().replace('\\', '/');
                    relPaths.add(rel);
                    fileSizes.put(rel, attrs.size());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Could not visit file: {}", file, exc);
                    skipped[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return ProjectScanResult.error("Error scanning project: " + e.getMessage());
        }

        relPaths.sort(String::compareTo);
        log.info("Scanned project: {} — {} files found, {} skipped", root, relPaths.size(), skipped[0]);
        return ProjectScanResult.success(root.toString(), relPaths, fileSizes, skipped[0]);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  File Reading
    // ─────────────────────────────────────────────────────────────────────────

    public List<ProjectFile> readFiles(String projectRootPath, List<String> relativePaths) {
        Path root = Path.of(projectRootPath).normalize();
        List<ProjectFile> result = new ArrayList<>();

        for (String rel : relativePaths) {
            Path filePath = safeResolve(root, rel);
            if (filePath == null) {
                log.warn("Path traversal attempt or invalid path: {}", rel);
                continue;
            }

            try {
                long size = Files.size(filePath);
                String ext = getExtension(filePath.getFileName().toString());
                ProjectFile pf = new ProjectFile(filePath.toString(), rel, ext, size);

                if (size > config.getMaxFileSizeBytes()) {
                    // Read only first N bytes for very large files, append truncation notice
                    byte[] partial = Arrays.copyOf(Files.readAllBytes(filePath), (int) config.getMaxFileSizeBytes());
                    pf.setContent(new String(partial, StandardCharsets.UTF_8)
                            + "\n\n... [FILE TRUNCATED — " + size + " bytes total, showing first "
                            + config.getMaxFileSizeBytes() + " bytes]");
                    pf.setTruncated(true);
                } else {
                    pf.setContent(Files.readString(filePath, StandardCharsets.UTF_8));
                    pf.setTruncated(false);
                }

                result.add(pf);
            } catch (IOException e) {
                log.warn("Could not read file {}: {}", rel, e.getMessage());
            }
        }

        return result;
    }

    public String readFileContent(String projectRootPath, String relativePath) {
        Path root = Path.of(projectRootPath).normalize();
        Path target = safeResolve(root, relativePath);
        if (target == null || !Files.isReadable(target)) {
            throw new IllegalArgumentException("Cannot read file: " + relativePath);
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + relativePath + ": " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  File Writing
    // ─────────────────────────────────────────────────────────────────────────

    public void saveRefactoredFile(String projectRootPath, String relativePath, String newContent) throws IOException {
        Path root = Path.of(projectRootPath).normalize();
        Path target = safeResolve(root, relativePath);
        if (target == null) throw new IllegalArgumentException("Invalid path: " + relativePath);

        // Create backup
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        if (Files.exists(target)) {
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            log.info("Backup created: {}", backup);
        }

        Files.writeString(target, newContent, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
        log.info("Saved refactored file: {}", target);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    public Path safeResolve(Path root, String relativePath) {
        try {
            Path resolved = root.resolve(relativePath).normalize();
            if (!resolved.startsWith(root.normalize())) {
                log.error("Path traversal blocked: {} resolves outside root {}", relativePath, root);
                return null;
            }
            return resolved;
        } catch (Exception e) {
            return null;
        }
    }

    public String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
    }
}