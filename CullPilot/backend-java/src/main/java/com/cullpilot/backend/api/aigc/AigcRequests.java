package com.cullpilot.backend.api.aigc;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AigcRequests {
    private AigcRequests() {}

    public record CreateEditsRequest(
            @NotEmpty @Size(max = 50) List<@Valid EditItem> items,
            @Size(max = 100) String model,
            @Size(max = 20) String size,
            Boolean promptExtend,
            Boolean watermark) {}

    public record EditItem(
            @NotBlank String assetId,
            @Size(max = 2000) String prompt) {}
}
