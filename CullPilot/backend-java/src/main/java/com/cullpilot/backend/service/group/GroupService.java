package com.cullpilot.backend.service.group;

import com.cullpilot.backend.api.ApiException;
import com.cullpilot.backend.api.asset.AssetResponses.AssetResponse;
import com.cullpilot.backend.api.group.GroupResponses.GroupResponse;
import com.cullpilot.backend.api.group.GroupResponses.PageResponse;
import com.cullpilot.backend.domain.group.AssetGroup;
import com.cullpilot.backend.repository.asset.AssetRepository;
import com.cullpilot.backend.repository.group.AssetGroupRepository;
import com.cullpilot.backend.repository.project.ProjectRepository;
import com.cullpilot.backend.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class GroupService {
    private final AssetGroupRepository groups;
    private final AssetRepository assets;
    private final ProjectRepository projects;
    private final CurrentUser currentUser;

    public GroupService(AssetGroupRepository groups, AssetRepository assets,
                        ProjectRepository projects, CurrentUser currentUser) {
        this.groups = groups;
        this.assets = assets;
        this.projects = projects;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public PageResponse<GroupResponse> list(String projectId, int page, int pageSize) {
        requireProject(projectId);
        validatePage(page, pageSize);
        Page<AssetGroup> result = groups.findAllByProjectId(projectId,
                PageRequest.of(page - 1, pageSize, Sort.by("groupNo")));
        return new PageResponse<>(result.map(GroupResponse::from).getContent(), page, pageSize, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageResponse<AssetResponse> assets(String groupId, int page, int pageSize) {
        validatePage(page, pageSize);
        try { UUID.fromString(groupId); } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "Group not found");
        }
        AssetGroup group = groups.findById(groupId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "Group not found"));
        requireProject(group.getProjectId());
        Page<com.cullpilot.backend.domain.asset.Asset> result = assets.findAllByProjectIdAndGroupId(
                group.getProjectId(), groupId, PageRequest.of(page - 1, pageSize, Sort.by("rank").ascending()));
        return new PageResponse<>(result.map(AssetResponse::from).getContent(), page, pageSize, result.getTotalElements());
    }

    private void requireProject(String projectId) {
        try { UUID.fromString(projectId); } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found");
        }
        projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, currentUser.id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found"));
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "Invalid pagination");
        }
    }
}
