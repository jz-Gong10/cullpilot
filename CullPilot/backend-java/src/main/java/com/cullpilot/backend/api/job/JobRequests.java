package com.cullpilot.backend.api.job;

public final class JobRequests {

    private JobRequests() {
    }

    public record AnalyzeRequest(Boolean force, Boolean rebuildGroups) {

        public boolean forceValue() {
            return Boolean.TRUE.equals(force);
        }

        public boolean rebuildGroupsValue() {
            return rebuildGroups == null || rebuildGroups;
        }
    }
}
