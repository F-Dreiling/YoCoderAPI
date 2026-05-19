package dev.dreiling.YoCoderAPI.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "yocoderapi.project")
public class ProjectConfig {

    private long maxFileSizeBytes = 50_000;
    private int maxContextChars = 150_000;

    private List<String> includedExtensions = Arrays.asList(
            ".java", ".php", ".js", ".ts", ".vue", ".py", ".go",
            ".cs", ".cpp", ".c", ".h", ".kt", ".rb", ".swift",
            ".rs", ".jsx", ".tsx", ".html", ".css", ".scss",
            ".sql", ".xml", ".yaml", ".yml", ".json",
            ".gradle", ".properties"
    );

    private List<String> excludedDirs = Arrays.asList(
            ".git", "node_modules", "target", "build", "dist",
            ".idea", ".vscode", "__pycache__", ".gradle",
            "vendor", "out", "bin", ".mvn"
    );
}