package com.cullpilot.backend.api.group;

import com.cullpilot.backend.api.ApiResponse;
import com.cullpilot.backend.api.asset.AssetResponses.AssetResponse;
import com.cullpilot.backend.api.group.GroupResponses.GroupResponse;
import com.cullpilot.backend.api.group.GroupResponses.PageResponse;
import com.cullpilot.backend.service.group.GroupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GroupController {
    private final GroupService service;

    public GroupController(GroupService service) { this.service = service; }

    @GetMapping("/api/v1/projects/{projectId}/groups")
    public ResponseEntity<ApiResponse<PageResponse<GroupResponse>>> list(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return ResponseEntity.ok(ApiResponse.success(service.list(projectId, page, pageSize)));
    }

    @GetMapping("/api/v1/groups/{groupId}/assets")
    public ResponseEntity<ApiResponse<PageResponse<AssetResponse>>> assets(
            @PathVariable String groupId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return ResponseEntity.ok(ApiResponse.success(service.assets(groupId, page, pageSize)));
    }
}
