package com.ainexus.gateway.admin;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.ModelGroup;
import com.ainexus.gateway.entity.ModelGroupModel;
import com.ainexus.gateway.mapper.ModelGroupMapper;
import com.ainexus.gateway.mapper.ModelGroupModelMapper;
import com.ainexus.gateway.service.RouteCacheService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** CRUD for named collections of currently available gateway models. */
@RestController
@RequestMapping("/api/admin/model-groups")
@RequiredArgsConstructor
public class AdminModelGroupController {
    private final ModelGroupMapper modelGroupMapper;
    private final ModelGroupModelMapper modelGroupModelMapper;
    private final RouteCacheService routeCacheService;

    @GetMapping
    public List<ModelGroupView> list() {
        return modelGroupMapper.selectList(new LambdaQueryWrapper<ModelGroup>().orderByAsc(ModelGroup::getId))
                .stream().map(this::view).toList();
    }

    @GetMapping("/available-models")
    public List<String> availableModels() { return routeCacheService.getAvailableModels(); }

    @PostMapping
    public ModelGroupView create(@Valid @RequestBody ModelGroupRequest request) {
        ModelGroup group = new ModelGroup();
        group.setName(cleanName(request.name()));
        group.setRemark(request.remark());
        try {
            modelGroupMapper.insert(group);
        } catch (DuplicateKeyException e) {
            throw BizException.badRequest("model_group_name_exists", "Model group name already exists");
        }
        replaceModels(group.getId(), request.models());
        return view(required(group.getId()));
    }

    @PutMapping("/{id}")
    public ModelGroupView update(@PathVariable Long id, @Valid @RequestBody ModelGroupRequest request) {
        ModelGroup group = required(id);
        group.setName(cleanName(request.name()));
        group.setRemark(request.remark());
        try {
            modelGroupMapper.updateById(group);
        } catch (DuplicateKeyException e) {
            throw BizException.badRequest("model_group_name_exists", "Model group name already exists");
        }
        replaceModels(id, request.models());
        return view(required(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        required(id);
        modelGroupModelMapper.deleteByModelGroupId(id);
        modelGroupMapper.deleteById(id);
    }

    private void replaceModels(Long groupId, List<String> requested) {
        Set<String> models = requested == null ? Set.of() : new LinkedHashSet<>(requested.stream()
                .filter(StringUtils::hasText).map(String::trim).toList());
        if (models.isEmpty()) throw BizException.badRequest("model_group_models_required", "Select at least one existing model");
        Set<String> available = Set.copyOf(routeCacheService.getAvailableModels());
        List<String> unavailable = models.stream().filter(model -> !available.contains(model)).toList();
        if (!unavailable.isEmpty()) throw BizException.badRequest("model_not_available", "Models are not currently available: " + unavailable);
        modelGroupModelMapper.deleteByModelGroupId(groupId);
        models.forEach(name -> { ModelGroupModel binding = new ModelGroupModel(); binding.setModelGroupId(groupId); binding.setModelName(name); modelGroupModelMapper.insert(binding); });
    }

    private ModelGroup required(Long id) {
        ModelGroup group = modelGroupMapper.selectById(id);
        if (group == null) throw BizException.badRequest("model_group_not_found", "Model group not found: " + id);
        return group;
    }

    private String cleanName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.length() > 128) throw BizException.badRequest("invalid_model_group_name", "Name must be at most 128 characters");
        return value;
    }

    private ModelGroupView view(ModelGroup group) {
        List<String> models = modelGroupModelMapper.selectList(new LambdaQueryWrapper<ModelGroupModel>()
                        .eq(ModelGroupModel::getModelGroupId, group.getId()).orderByAsc(ModelGroupModel::getModelName))
                .stream().map(ModelGroupModel::getModelName).toList();
        return new ModelGroupView(group.getId(), group.getName(), models, group.getRemark(), group.getCreateTime(), group.getUpdateTime());
    }

    public record ModelGroupRequest(@NotBlank String name, List<String> models, String remark) { }
    public record ModelGroupView(Long id, String name, List<String> models, String remark, Object createTime, Object updateTime) { }
}
