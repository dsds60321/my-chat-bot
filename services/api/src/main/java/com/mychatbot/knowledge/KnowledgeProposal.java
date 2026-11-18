package com.mychatbot.knowledge;

import java.util.List;

public record KnowledgeProposal(String id, Kind kind, String project, String sourceDocumentId, String sourceVersion,
                                String sourceTitle, String title, String content, String author, State state,
                                String createdAt, String updatedAt, List<Attachment> attachments, String folderPath) {
    public KnowledgeProposal {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        folderPath = folderPath == null && kind == Kind.NEW ? project : folderPath;
    }

    public KnowledgeProposal(String id, Kind kind, String project, String sourceDocumentId, String sourceVersion,
            String sourceTitle, String title, String content, String author, State state,
            String createdAt, String updatedAt, List<Attachment> attachments) {
        this(id, kind, project, sourceDocumentId, sourceVersion, sourceTitle, title, content, author, state,
                createdAt, updatedAt, attachments, kind == Kind.NEW ? project : null);
    }

    public record Attachment(String documentId, String version, String filename, String description, boolean image, long bytes) {
        public Attachment(String documentId, String version, String filename, String description, boolean image) {
            this(documentId, version, filename, description, image, 0);
        }
    }

    public enum Kind { NEW, AMENDMENT, REPLACEMENT }
    public enum State { PENDING_APPROVAL, APPROVED, REJECTED, WITHDRAWN }

    KnowledgeProposal withState(State next, String time) {
        return new KnowledgeProposal(id, kind, project, sourceDocumentId, sourceVersion, sourceTitle, title,
                content, author, next, createdAt, time, attachments, folderPath);
    }

    @Override public String toString() { return "KnowledgeProposal[" + id + ", " + state + "]"; }

    public record Draft(Kind kind, String project, String sourceDocumentId, String sourceVersion,
                        String sourceTitle, String before, String title, String content, String folderPath) {
        public Draft(Kind kind, String project, String sourceDocumentId, String sourceVersion,
                String sourceTitle, String before, String title, String content) {
            this(kind, project, sourceDocumentId, sourceVersion, sourceTitle, before, title, content, project);
        }
        @Override public String toString() { return "KnowledgeProposal.Draft[" + kind + "]"; }
    }
}
