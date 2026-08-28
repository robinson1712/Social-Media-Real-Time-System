package com.socialapp.user.kafka;

import com.socialapp.common.event.UserRegisteredEvent;
import com.socialapp.user.entity.UserProfile;
import com.socialapp.user.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for UserRegisteredListener — invokes the @KafkaListener method
 * directly (no embedded/real Kafka broker needed) against a mocked repository.
 */
@ExtendWith(MockitoExtension.class)
class UserRegisteredListenerTest {

    @Mock
    private UserProfileRepository userProfileRepository;

    private UserRegisteredListener listener;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        listener = new UserRegisteredListener(userProfileRepository);
    }

    @Test
    void onUserRegistered_newUser_createsProfileWithFullName() {
        UserRegisteredEvent event = new UserRegisteredEvent("user-1", "alice@social.app", "Alice Wonderland", Instant.now());
        when(userProfileRepository.existsById("user-1")).thenReturn(false);
        when(userProfileRepository.save(any(UserProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        listener.onUserRegistered(event);

        ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("user-1");
        assertThat(captor.getValue().getFullName()).isEqualTo("Alice Wonderland");
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
        assertThat(captor.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    void onUserRegistered_profileAlreadyExists_skipsIdempotently() {
        UserRegisteredEvent event = new UserRegisteredEvent("user-1", "alice@social.app", "Alice Wonderland", Instant.now());
        when(userProfileRepository.existsById("user-1")).thenReturn(true);

        listener.onUserRegistered(event);

        verify(userProfileRepository, never()).save(any());
    }
}
