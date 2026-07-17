package club.ttg.comment.controller;

import club.ttg.comment.config.SecurityConfig;
import club.ttg.comment.dto.response.CommentResponse;
import club.ttg.comment.service.CommentService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет правила доступа на реальной цепочке фильтров: токены подписываются так же,
 * как их выпускает auth-service, и проходят через настоящий JwtDecoder.
 */
@WebMvcTest(CommentController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "auth-service.jwt-secret=" + CommentControllerSecurityTest.SECRET)
class CommentControllerSecurityTest
{
    static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommentService commentService;

    @Test
    void guestReadsRootComments() throws Exception
    {
        given(commentService.getRootComments(anyString(), anyString(), any(Pageable.class)))
                .willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/comments")
                        .param("section", "spells")
                        .param("url", "/spells/fireball"))
                .andExpect(status().isOk());
    }

    @Test
    void guestReadsCommentCount() throws Exception
    {
        given(commentService.getCommentCount(anyString(), anyString())).willReturn(3L);

        mockMvc.perform(get("/api/v1/comments/count")
                        .param("section", "spells")
                        .param("url", "/spells/fireball"))
                .andExpect(status().isOk());
    }

    @Test
    void guestReadsReplies() throws Exception
    {
        given(commentService.getReplies(any(UUID.class))).willReturn(List.of());

        mockMvc.perform(get("/api/v1/comments/" + UUID.randomUUID() + "/replies"))
                .andExpect(status().isOk());
    }

    @Test
    void guestReadsLatestComment() throws Exception
    {
        given(commentService.getLatestComment(anyString(), anyString()))
                .willReturn(Optional.of(new CommentResponse()));

        mockMvc.perform(get("/api/v1/comments/latest")
                        .param("section", "spells")
                        .param("url", "/spells/fireball"))
                .andExpect(status().isOk());
    }

    @Test
    void latestReturnsNoContentWhenPageEmpty() throws Exception
    {
        given(commentService.getLatestComment(anyString(), anyString())).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/comments/latest")
                        .param("section", "spells")
                        .param("url", "/spells/empty"))
                .andExpect(status().isNoContent());
    }

    @Test
    void guestReadsCommentById() throws Exception
    {
        given(commentService.getComment(any(UUID.class))).willReturn(new CommentResponse());

        mockMvc.perform(get("/api/v1/comments/" + UUID.randomUUID()))
                .andExpect(status().isOk());
    }

    @Test
    void byIdPatternDoesNotExposeModeration() throws Exception
    {
        // /moderation — не UUID, поэтому by-id regex его не матчит, и он остаётся защищённым.
        mockMvc.perform(get("/api/v1/comments/moderation"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void guestReadsCommentByIdWithQueryString() throws Exception
    {
        // Path-pattern матчит только путь: query-параметр (например, cache-buster) не должен ломать доступ.
        given(commentService.getComment(any(UUID.class))).willReturn(new CommentResponse());

        mockMvc.perform(get("/api/v1/comments/" + UUID.randomUUID() + "?v=123"))
                .andExpect(status().isOk());
    }

    @Test
    void guestCannotCreateComment() throws Exception
    {
        mockMvc.perform(post("/api/v1/comments")
                        .contentType("application/json")
                        .content("{\"section\":\"spells\",\"url\":\"/spells/fireball\",\"content\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void guestCannotReadModeration() throws Exception
    {
        mockMvc.perform(get("/api/v1/comments/moderation"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void plainUserCannotReadModeration() throws Exception
    {
        mockMvc.perform(get("/api/v1/comments/moderation")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void moderatorReadsModeration() throws Exception
    {
        given(commentService.getAllComments(any(Pageable.class))).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/comments/moderation")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("MODERATOR"))))
                .andExpect(status().isOk());
    }

    @Test
    void moderatorReadsDislikedComments() throws Exception
    {
        given(commentService.getDislikedComments(any(Pageable.class))).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/comments/moderation/disliked")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("MODERATOR"))))
                .andExpect(status().isOk());
    }

    @Test
    void garbageTokenIsRejected() throws Exception
    {
        mockMvc.perform(get("/api/v1/comments/moderation")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    private static String issueToken(final List<String> roles)
    {
        final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("username", "peterko")
                .claim("roles", roles)
                .issuedAt(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }
}
