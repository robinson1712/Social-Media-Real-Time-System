package com.socialapp.reels.service;

import com.socialapp.common.event.KafkaTopics;
import com.socialapp.common.event.ReelCreatedEvent;
import com.socialapp.common.exception.BadRequestException;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.ResourceNotFoundException;
import com.socialapp.common.moderation.ProfanityFilter;
import com.socialapp.common.security.CurrentUserContext;
import com.socialapp.reels.document.Reel;
import com.socialapp.reels.dto.CreateReelRequest;
import com.socialapp.reels.repository.ReelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReelService {

    private final ReelRepository reelRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public Reel createReel(CreateReelRequest request) {
        rejectIfProfane(request.caption());
        String authorId = CurrentUserContext.getUserId();

        Reel reel = Reel.builder()
                .id(UUID.randomUUID().toString())
                .authorId(authorId)
                .videoUrl(request.videoUrl())
                .thumbnailUrl(request.thumbnailUrl())
                .caption(request.caption())
                .createdAt(Instant.now())
                .build();

        Reel saved = reelRepository.save(reel);

        kafkaTemplate.send(KafkaTopics.REEL_CREATED,
                saved.getAuthorId(),
                new ReelCreatedEvent(saved.getId(), saved.getAuthorId(), Instant.now()));

        return saved;
    }

    public Reel getReel(String id) {
        return getReelOrThrow(id);
    }

    public Reel viewReel(String id) {
        Reel reel = getReelOrThrow(id);
        reel.setViewCount(reel.getViewCount() + 1);
        return reelRepository.save(reel);
    }

    public Page<Reel> getFeed(Pageable pageable) {
        return reelRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public Page<Reel> getReelsByAuthor(String authorId, Pageable pageable) {
        return reelRepository.findByAuthorIdOrderByCreatedAtDesc(authorId, pageable);
    }

    public void deleteReel(String id) {
        Reel reel = getReelOrThrow(id);
        String userId = CurrentUserContext.getUserId();
        if (!reel.getAuthorId().equals(userId)) {
            throw new ForbiddenException("You are not allowed to delete this reel");
        }
        reelRepository.delete(reel);
    }

    private Reel getReelOrThrow(String id) {
        return reelRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Reel not found: " + id));
    }

    /** Driven by moderation-service's ContentRemovedEvent — idempotent, a no-op if already gone. */
    public void removeForModeration(String id) {
        reelRepository.findById(id).ifPresent(reelRepository::delete);
    }

    private void rejectIfProfane(String content) {
        if (ProfanityFilter.containsProfanity(content)) {
            throw new BadRequestException("Content violates community guidelines");
        }
    }
}
