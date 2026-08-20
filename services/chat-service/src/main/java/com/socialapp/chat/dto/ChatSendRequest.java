package com.socialapp.chat.dto;

public record ChatSendRequest(String conversationId, String content, String mediaUrl) {
}
