package com.classos.files;

import com.classos.common.ApiException;
import com.classos.config.ClassosProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The file storage layer. Rules:
 *  - Files are stored as <storage dir>/<random UUID>. The uploader's name is only metadata,
 *    so it can never be used to write outside the directory (path traversal).
 *  - Only an allowlist of extensions is accepted. HTML and SVG are refused: if served from
 *    our origin they could run scripts in another user's browser.
 *  - Downloads always use Content-Disposition: attachment, a content type we choose from
 *    the extension (never the browser's claim), and X-Content-Type-Options: nosniff.
 *  - store() must run inside a transaction: if the transaction rolls back, the file just
 *    written is deleted, so the disk and the database never disagree.
 *  - Access checks are NOT done here; each caller checks who may see the owning record.
 */
@Service
public class FileStorageService {

    private static final Map<String, String> ALLOWED = new TreeMap<>(Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("odt", "application/vnd.oasis.opendocument.text"),
            Map.entry("txt", "text/plain"), Map.entry("md", "text/plain"), Map.entry("csv", "text/csv"),
            Map.entry("zip", "application/zip"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("c", "text/plain"), Map.entry("cpp", "text/plain"), Map.entry("h", "text/plain"),
            Map.entry("hpp", "text/plain"), Map.entry("java", "text/plain"), Map.entry("py", "text/plain"),
            Map.entry("js", "text/plain"), Map.entry("sql", "text/plain"), Map.entry("ipynb", "application/json")));

    private final JdbcTemplate jdbc;
    private final Path root;
    private final long maxBytes;

    public FileStorageService(JdbcTemplate jdbc, ClassosProperties props) throws IOException {
        this.jdbc = jdbc;
        this.root = Path.of(props.storage().dir()).toAbsolutePath().normalize();
        this.maxBytes = props.storage().maxUploadMb() * 1024L * 1024L;
        Files.createDirectories(root);
    }

    public static List<String> allowedExtensions() {
        return List.copyOf(ALLOWED.keySet());
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Only the last path segment, without control characters, at most 255 characters. */
    static String cleanName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").trim();
        if (name.isEmpty()) name = "file";
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    public StoredFile store(MultipartFile file, long uploaderId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("store() must be called inside a transaction");
        }
        if (file == null || file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a file to upload.");
        if (file.getSize() > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Files can be at most " + (maxBytes / 1024 / 1024) + " MB.");
        }
        String name = cleanName(file.getOriginalFilename());
        String type = ALLOWED.get(extension(name));
        if (type == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This type of file can't be uploaded. Allowed: "
                    + String.join(", ", ALLOWED.keySet()) + ".");
        }

        UUID id = UUID.randomUUID();
        Path target = root.resolve(id.toString());
        String sha256;
        try (InputStream in = new DigestInputStream(file.getInputStream(), MessageDigest.getInstance("SHA-256"))) {
            Files.copy(in, target);
            sha256 = HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            deleteQuietly(target);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "The file couldn't be saved. Try again.");
        }
        // Undo the disk write if the surrounding transaction does not commit.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) deleteQuietly(target);
            }
        });
        jdbc.update("""
                INSERT INTO stored_files (id, original_name, content_type, size_bytes, sha256, uploaded_by)
                VALUES (?, ?, ?, ?, ?, ?)""", id, name, type, file.getSize(), sha256, uploaderId);
        return new StoredFile(id, name, type, file.getSize());
    }

    public StoredFile find(UUID id) {
        List<StoredFile> found = jdbc.query(
                "SELECT id, original_name, content_type, size_bytes FROM stored_files WHERE id = ?",
                (rs, i) -> new StoredFile(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4)), id);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "File not found.");
        return found.get(0);
    }

    /** Removes the record now and the bytes once the transaction commits (a rollback keeps both). */
    public void deleteAfterCommit(UUID id) {
        jdbc.update("DELETE FROM stored_files WHERE id = ?", id);
        Path path = root.resolve(id.toString());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteQuietly(path);
                }
            });
        } else {
            deleteQuietly(path);
        }
    }

    /** Callers must check access before calling this. */
    public ResponseEntity<Resource> download(UUID id) {
        StoredFile f = find(id);
        Path path = root.resolve(id.toString());
        if (!Files.isRegularFile(path)) throw new ApiException(HttpStatus.NOT_FOUND, "File not found.");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .contentLength(f.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.originalName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new FileSystemResource(path));
    }

    boolean existsOnDisk(UUID id) {
        return Files.exists(root.resolve(id.toString()));
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // An orphaned file wastes space but exposes nothing: it is unreachable without a database row.
        }
    }
}
