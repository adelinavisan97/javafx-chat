package com.example.client;

public class ConversationListItem {
    private String conversationId;
    private String displayName;

    public ConversationListItem(String conversationId, String displayName) {
        this.conversationId = conversationId;
        this.displayName = displayName;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName; // so ListView shows only the display name
    }
}
