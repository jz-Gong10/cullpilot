package com.cullpilot.backend.api.export;

public final class ExportRequests {
    private ExportRequests() {}

    public record CreateExportRequest(
            String selection,
            Boolean copyImages,
            Boolean stripGps,
            Boolean includeManifest,
            String manifestFormat) {}
}
