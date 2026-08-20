package com.socialapp.dating.service;

import com.socialapp.common.event.KafkaTopics;
import com.socialapp.common.event.MatchEvent;
import com.socialapp.common.exception.ConflictException;
import com.socialapp.common.exception.ResourceNotFoundException;
import com.socialapp.common.security.CurrentUserContext;
import com.socialapp.dating.dto.SwipeRequest;
import com.socialapp.dating.dto.SwipeResponse;
import com.socialapp.dating.dto.UpsertProfileRequest;
import com.socialapp.dating.entity.DatingProfile;
import com.socialapp.dating.entity.GenderPreference;
import com.socialapp.dating.entity.Match;
import com.socialapp.dating.entity.Swipe;
import com.socialapp.dating.entity.SwipeAction;
import com.socialapp.dating.repository.DatingProfileRepository;
import com.socialapp.dating.repository.MatchRepository;
import com.socialapp.dating.repository.SwipeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class DatingService {

    private final DatingProfileRepository datingProfileRepository;
    private final SwipeRepository swipeRepository;
    private final MatchRepository matchRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public DatingProfile upsertProfile(UpsertProfileRequest request) {
        String userId = CurrentUserContext.getUserId();
        DatingProfile profile = datingProfileRepository.findById(userId).orElse(null);
        if (profile == null) {
            profile = DatingProfile.builder()
                    .id(userId)
                    .build();
        }
        if (request.bio() != null) {
            profile.setBio(request.bio());
        }
        if (request.interests() != null) {
            profile.setInterests(new ArrayList<>(request.interests()));
        }
        profile.setMinAgePreference(request.minAgePreference());
        profile.setMaxAgePreference(request.maxAgePreference());
        profile.setGenderPreference(request.genderPreference() != null ? request.genderPreference() : GenderPreference.ANY);
        if (request.photos() != null) {
            profile.setPhotos(new ArrayList<>(request.photos()));
        }
        return datingProfileRepository.save(profile);
    }

    public DatingProfile getMyProfile() {
        String userId = CurrentUserContext.getUserId();
        return datingProfileRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Dating profile not found for current user"));
    }

    public Page<DatingProfile> getCandidates(Pageable pageable) {
        String userId = CurrentUserContext.getUserId();
        return datingProfileRepository.findCandidates(userId, pageable);
    }

    public SwipeResponse swipe(SwipeRequest request) {
        String swiperId = CurrentUserContext.getUserId();
        String targetId = request.targetId();

        if (swipeRepository.existsBySwiperIdAndTargetId(swiperId, targetId)) {
            throw new ConflictException("You already swiped on this user");
        }

        Swipe swipe = Swipe.builder()
                .swiperId(swiperId)
                .targetId(targetId)
                .action(request.action())
                .build();
        swipeRepository.save(swipe);

        boolean matched = false;
        if (request.action() == SwipeAction.LIKE) {
            boolean reciprocalLike = swipeRepository
                    .findBySwiperIdAndTargetIdAndAction(targetId, swiperId, SwipeAction.LIKE)
                    .isPresent();
            if (reciprocalLike) {
                matched = true;
                String user1Id = swiperId.compareTo(targetId) < 0 ? swiperId : targetId;
                String user2Id = swiperId.compareTo(targetId) < 0 ? targetId : swiperId;
                if (matchRepository.findByUser1IdAndUser2Id(user1Id, user2Id).isEmpty()) {
                    Match match = Match.builder()
                            .user1Id(user1Id)
                            .user2Id(user2Id)
                            .build();
                    Match saved = matchRepository.save(match);
                    MatchEvent event = new MatchEvent(saved.getId(), saved.getUser1Id(), saved.getUser2Id(), Instant.now());
                    kafkaTemplate.send(KafkaTopics.MATCH, saved.getId(), event);
                }
            }
        }

        return new SwipeResponse(true, matched);
    }

    public Page<Match> getMatches(Pageable pageable) {
        String userId = CurrentUserContext.getUserId();
        return matchRepository.findByUser1IdOrUser2Id(userId, userId, pageable);
    }
}
