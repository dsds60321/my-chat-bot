package com.mychatbot.knowledge;

import java.util.List;
import java.util.Set;

/** Common search boundary for official documents and future explicitly approved knowledge. */
public interface KnowledgeSearch {
    List<SearchHit> search(String question, Set<String> projects, int limit);

    default List<SearchHit> search(String question, com.mychatbot.security.AccessContext access, int limit) {
        return search(question, access.projects(), limit).stream().filter(hit -> access.allowsPath(hit.path())).toList();
    }
    default List<SearchHit> search(String question, com.mychatbot.security.AccessContext access, int limit,
                                  String documentId, String version) {
        return search(question, access, limit).stream().filter(hit -> documentId == null
                || (documentId.equals(hit.documentId()) && java.util.Objects.equals(version, hit.version()))).toList();
    }
}
