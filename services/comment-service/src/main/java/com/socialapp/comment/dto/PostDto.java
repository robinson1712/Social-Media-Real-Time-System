package com.socialapp.comment.dto;

/**
 * Minimal local projection of post-service's Post resource. Jackson ignores
 * any extra fields present in the actual JSON response, so we only declare
 * what this service needs.
 */
public record PostDto(String id, String authorId) {
}
