package com.getjobs.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 管理 Boss 图片简历。文件保存在程序启动目录之外，不写入 classpath 或 JAR。
 *
 * 支持两种形态：
 * 1) 单文件简历（向后兼容）：data/boss/resume/resume.{png|jpg|gif}
 * 2) 多简历库：data/boss/resume/library/resume-<id>.<ext>，元数据在 library/index.json
 *    多简历库生效时，getCurrentImagePath() 优先返回「默认简历」。
 */
@Service
public class BossResumeImageService {

    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    public static final String PREVIEW_URL = "/api/boss/config/resume-image/content";

    private static final List<String> SUPPORTED_EXTENSIONS = List.of("png", "jpg", "gif");
    private final Path storageDirectory = Paths.get(System.getProperty("user.dir"), "data", "boss", "resume")
            .toAbsolutePath()
            .normalize();
    private final Path libraryDir = storageDirectory.resolve("library");
    private final Path indexFile = libraryDir.resolve("index.json");
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ===================== 单文件简历（向后兼容） =====================

    public synchronized ResumeImageInfo save(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的图片简历");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("图片大小不能超过 10MB");
        }

        Files.createDirectories(storageDirectory);
        Path temporaryFile = Files.createTempFile(storageDirectory, "resume-upload-", ".tmp");
        try {
            file.transferTo(temporaryFile);
            if (Files.size(temporaryFile) > MAX_FILE_SIZE) {
                throw new IllegalArgumentException("图片大小不能超过 10MB");
            }

            String extension = detectImageExtension(temporaryFile);
            Path target = storageDirectory.resolve("resume." + extension);
            moveReplacing(temporaryFile, target);
            deleteOtherResumeImages(target);
            return toInfo(target);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    public synchronized Optional<ResumeImageInfo> getCurrentImage() {
        return getCurrentImagePath().map(path -> {
            try {
                return toInfo(path);
            } catch (IOException e) {
                return null;
            }
        });
    }

    /**
     * 优先返回多简历库中的「默认简历」；库为空时回退到单文件简历。
     */
    public synchronized Optional<Path> getCurrentImagePath() {
        Optional<Path> libraryDefault = getDefaultResumePath();
        if (libraryDefault.isPresent()) {
            return libraryDefault;
        }
        return findCurrentImagePath();
    }

    public Map<String, Object> getMetadata() {
        return getCurrentImage()
                .map(info -> Map.<String, Object>of(
                        "exists", true,
                        "fileName", info.fileName(),
                        "contentType", info.contentType(),
                        "size", info.size(),
                        "updatedAt", info.updatedAt(),
                        "previewUrl", PREVIEW_URL,
                        "source", "library"
                ))
                .orElseGet(() -> Map.of("exists", false));
    }

    private Optional<Path> findCurrentImagePath() {
        for (String extension : SUPPORTED_EXTENSIONS) {
            Path candidate = storageDirectory.resolve("resume." + extension);
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    // ===================== 多简历库 =====================

    public synchronized ResumeMeta saveResume(MultipartFile file, String name) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的图片简历");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("图片大小不能超过 10MB");
        }
        String trimmedName = (name == null || name.isBlank()) ? "简历" : name.trim();

        Files.createDirectories(libraryDir);
        Path temporaryFile = Files.createTempFile(libraryDir, "resume-upload-", ".tmp");
        try {
            file.transferTo(temporaryFile);
            if (Files.size(temporaryFile) > MAX_FILE_SIZE) {
                throw new IllegalArgumentException("图片大小不能超过 10MB");
            }
            String extension = detectImageExtension(temporaryFile);
            long id = nextResumeId();
            String fileName = "resume-" + id + "." + extension;
            Path target = libraryDir.resolve(fileName);
            moveReplacing(temporaryFile, target);

            List<ResumeMeta> index = readIndex();
            boolean isDefault = index.isEmpty();
            index.add(new ResumeMeta(id, trimmedName, fileName, isDefault));
            writeIndex(index);
            return new ResumeMeta(id, trimmedName, fileName, isDefault);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    public synchronized List<ResumeMeta> listResumes() {
        return readIndex();
    }

    public synchronized void setDefault(long id) {
        List<ResumeMeta> index = readIndex();
        boolean found = false;
        List<ResumeMeta> next = new ArrayList<>();
        for (ResumeMeta meta : index) {
            boolean target = meta.id() == id;
            found |= target;
            next.add(new ResumeMeta(meta.id(), meta.name(), meta.fileName(), target));
        }
        if (!found) {
            throw new IllegalArgumentException("简历不存在：id=" + id);
        }
        try {
            writeIndex(next);
        } catch (IOException e) {
            throw new UncheckedIOException("保存简历索引失败", e);
        }
    }

    public synchronized void deleteResume(long id) {
        List<ResumeMeta> index = readIndex();
        Optional<ResumeMeta> removed = index.stream().filter(m -> m.id() == id).findFirst();
        if (removed.isEmpty()) {
            throw new IllegalArgumentException("简历不存在：id=" + id);
        }
        try {
            Files.deleteIfExists(libraryDir.resolve(removed.get().fileName()));
        } catch (IOException ignored) {
        }
        List<ResumeMeta> next = new ArrayList<>();
        boolean wasDefault = removed.get().isDefault();
        for (ResumeMeta meta : index) {
            if (meta.id() != id) {
                next.add(meta);
            }
        }
        if (wasDefault && !next.isEmpty()) {
            ResumeMeta first = next.get(0);
            next.set(0, new ResumeMeta(first.id(), first.name(), first.fileName(), true));
        }
        try {
            writeIndex(next);
        } catch (IOException e) {
            throw new UncheckedIOException("保存简历索引失败", e);
        }
    }

    public Optional<Path> getResumePath(long id) {
        return readIndex().stream()
                .filter(m -> m.id() == id)
                .findFirst()
                .map(m -> libraryDir.resolve(m.fileName()));
    }

    public Optional<Path> getDefaultResumePath() {
        List<ResumeMeta> index = readIndex();
        return index.stream()
                .filter(ResumeMeta::isDefault)
                .findFirst()
                .or(() -> index.stream().min(Comparator.comparingLong(ResumeMeta::id)))
                .map(m -> libraryDir.resolve(m.fileName()));
    }

    public Optional<Long> getDefaultResumeId() {
        List<ResumeMeta> index = readIndex();
        return index.stream()
                .filter(ResumeMeta::isDefault)
                .findFirst()
                .or(() -> index.stream().min(Comparator.comparingLong(ResumeMeta::id)))
                .map(ResumeMeta::id);
    }

    public Optional<ResumeMeta> getResumeMeta(long id) {
        return readIndex().stream().filter(m -> m.id() == id).findFirst();
    }

    public Map<String, Object> getResumeMetadata(long id) {
        return getResumeMeta(id)
                .map(meta -> {
                    Path path = libraryDir.resolve(meta.fileName());
                    try {
                        ResumeImageInfo info = toInfo(path);
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("exists", true);
                        m.put("id", meta.id());
                        m.put("name", meta.name());
                        m.put("fileName", meta.fileName());
                        m.put("isDefault", meta.isDefault());
                        m.put("contentType", info.contentType());
                        m.put("size", info.size());
                        m.put("updatedAt", info.updatedAt());
                        m.put("previewUrl", "/api/boss/config/resume/" + meta.id() + "/content");
                        return m;
                    } catch (IOException e) {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("exists", false);
                        m.put("id", meta.id());
                        return m;
                    }
                })
                .orElseGet(() -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("exists", false);
                    return m;
                });
    }

    private long nextResumeId() {
        return readIndex().stream().mapToLong(ResumeMeta::id).max().orElse(0L) + 1L;
    }

    @SuppressWarnings("unchecked")
    private List<ResumeMeta> readIndex() {
        if (!Files.isRegularFile(indexFile)) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(indexFile.toFile(),
                    new TypeReference<List<Map<String, Object>>>() {});
            List<ResumeMeta> result = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                long id = ((Number) m.get("id")).longValue();
                String name = String.valueOf(m.get("name"));
                String fileName = String.valueOf(m.get("fileName"));
                boolean isDefault = Boolean.parseBoolean(String.valueOf(m.get("isDefault")));
                result.add(new ResumeMeta(id, name, fileName, isDefault));
            }
            return result;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void writeIndex(List<ResumeMeta> list) throws IOException {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (ResumeMeta m : list) {
            raw.add(Map.of(
                    "id", m.id(),
                    "name", m.name(),
                    "fileName", m.fileName(),
                    "isDefault", m.isDefault()
            ));
        }
        objectMapper.writeValue(indexFile.toFile(), raw);
    }

    // ===================== 公共工具 =====================

    private String detectImageExtension(Path image) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(image.toFile())) {
            if (input == null) {
                throw new IllegalArgumentException("无法读取图片文件");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("文件不是有效的 PNG、JPG 或 GIF 图片");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new IllegalArgumentException("图片尺寸无效");
                }
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                return switch (format) {
                    case "png" -> "png";
                    case "jpeg", "jpg" -> "jpg";
                    case "gif" -> "gif";
                    default -> throw new IllegalArgumentException("仅支持 PNG、JPG、JPEG 和 GIF 图片");
                };
            } finally {
                reader.dispose();
            }
        }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteOtherResumeImages(Path retained) throws IOException {
        for (String extension : SUPPORTED_EXTENSIONS) {
            Path candidate = storageDirectory.resolve("resume." + extension);
            if (!candidate.equals(retained)) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private ResumeImageInfo toInfo(Path path) throws IOException {
        String fileName = path.getFileName().toString();
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String contentType = switch (extension) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            default -> "image/jpeg";
        };
        return new ResumeImageInfo(
                path,
                fileName,
                contentType,
                Files.size(path),
                Files.getLastModifiedTime(path).toMillis()
        );
    }

    public record ResumeMeta(long id, String name, String fileName, boolean isDefault) {
    }

    public record ResumeImageInfo(Path path, String fileName, String contentType, long size, long updatedAt) {
    }
}
