package dev.dreiling.YoCoderAPI.model;

import lombok.Data;

@Data
public class RefactorRequest {

    private String projectRoot;
    private String targetFile;
    private String providerOverride;
    private String modelOverride;
    private String prompt;
    private String extraContext;
}