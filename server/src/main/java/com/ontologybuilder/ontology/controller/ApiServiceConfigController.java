package com.ontologybuilder.ontology.controller;

import com.ontologybuilder.common.response.Result;
import com.ontologybuilder.ontology.model.ApiServiceConfig;
import com.ontologybuilder.ontology.service.ApiServiceConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/api-service-configs")
@RequiredArgsConstructor
@Tag(name = "本体API服务配置", description = "API服务端点的增删改查")
public class ApiServiceConfigController {

    private final ApiServiceConfigService service;

    @PostMapping
    @Operation(summary = "创建API服务配置")
    public Result<ApiServiceConfig> create(@Valid @RequestBody ApiServiceConfig config) {
        return Result.success(service.create(config));
    }

    @GetMapping
    @Operation(summary = "查询所有API服务配置")
    public Result<List<ApiServiceConfig>> findAll() {
        return Result.success(service.findAll());
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询API服务配置详情")
    public Result<ApiServiceConfig> findById(@PathVariable String id) {
        return Result.success(service.findById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新API服务配置")
    public Result<ApiServiceConfig> update(@PathVariable String id, @Valid @RequestBody ApiServiceConfig config) {
        return Result.success(service.update(id, config));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除API服务配置")
    public Result<Void> delete(@PathVariable String id) {
        service.delete(id);
        return Result.success();
    }
}
