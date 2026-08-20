package com.socialapp.dating.dto;

import com.socialapp.dating.entity.GenderPreference;

import java.util.List;

public record UpsertProfileRequest(
        String bio,
        List<String> interests,
        int minAgePreference,
        int maxAgePreference,
        GenderPreference genderPreference,
        List<String> photos
) {
}
