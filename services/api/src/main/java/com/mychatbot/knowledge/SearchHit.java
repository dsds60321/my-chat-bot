package com.mychatbot.knowledge;

public record SearchHit(String documentId, String path, String title, String project, String category,
                        String type, String version, String chunkId, String section, Integer row,
                        String text, double score, boolean approvedKnowledge) {}
