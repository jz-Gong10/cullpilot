package com.cullpilot.backend.service.asset;

import com.cullpilot.backend.api.ApiException;
import com.cullpilot.backend.config.StorageProperties;
import com.cullpilot.backend.domain.asset.Asset;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

@Service
public class AssetStorageService {

    private static final int THUMBNAIL_MAX_SIZE = 480;
    private final StorageProperties properties;

    public AssetStorageService(StorageProperties properties) {
        this.properties = properties;
    }

    public StoredAsset store(String projectId, String assetId, MultipartFile file, String mimeType) {
        Path projectRoot = projectRoot(projectId);
        Path assetRoot = projectRoot.resolve("assets").resolve(assetId).normalize();
        ensureInside(projectRoot, assetRoot);

        try {
            Files.createDirectories(assetRoot);
            String extension = extensionFor(mimeType);
            Path original = assetRoot.resolve("original" + extension);
            Path thumbnail = assetRoot.resolve("thumbnail.jpg");
            file.transferTo(original);

            BufferedImage image;
            try (InputStream input = Files.newInputStream(original)) {
                image = ImageIO.read(input);
            }
            if (image == null) {
                deleteQuietly(assetRoot);
                throw invalidFile("IMAGE_DECODE_FAILED", "图片内容无法解析");
            }

            writeThumbnail(image, thumbnail);
            return new StoredAsset(relative(original), relative(thumbnail), image.getWidth(), image.getHeight());
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            deleteQuietly(assetRoot);
            throw new ApiException(INTERNAL_SERVER_ERROR, "STORAGE_WRITE_FAILED", "图片文件保存失败");
        }
    }

    public Resource load(String relativePath) {
        Path root = Paths.get(properties.getRoot()).toAbsolutePath().normalize();
        Path path = root.resolve(relativePath).normalize();
        ensureInside(root, path);
        try {
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ApiException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "ASSET_FILE_NOT_FOUND",
                        "图片文件不存在");
            }
            return resource;
        } catch (IOException exception) {
            throw new ApiException(INTERNAL_SERVER_ERROR, "STORAGE_READ_FAILED", "图片文件读取失败");
        }
    }

    public Path exportPath(String projectId, String exportId) {
        Path projectRoot = projectRoot(projectId);
        Path exportRoot = projectRoot.resolve("exports").resolve(exportId).normalize();
        ensureInside(projectRoot, exportRoot);
        try {
            Files.createDirectories(exportRoot);
            return exportRoot;
        } catch (IOException exception) {
            throw new ApiException(INTERNAL_SERVER_ERROR, "STORAGE_WRITE_FAILED", "导出目录创建失败");
        }
    }

    public void copyForExport(String relativeSource, Path target, String mimeType, boolean stripGps) {
        Path root = Paths.get(properties.getRoot()).toAbsolutePath().normalize();
        Path source = root.resolve(relativeSource).normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        ensureInside(root, source);
        ensureInside(root, normalizedTarget);
        try {
            Files.createDirectories(normalizedTarget.getParent());
            if (!stripGps) {
                Files.copy(source, normalizedTarget);
                return;
            }
            BufferedImage image;
            try (InputStream input = Files.newInputStream(source)) {
                image = ImageIO.read(input);
            }
            if (image == null || !ImageIO.write(image, formatFor(mimeType), normalizedTarget.toFile())) {
                throw new IOException("No image writer available");
            }
        } catch (IOException exception) {
            throw new ApiException(INTERNAL_SERVER_ERROR, "EXPORT_IMAGE_FAILED", "导出图片处理失败");
        }
    }

    public Resource loadExport(String relativePath) {
        return load(relativePath);
    }

    public void delete(String relativeOriginal, String relativeThumbnail) {
        deleteQuietly(path(relativeOriginal));
        deleteQuietly(path(relativeThumbnail));
        Path assetDirectory = path(relativeOriginal).getParent();
        deleteQuietly(assetDirectory);
    }

    private void writeThumbnail(BufferedImage source, Path target) throws IOException {
        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min(1.0, (double) THUMBNAIL_MAX_SIZE / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage thumbnail = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(thumbnail, "jpg", target.toFile())) {
            throw new IOException("No JPEG writer available");
        }
    }

    private Path projectRoot(String projectId) {
        UUID.fromString(projectId);
        Path root = Paths.get(properties.getRoot()).toAbsolutePath().normalize();
        Path projectRoot = root.resolve(projectId).normalize();
        ensureInside(root, projectRoot);
        return projectRoot;
    }

    private Path path(String relativePath) {
        Path root = Paths.get(properties.getRoot()).toAbsolutePath().normalize();
        Path path = root.resolve(relativePath).normalize();
        ensureInside(root, path);
        return path;
    }

    private String relative(Path path) {
        Path root = Paths.get(properties.getRoot()).toAbsolutePath().normalize();
        return root.relativize(path.toAbsolutePath().normalize()).toString();
    }

    private void ensureInside(Path root, Path path) {
        if (!path.startsWith(root) || path.equals(root)) {
            throw new ApiException(INTERNAL_SERVER_ERROR, "STORAGE_PATH_INVALID", "存储路径不在允许范围内");
        }
    }

    private String extensionFor(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw invalidFile("UNSUPPORTED_FILE_TYPE", "只支持 JPEG、PNG、WebP");
        };
    }

    private String formatFor(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw invalidFile("UNSUPPORTED_FILE_TYPE", "不支持的图片格式");
        };
    }

    private ApiException invalidFile(String code, String message) {
        return new ApiException(UNPROCESSABLE_ENTITY, code, message);
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                try (var paths = Files.list(path)) {
                    paths.forEach(this::deleteQuietly);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup after a failed upload.
        }
    }

    public record StoredAsset(
            String originalPath,
            String thumbnailPath,
            int width,
            int height) {
    }
}
