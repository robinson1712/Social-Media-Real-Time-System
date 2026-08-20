package com.socialapp.common.event;

import java.time.Instant;

public record UserRegisteredEvent(String userId, String email, String fullName, Instant occurredAt) {
}
