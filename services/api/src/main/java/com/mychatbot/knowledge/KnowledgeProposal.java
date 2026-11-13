package com.mychatbot.knowledge;

public record KnowledgeProposal(String id, Kind kind, String project, String sourceDocumentId, String sourceVersion,
                                String sourceTitle, String title, String content, String author, State state,
                                String createdAt, String updatedAt) {
    public enum Kind { NEW, AMENDMENT }
    public enum State { PENDING_APPROVAL, APPROVED, REJECTED, WITHDRAWN }

    KnowledgeProposal withState(State next, String time) {
        return new KnowledgeProposal(id, kind, project, sourceDocumentId, sourceVersion, sourceTitle, title,
                content, author, next, createdAt, time);
    }

    @Override public String toString() { return "KnowledgeProposal[" + id + ", " + state + "]"; }

    public record Draft(Kind kind, String project, String sourceDocumentId, String sourceVersion,
                        String sourceTitle, String before, String title, String content) {
        @Override public String toString() { return "KnowledgeProposal.Draft[" + kind + "]"; }
    }
}
