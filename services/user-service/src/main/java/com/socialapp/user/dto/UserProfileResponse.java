package com.socialapp.user.dto;

import com.socialapp.user.entity.Gender;
import com.socialapp.user.entity.UserProfile;

import java.time.Instant;
import java.time.LocalDate;

public record UserProfileResponse(
        String id,
        String fullName,
        String avatarUrl,
        String coverUrl,
        String bio,
        LocalDate dob,
        Gender gender,
        String location,
        Instant createdAt,
        Instant updatedAt
) {
    public static UserProfileResponse from(UserProfile p) {
        return new UserProfileResponse(
                p.getId(), p.getFullName(), p.getAvatarUrl(), p.getCoverUrl(), p.getBio(),
                p.getDob(), p.getGender(), p.getLocation(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
