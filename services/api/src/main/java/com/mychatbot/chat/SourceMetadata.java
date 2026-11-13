package com.mychatbot.chat;

import com.mychatbot.knowledge.SearchHit;

public record SourceMetadata(String documentId, String path, String title, String project, String category,
                             String type, String version, String chunkId, String section, Integer row) {
    public static SourceMetadata from(SearchHit hit) {
        return new SourceMetadata(hit.documentId(), hit.path(), hit.title(), hit.project(), hit.category(),
                hit.type(), hit.version(), hit.chunkId(), hit.section(), hit.row());
    }
}
