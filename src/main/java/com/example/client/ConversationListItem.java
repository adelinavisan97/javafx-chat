package com.example.client;

// This class holds the conversation ID and the displayName.
// The toString() method returns displayName, so ListView will display only the name.
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
        // Only show the display name in the ListView
        return displayName;
    }
}
