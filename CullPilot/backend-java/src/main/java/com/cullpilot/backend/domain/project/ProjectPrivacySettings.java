package com.cullpilot.backend.domain.project;

public class ProjectPrivacySettings {

    private boolean sendThumbnailsToProvider;
    private boolean stripGpsOnExport;

    public ProjectPrivacySettings() {
        this(false, true);
    }

    public ProjectPrivacySettings(boolean sendThumbnailsToProvider, boolean stripGpsOnExport) {
        this.sendThumbnailsToProvider = sendThumbnailsToProvider;
        this.stripGpsOnExport = stripGpsOnExport;
    }

    public ProjectPrivacySettings copy() {
        return new ProjectPrivacySettings(sendThumbnailsToProvider, stripGpsOnExport);
    }

    public boolean isSendThumbnailsToProvider() {
        return sendThumbnailsToProvider;
    }

    public void setSendThumbnailsToProvider(boolean sendThumbnailsToProvider) {
        this.sendThumbnailsToProvider = sendThumbnailsToProvider;
    }

    public boolean isStripGpsOnExport() {
        return stripGpsOnExport;
    }

    public void setStripGpsOnExport(boolean stripGpsOnExport) {
        this.stripGpsOnExport = stripGpsOnExport;
    }
}
