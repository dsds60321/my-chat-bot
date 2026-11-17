package com.mychatbot.knowledge;

import com.mychatbot.ai.AiException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.assertj.core.api.Assertions.*;

class KnowledgeFoldersTest {
    @TempDir Path temp;
    private Path root;
    private KnowledgeFolders service;
    @BeforeEach void open() throws Exception {
        root = temp.toRealPath().resolve("source");
        Files.createDirectories(root.resolve("user/ghkang"));
        Files.createDirectories(root.resolve("user/ghkang2"));
        service = reopen();
    }
    private KnowledgeFolders reopen() { return new KnowledgeFolders(root.toString(), root.resolveSibling("store").toString(), root.resolveSibling("index").toString()); }
    private static Authentication auth(String user, String... permissions) {
        var authorities = new ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority("ROLE_EMPLOYEE"));
        Arrays.stream(permissions).map(SimpleGrantedAuthority::new).forEach(authorities::add);
        return new UsernamePasswordAuthenticationToken(user, "", authorities);
    }
    private static Authentication admin() { return auth("admin", "PROJECT_user", "ROLE_KNOWLEDGE_ADMIN", "CREATE_PROJECT_ALL"); }
    private static void rejects(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AiException.class, error -> assertThat(error.code()).isEqualTo(code));
    }

    @Test void usersRequestAndAdminApprovesDurablyWhileDirectPublishUsersStillRequest() throws Exception {
        var user = auth("ghkang", "PROJECT_user", "KNOWLEDGE_DIRECT_PUBLISH");
        assertThat(service.list(user).canCreateDirectly()).isFalse();
        var request = service.submit("user/ghkang", "정산 가이드", user);
        assertThat(request.state()).isEqualTo(KnowledgeFolders.State.PENDING_APPROVAL);
        assertThat(Files.exists(root.resolve(request.path()))).isFalse();
        service = reopen();
        assertThat(service.list(admin()).requests()).extracting(KnowledgeFolders.FolderRequest::id).contains(request.id());
        var approved = service.review(request.id(), true, admin());
        assertThat(approved.state()).isEqualTo(KnowledgeFolders.State.APPROVED);
        assertThat(Files.isDirectory(root.resolve(request.path()))).isTrue();
        assertThat(reopen().list(user).requests()).extracting(KnowledgeFolders.FolderRequest::state).contains(KnowledgeFolders.State.APPROVED);
        assertThat(service.list(user).folders()).contains("user/ghkang/정산 가이드");
        rejects(() -> service.review(request.id(), true, admin()), "PROPOSAL_CONFLICT");
    }
    @Test void adminCreatesImmediatelyAndTopLevelRequestsNeedScopedApproval() throws Exception {
        assertThat(service.submit("user", "업무 자료", admin()).state()).isEqualTo(KnowledgeFolders.State.APPROVED);
        assertThat(Files.isDirectory(root.resolve("user/업무 자료"))).isTrue();
        var user = auth("employee", "PROJECT_user");
        var request = service.submit("", "wayup", user);
        assertThat(service.list(user).requests()).contains(request);
        var limited = auth("limited", "PROJECT_user", "ROLE_KNOWLEDGE_ADMIN", "CREATE_PROJECT_user");
        assertThat(service.list(limited).requests()).doesNotContain(request);
        rejects(() -> service.review(request.id(), true, limited), "PROPOSAL_FORBIDDEN");
        service.review(request.id(), true, admin());
        assertThat(Files.isDirectory(root.resolve("wayup"))).isTrue();
        assertThat(service.list(user).folders()).doesNotContain("wayup");
        rejects(() -> service.submit("", "another", limited), "PROPOSAL_FORBIDDEN");
    }
    @Test void exclusionsAndMissingParentRevalidationProtectEveryOperation() throws Exception {
        var user = auth("employee", "PROJECT_user");
        var pending = service.submit("user/ghkang", "private", user);
        var denied = auth("admin", "PROJECT_user", "ROLE_KNOWLEDGE_ADMIN", "CREATE_PROJECT_ALL", "DENY_PATH_user/ghkang");
        assertThat(service.list(denied).folders()).contains("user/ghkang2").noneMatch(path -> path.equals("user/ghkang") || path.startsWith("user/ghkang/"));
        assertThat(service.list(denied).requests()).isEmpty();
        rejects(() -> service.submit("user/ghkang", "secret", denied), "PROPOSAL_FORBIDDEN");
        rejects(() -> service.review(pending.id(), true, denied), "PROPOSAL_FORBIDDEN");
        rejects(() -> service.review(pending.id(), false, denied), "PROPOSAL_FORBIDDEN");
        Files.delete(root.resolve("user/ghkang"));
        rejects(() -> service.review(pending.id(), true, admin()), "PROPOSAL_CONFLICT");
        assertThat(service.review(pending.id(), false, admin()).state()).isEqualTo(KnowledgeFolders.State.REJECTED);
        assertThat(service.list(auth("other", "PROJECT_user")).requests()).isEmpty();
    }
    @Test void unsafeReservedAndDuplicateFoldersAreRejectedAndSymlinksAreNotFollowed() throws Exception {
        for (String name : List.of("", ".", "..", "../outside", "/absolute", "a\\b", "folder.", "folder ", "CON", "aux.txt", "LPT1")) {
            rejects(() -> service.submit("user", name, admin()), "PROPOSAL_INVALID");
        }
        rejects(() -> service.submit("user/missing", "child", admin()), "PROPOSAL_CONFLICT");
        rejects(() -> service.submit("user", "ghkang", admin()), "PROPOSAL_CONFLICT");
        service.submit("user", "pending", auth("first", "PROJECT_user"));
        rejects(() -> service.submit("user", "pending", auth("second", "PROJECT_user")), "PROPOSAL_CONFLICT");
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.createSymbolicLink(root.resolve("user/link"), outside);
        assertThat(service.list(admin()).folders()).doesNotContain("user/link");
        rejects(() -> service.submit("user/link", "child", admin()), "PROPOSAL_FORBIDDEN");
        assertThat(Files.list(outside).toList()).isEmpty();
        String deep = "user/" + "d/".repeat(15) + "d";
        assertThat(KnowledgeFolders.validPath(deep)).isFalse();
    }
    @Test void storeFailureRollsBackOnlyTheNewEmptyFolderAndNeverErasesRequests() throws Exception {
        service.list(admin());
        Files.delete(root.resolveSibling("store").resolve("folder-requests.json"));
        Files.createDirectory(root.resolveSibling("store").resolve("folder-requests.json"));
        rejects(() -> service.submit("user", "rollback", admin()), "PROPOSAL_UNAVAILABLE");
        assertThat(Files.exists(root.resolve("user/rollback"))).isFalse();
        rejects(() -> reopen().list(admin()), "PROPOSAL_UNAVAILABLE");
    }
}
