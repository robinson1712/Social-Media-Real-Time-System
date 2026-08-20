package com.socialapp.post.event;

import com.socialapp.common.enums.TargetType;
import com.socialapp.common.event.CommentCreatedEvent;
import com.socialapp.common.event.KafkaTopics;
import com.socialapp.common.event.ReactionEvent;
import com.socialapp.post.service.PostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostEventListener {

    private final PostService postService;

    @KafkaListener(topics = KafkaTopics.COMMENT_CREATED, groupId = "${spring.kafka.consumer.group-id}")
    public void onCommentCreated(CommentCreatedEvent event) {
        if (event.parentCommentId() == null) {
            postService.incrementCommentCount(event.postId());
        }
    }

    @KafkaListener(topics = KafkaTopics.REACTION, groupId = "${spring.kafka.consumer.group-id}")
    public void onReaction(ReactionEvent event) {
        if (!TargetType.POST.name().equals(event.targetType())) {
            return;
        }
        postService.applyReactionDelta(event.targetId(), event.removed());
    }
}
