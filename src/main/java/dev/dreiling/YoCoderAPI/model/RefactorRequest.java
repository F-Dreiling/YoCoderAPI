package dev.dreiling.YoCoderAPI.model;

import lombok.Data;

import java.util.Map;

@Data
public class RefactorRequest {

    private String targetFilePath;
    private String targetFileContent;
    private Map<String, String> contextFileContents;

    private String prompt;
    private String providerOverride;
    private String modelOverride;

}