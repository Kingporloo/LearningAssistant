package com.learningassistant.backend.interfaceapi;

import com.learningassistant.backend.dataport.AgentConfigDataPort;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;

/** 合并系统允许范围与用户保存的 Agent 配置。 */
final class AgentSettingsService {
    record ToolOption(String name, String label) {
    }

    record Settings(
            String model,
            String persona,
            List<String> enabledTools,
            List<String> availableModels,
            List<ToolOption> availableTools,
            String updatedAt) {
    }

    private final AgentConfigDataPort data;
    private final String defaultModel;
    private final List<String> availableModels;
    private final List<ToolOption> availableTools;
    private final List<String> defaultEnabledTools;

    AgentSettingsService(
            AgentConfigDataPort data,
            String defaultModel,
            List<String> availableModels,
            List<ToolOption> availableTools,
            List<String> defaultEnabledTools) {
        this.data = data;
        this.defaultModel = defaultModel;
        this.availableModels = List.copyOf(availableModels);
        this.availableTools = List.copyOf(availableTools);
        this.defaultEnabledTools = validateTools(defaultEnabledTools);
        if (!this.availableModels.contains(defaultModel)) {
            throw new IllegalArgumentException("默认模型必须包含在可用模型中");
        }
    }

    Settings get(String userId) {
        return data.find(userId)
                .map(stored -> settings(
                        availableModels.contains(stored.modelName())
                                ? stored.modelName()
                                : defaultModel,
                        stored.persona(),
                        stored.enabledTools().stream()
                                .filter(tool -> availableTools.stream()
                                        .anyMatch(option -> option.name().equals(tool)))
                                .toList(),
                        stored.updatedAt()))
                .orElseGet(() -> settings(
                        defaultModel, "", defaultEnabledTools, null));
    }

    Settings update(
            String userId,
            String model,
            String persona,
            List<String> enabledTools) {
        String normalizedModel = model == null ? "" : model.strip();
        if (!availableModels.contains(normalizedModel)) {
            throw new AgentGatewayException(400, "model 不在系统允许范围内");
        }
        String normalizedPersona = persona == null ? "" : persona.strip();
        if (normalizedPersona.length() > 2_000) {
            throw new AgentGatewayException(400, "persona 不能超过 2000 个字符");
        }
        var normalizedTools = validateTools(enabledTools);
        var saved = data.save(
                userId,
                normalizedModel,
                normalizedPersona,
                normalizedTools,
                OffsetDateTime.now(ZoneOffset.UTC));
        return settings(
                saved.modelName(),
                saved.persona(),
                saved.enabledTools(),
                saved.updatedAt());
    }

    AgentRunRequest.RuntimeConfig runtimeConfig(String userId) {
        var settings = get(userId);
        return new AgentRunRequest.RuntimeConfig(
                settings.model(), settings.persona(), settings.enabledTools());
    }

    private Settings settings(
            String model,
            String persona,
            List<String> enabledTools,
            OffsetDateTime updatedAt) {
        return new Settings(
                model,
                persona,
                List.copyOf(enabledTools),
                availableModels,
                availableTools,
                updatedAt == null ? null : updatedAt.toString());
    }

    private List<String> validateTools(List<String> tools) {
        if (tools == null) {
            throw new AgentGatewayException(400, "enabled_tools 必须是字符串数组");
        }
        var allowed = availableTools.stream()
                .map(ToolOption::name)
                .collect(java.util.stream.Collectors.toSet());
        var unique = new LinkedHashSet<String>();
        for (var tool : tools) {
            if (tool == null || !allowed.contains(tool)) {
                throw new AgentGatewayException(400, "enabled_tools 包含未知工具");
            }
            unique.add(tool);
        }
        return List.copyOf(unique);
    }
}
