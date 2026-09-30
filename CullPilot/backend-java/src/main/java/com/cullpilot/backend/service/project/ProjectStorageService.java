package com.cullpilot.backend.service.project;

import com.cullpilot.backend.api.ApiException;
import com.cullpilot.backend.config.StorageProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class ProjectStorageService {

    private final StorageProperties properties;

    public ProjectStorageService(StorageProperties properties) {
        this.properties = properties;
    }

    public void deleteProjectStorage(String projectId) {
        try {
            UUID.fromString(projectId);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "STORAGE_CLEANUP_FAILED",
                    "项目存储目录标识无效");
        }

        Path root = Paths.get(properties.getRoot()).toAbsolutePath().normalize();
        Path projectDirectory = root.resolve(projectId).normalize();

        if (projectDirectory.equals(root) || !projectDirectory.startsWith(root)) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "STORAGE_CLEANUP_FAILED",
                    "项目存储目录不在允许范围内");
        }

        if (!Files.exists(projectDirectory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(projectDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::deletePath);
        } catch (IOException | UncheckedIOException exception) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "STORAGE_CLEANUP_FAILED",
                    "项目文件清理失败");
        }
    }

    private void deletePath(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
