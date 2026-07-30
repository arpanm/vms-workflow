package com.vms.workflow.application;

import com.vms.workflow.api.CertificationDtos.ArtifactUploadView;
import com.vms.workflow.security.CertificationAuthorizationService;
import com.vms.workflow.security.CertificationAuthorizationService.Party;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

@Service
public class CertificationArtifactService {
    private static final long MAX_BYTES = 25L * 1024L * 1024L;
    private static final Set<String> CLASSIFICATIONS =
        Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private final JdbcTemplate jdbc;
    private final CertificationAuthorizationService authorization;
    private final Path root;

    public CertificationArtifactService(
        JdbcTemplate jdbc,
        CertificationAuthorizationService authorization,
        @Value("${vms.certification.local-artifact-root:./.local/artifacts}") String root
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Transactional
    public ArtifactUploadView upload(String subject, UUID monthId, String classification,
                                     MultipartFile file) {
        authorization.requireMonthParty(subject, monthId,
            CertificationAuthorizationService.SUBMISSION_MANAGE, Party.VENDOR);
        if (!CLASSIFICATIONS.contains(classification)) {
            throw new IllegalArgumentException("Unsupported evidence classification.");
        }
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("Evidence must contain 1 byte to 25 MiB.");
        }
        UUID engagementId = jdbc.queryForObject(
            "SELECT engagement_id FROM engagement_months WHERE id = ?",
            UUID.class, monthId);
        UUID artifactId = UUID.randomUUID();
        String safeName = safeName(file.getOriginalFilename());
        byte[] bytes = bytes(file);
        String sha256 = sha256(bytes);
        String objectKey = "local/" + engagementId + "/" + artifactId;
        Path target = resolve(objectKey);
        String declared = normalizedMime(file.getContentType());
        String sniffed = sniff(bytes, safeName);
        jdbc.update("""
            INSERT INTO evidence_artifacts
                (id, engagement_id, engagement_month_id, artifact_kind,
                 object_key, object_version, original_name, safe_name,
                 declared_mime_type, sniffed_mime_type, size_bytes, sha256,
                 classification, scan_status, source, uploader_subject,
                 provider_status)
            VALUES (?, ?, ?, 'OBJECT', ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'PENDING', 'VENDOR', ?, 'AVAILABLE')
            """, artifactId, engagementId, monthId, objectKey, sha256,
            file.getOriginalFilename(), safeName, declared, sniffed, bytes.length,
            sha256, classification, subject);
        // Persist metadata first so a storage failure rolls the transaction back
        // instead of leaving a database record that points to missing bytes.
        store(target, bytes);
        deleteObjectIfTransactionRollsBack(target);
        audit(monthId, artifactId, "EVIDENCE_ARTIFACT_UPLOADED", subject, "PENDING");
        return view(artifactId);
    }

    @Transactional
    public ArtifactUploadView scan(String subject, UUID artifactId) {
        ArtifactRow artifact = jdbc.query("""
            SELECT engagement_month_id, object_key, scan_status, sha256, safe_name
            FROM evidence_artifacts WHERE id = ?
            """, rs -> rs.next()
                ? new ArtifactRow(rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getString(3), rs.getString(4), rs.getString(5))
                : null, artifactId);
        if (artifact == null) {
            throw new jakarta.persistence.EntityNotFoundException("Resource not found.");
        }
        authorization.requireMonthParty(subject, artifact.monthId(),
            CertificationAuthorizationService.SUBMISSION_MANAGE, Party.VENDOR);
        if (!"PENDING".equals(artifact.scanStatus())) {
            return view(artifactId);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(resolve(artifact.objectKey()));
        } catch (IOException exception) {
            jdbc.update("""
                UPDATE evidence_artifacts
                SET scan_status = 'UNKNOWN', provider_status = 'ACTION_REQUIRED'
                WHERE id = ?
                """, artifactId);
            audit(artifact.monthId(), artifactId,
                "EVIDENCE_ARTIFACT_SCANNED", subject, "UNKNOWN");
            return view(artifactId);
        }
        boolean integrityMismatch = !sha256(bytes).equals(artifact.sha256());
        boolean malwareSignature = containsAscii(
            bytes, "EICAR-STANDARD-ANTIVIRUS-TEST-FILE");
        boolean executable = executable(bytes, artifact.safeName());
        boolean rejected = integrityMismatch || malwareSignature || executable
            || !allowlistedContent(bytes, artifact.safeName());
        jdbc.update("""
            UPDATE evidence_artifacts
            SET scan_status = ?, provider_status = 'AVAILABLE'
            WHERE id = ?
            """, rejected ? "FAILED" : "PASSED", artifactId);
        audit(artifact.monthId(), artifactId, "EVIDENCE_ARTIFACT_SCANNED",
            subject, rejected ? "FAILED" : "PASSED");
        return view(artifactId);
    }

    private void audit(UUID monthId, UUID artifactId, String eventType,
                       String subject, String result) {
        UUID policyId = jdbc.query("""
            SELECT policy.id
            FROM engagement_months month
            JOIN certification_policy_versions policy
              ON policy.engagement_id = month.engagement_id
             AND policy.status = 'ACTIVE'
            WHERE month.id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, monthId);
        jdbc.update("""
            INSERT INTO certification_audit_events
                (id, engagement_month_id, event_type, actor_subject,
                 authority_snapshot, object_type, object_id, source, result,
                 correlation_id, policy_version_id)
            VALUES (?, ?, ?, ?, '{"resolvedServerSide":true}'::jsonb,
                    'evidence_artifact', ?, 'IN_APP', ?, ?, ?)
            """, UUID.randomUUID(), monthId, eventType, subject, artifactId,
            result, UUID.randomUUID(), policyId);
    }

    private ArtifactUploadView view(UUID id) {
        return jdbc.query("""
            SELECT id, engagement_month_id, safe_name, classification, scan_status,
                   size_bytes, sha256, recorded_at
            FROM evidence_artifacts WHERE id = ?
            """, rs -> {
                if (!rs.next()) {
                    throw new jakarta.persistence.EntityNotFoundException("Resource not found.");
                }
                return new ArtifactUploadView(
                    rs.getObject("id", UUID.class),
                    rs.getObject("engagement_month_id", UUID.class),
                    rs.getString("safe_name"), rs.getString("classification"),
                    rs.getString("scan_status"), rs.getLong("size_bytes"),
                    rs.getString("sha256"),
                    rs.getObject("recorded_at", OffsetDateTime.class));
            }, id);
    }

    private byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Evidence upload could not be read.", exception);
        }
    }

    private void store(Path target, byte[] bytes) {
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            hardenPermissions(root, "rwx------");
            hardenPermissions(target.getParent(), "rwx------");
            temporary = Files.createTempFile(target.getParent(), "upload-", ".pending");
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            hardenPermissions(target, "rw-------");
        } catch (IOException exception) {
            throw new IllegalStateException("Local evidence storage is unavailable.", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup; the private pending file is never addressable.
                }
            }
        }
    }

    private void hardenPermissions(Path path, String permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems rely on their platform ACL and private root.
        }
    }

    private void deleteObjectIfTransactionRollsBack(Path target) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        try {
                            Files.deleteIfExists(target);
                        } catch (IOException ignored) {
                            // A later storage reconciliation can remove an orphaned private object.
                        }
                    }
                }
            });
    }

    private Path resolve(String objectKey) {
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Invalid evidence object key.");
        }
        return resolved;
    }

    private String safeName(String original) {
        String name = original == null ? "evidence"
            : original.replace('\\', '/').substring(original.replace('\\', '/').lastIndexOf('/') + 1);
        String safe = name.replaceAll("[^A-Za-z0-9._ -]", "_").strip();
        return safe.isBlank() ? "evidence" : safe.substring(0, Math.min(safe.length(), 255));
    }

    private String normalizedMime(String value) {
        return value == null || value.isBlank() ? "application/octet-stream" : value;
    }

    private String sniff(byte[] bytes, String name) {
        if (bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P'
            && bytes[2] == 'D' && bytes[3] == 'F') return "application/pdf";
        if (bytes.length >= 4 && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
            && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if (name.toLowerCase().endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private boolean executable(byte[] bytes, String safeName) {
        String lower = safeName.toLowerCase();
        boolean executableExtension = lower.endsWith(".exe") || lower.endsWith(".dll")
            || lower.endsWith(".com") || lower.endsWith(".bat") || lower.endsWith(".cmd")
            || lower.endsWith(".sh");
        boolean windowsExecutable = bytes.length >= 2 && bytes[0] == 'M' && bytes[1] == 'Z';
        boolean elfExecutable = bytes.length >= 4
            && bytes[0] == 0x7f && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F';
        return executableExtension || windowsExecutable || elfExecutable;
    }

    private boolean allowlistedContent(byte[] bytes, String safeName) {
        String lower = safeName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return startsWith(bytes, new byte[]{'%', 'P', 'D', 'F'});
        }
        if (lower.endsWith(".png")) {
            return startsWith(bytes, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return startsWith(bytes, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
        }
        if (lower.endsWith(".docx") || lower.endsWith(".xlsx")
            || lower.endsWith(".pptx")) {
            return startsWith(bytes, new byte[]{'P', 'K', 3, 4});
        }
        if (lower.endsWith(".msg")) {
            return startsWith(bytes, new byte[]{
                (byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0});
        }
        if (lower.endsWith(".mp4")) {
            return bytes.length >= 12 && bytes[4] == 'f' && bytes[5] == 't'
                && bytes[6] == 'y' && bytes[7] == 'p';
        }
        if (lower.endsWith(".txt") || lower.endsWith(".csv")
            || lower.endsWith(".json") || lower.endsWith(".eml")) {
            int inspected = Math.min(bytes.length, 4096);
            for (int index = 0; index < inspected; index++) {
                if (bytes[index] == 0) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean containsAscii(byte[] bytes, String signature) {
        byte[] expected = signature.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int offset = 0; offset <= bytes.length - expected.length; offset++) {
            for (int index = 0; index < expected.length; index++) {
                if (bytes[offset + index] != expected[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private record ArtifactRow(
        UUID monthId,
        String objectKey,
        String scanStatus,
        String sha256,
        String safeName
    ) {
    }
}
