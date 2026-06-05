package dev.dreiling.YoCoderAPI.model;

import lombok.Data;

import java.util.List;

@Data
public class RefactorRequest {

    private String projectRoot;
    private String targetFile;
    private List<String> contextFiles;
    private String prompt;
    private String providerOverride;
    private String modelOverride;

}