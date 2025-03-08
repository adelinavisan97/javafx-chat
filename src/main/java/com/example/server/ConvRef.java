package com.example.server;

public class ConvRef {
    private String conversationId;
    private String displayName;

    public ConvRef(String conversationId, String displayName) {
        this.conversationId = conversationId;
        this.displayName = displayName;
    }
    public String getConversationId() { return conversationId; }
    public String getDisplayName() { return displayName; }
}
