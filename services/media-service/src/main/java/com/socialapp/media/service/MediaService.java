package com.socialapp.media.service;

import com.socialapp.common.exception.BadRequestException;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.ResourceNotFoundException;
import com.socialapp.common.exception.UnauthorizedException;
import com.socialapp.media.dto.MediaFileResponse;
import com.socialapp.media.dto.UploadResponse;
import com.socialapp.media.entity.MediaFile;
import com.socialapp.media.entity.MediaPurpose;
import com.socialapp.media.repository.MediaFileRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MediaService {

    private final MinioClient minioClient;
    private final MediaFileRepository mediaFileRepository;

    @Value("${minio.public-endpoint}")
    private String publicEndpoint;

    @Value("${minio.bucket}")
    private String bucket;

    @Transactional
    public UploadResponse upload(String currentUserId, MultipartFile file, String purposeRaw) {
        if (currentUserId == null) {
            throw new UnauthorizedException("Not authenticated");
        }
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File is required");
        }
        MediaPurpose purpose = parsePurpose(purposeRaw);

        String originalFilename = sanitize(file.getOriginalFilename());
        String objectKey = "%s/%s/%s-%s".formatted(purpose.name(), currentUserId, UUID.randomUUID(), originalFilename);

        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
        } catch (Exception e) {
            throw new BadRequestException("Failed to upload file: " + e.getMessage());
        }

        String url = publicEndpoint + "/" + bucket + "/" + objectKey;

        MediaFile mediaFile = MediaFile.builder()
                .ownerId(currentUserId)
                .objectKey(objectKey)
                .url(url)
                .contentType(file.getContentType())
                .purpose(purpose)
                .sizeBytes(file.getSize())
                .createdAt(Instant.now())
                .build();
        mediaFile = mediaFileRepository.save(mediaFile);

        return new UploadResponse(mediaFile.getId(), mediaFile.getUrl());
    }

    @Transactional
    public void delete(String currentUserId, String id) {
        if (currentUserId == null) {
            throw new UnauthorizedException("Not authenticated");
        }
        MediaFile mediaFile = findOrThrow(id);
        if (!mediaFile.getOwnerId().equals(currentUserId)) {
            throw new ForbiddenException("Only the owner may delete this media file");
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(mediaFile.getObjectKey())
                    .build());
        } catch (Exception e) {
            throw new BadRequestException("Failed to delete file from storage: " + e.getMessage());
        }
        mediaFileRepository.delete(mediaFile);
    }

    public MediaFileResponse get(String id) {
        return MediaFileResponse.from(findOrThrow(id));
    }

    private MediaFile findOrThrow(String id) {
        return mediaFileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Media file not found: " + id));
    }

    private MediaPurpose parsePurpose(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("purpose is required");
        }
        try {
            return MediaPurpose.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid purpose: " + raw);
        }
    }

    private String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            return "file";
        }
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
