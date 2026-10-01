package com.cullpilot.backend.service.aigc;

import com.cullpilot.backend.api.ApiException;
import com.cullpilot.backend.config.StorageProperties;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@Service
public class AigcStorageService {
    private static final int MAX_GENERATED_BYTES = 20 * 1024 * 1024;
    private final StorageProperties properties;

    public AigcStorageService(StorageProperties properties) {
        this.properties = properties;
    }

    public StoredImage store(String projectId, String editId, String encoded, String mimeType) {
        String suffix = switch (mimeType == null ? "" : mimeType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> throw new ApiException(BAD_GATEWAY, "AIGC_INVALID_IMAGE", "Unsupported generated image type");
        };
        if (encoded == null || encoded.length() > (MAX_GENERATED_BYTES * 4L / 3L + 16)) {
            throw new ApiException(BAD_GATEWAY, "AIGC_INVALID_IMAGE", "Generated image is too large");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length == 0 || bytes.length > MAX_GENERATED_BYTES
                    || ImageIO.read(new ByteArrayInputStream(bytes)) == null) {
                throw new IllegalArgumentException("Invalid image content");
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new ApiException(BAD_GATEWAY, "AIGC_INVALID_IMAGE", "Generated image is invalid");
        }

        UUID.fromString(projectId);
        UUID.fromString(editId);
        Path root = Paths.get(properties.getRoot()).toAbsolutePath().normalize();
        Path directory = root.resolve(projectId).resolve("aigc").resolve(editId).normalize();
        if (!directory.startsWith(root)) {
            throw new ApiException(INTERNAL_SERVER_ERROR, "STORAGE_PATH_INVALID", "Invalid storage path");
        }
        Path target = directory.resolve("generated" + suffix);
        try {
            Files.createDirectories(directory);
            Files.write(target, bytes);
            return new StoredImage(root.relativize(target).toString(), bytes.length);
        } catch (IOException exception) {
            throw new ApiException(INTERNAL_SERVER_ERROR, "STORAGE_WRITE_FAILED", "Cannot store generated image");
        }
    }

    public void delete(String relativePath) {
        if (relativePath == null) return;
        Path root = Paths.get(properties.getRoot()).toAbsolutePath().normalize();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) return;
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // Best-effort cleanup if persistence fails after the file is written.
        }
    }

    public record StoredImage(String path, long sizeBytes) {}
}
