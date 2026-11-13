package com.mychatbot.knowledge;

import java.util.List;

public record SearchHit(String documentId, String path, String title, String project, String category,
                        String type, String version, String chunkId, String section, Integer row,
                        String text, double score, boolean approvedKnowledge, List<KnowledgeProposal.Attachment> attachments) {
    public SearchHit(String documentId, String path, String title, String project, String category,
                     String type, String version, String chunkId, String section, Integer row,
                     String text, double score, boolean approvedKnowledge) {
        this(documentId, path, title, project, category, type, version, chunkId, section, row,
                text, score, approvedKnowledge, List.of());
    }
}
