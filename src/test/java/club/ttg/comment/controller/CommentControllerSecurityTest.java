package club.ttg.comment.controller;

import club.ttg.comment.config.SecurityConfig;
import club.ttg.comment.dto.response.CommentResponse;
import club.ttg.comment.model.SourcePlatform;
import club.ttg.comment.exception.CommentAccessDeniedException;
import club.ttg.comment.exception.CommentStateException;
import club.ttg.comment.exception.GlobalExceptionHandler;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет правила доступа на реальной цепочке фильтров: токены подписываются так же,
 * как их выпускает auth-service, и проходят через настоящий JwtDecoder.
 */
@WebMvcTest(CommentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
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
        // Платформа nullable: запрос её не передаёт, как ещё не обновлённый фронт.
        given(commentService.getRootComments(
                nullable(SourcePlatform.class),
                anyString(),
                anyString(),
                any(Pageable.class)
        )).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/comments")
                        .param("section", "spells")
                        .param("url", "/spells/fireball"))
                .andExpect(status().isOk());
    }

    @Test
    void guestReadsCommentCount() throws Exception
    {
        given(commentService.getCommentCount(nullable(SourcePlatform.class), anyString(), anyString()))
                .willReturn(3L);

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
        given(commentService.getLatestComment(nullable(SourcePlatform.class), anyString(), anyString()))
                .willReturn(Optional.of(new CommentResponse()));

        mockMvc.perform(get("/api/v1/comments/latest")
                        .param("section", "spells")
                        .param("url", "/spells/fireball"))
                .andExpect(status().isOk());
    }

    @Test
    void latestReturnsNoContentWhenPageEmpty() throws Exception
    {
        given(commentService.getLatestComment(nullable(SourcePlatform.class), anyString(), anyString()))
                .willReturn(Optional.empty());

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
    void guestCannotReadOwnCommentCount() throws Exception
    {
        // Статистика профиля приватна: /my/count не подпадает ни под один публичный паттерн
        // и остаётся под anyRequest().authenticated().
        mockMvc.perform(get("/api/v1/comments/my/count"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userReadsOwnCommentCount() throws Exception
    {
        final UUID userId = UUID.randomUUID();
        given(commentService.getUserCommentCount(userId)).willReturn(2L);

        mockMvc.perform(get("/api/v1/comments/my/count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("USER"), userId)))
                .andExpect(status().isOk())
                .andExpect(content().string("2"));

        // Считаем по sub из токена, а не по чему-либо из запроса.
        verify(commentService).getUserCommentCount(userId);
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
        given(commentService.getAllComments(any(), any(), any(Pageable.class))).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/comments/moderation")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("MODERATOR"))))
                .andExpect(status().isOk());

        // Без параметров оба фильтра — null: общая лента по всем платформам и авторам.
        verify(commentService).getAllComments(isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void adminFiltersModerationByAuthor() throws Exception
    {
        final UUID authorId = UUID.randomUUID();
        given(commentService.getAllComments(any(), any(), any(Pageable.class))).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/comments/moderation")
                        .param("authorId", authorId.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("ADMIN"))))
                .andExpect(status().isOk());

        verify(commentService).getAllComments(isNull(), eq(authorId), any(Pageable.class));
    }

    @Test
    void adminFiltersModerationByPlatform() throws Exception
    {
        given(commentService.getAllComments(any(), any(), any(Pageable.class))).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/comments/moderation")
                        .param("sourcePlatform", "SITE_5E14")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("ADMIN"))))
                .andExpect(status().isOk());

        verify(commentService).getAllComments(eq(SourcePlatform.SITE_5E14), isNull(), any(Pageable.class));
    }

    @Test
    void unknownPlatformFilterIsRejected() throws Exception
    {
        // Неизвестное значение enum отбивается разбором параметра (400), а не доходит до сервиса.
        mockMvc.perform(get("/api/v1/comments/moderation")
                        .param("sourcePlatform", "SITE_MARS")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("ADMIN"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedAuthorIdIsRejected() throws Exception
    {
        mockMvc.perform(get("/api/v1/comments/moderation")
                        .param("authorId", "не-uuid")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("ADMIN"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void plainUserCannotFilterModerationByAuthor() throws Exception
    {
        // Фильтр не должен становиться лазейкой: правило доступа то же, что и у ленты целиком.
        mockMvc.perform(get("/api/v1/comments/moderation")
                        .param("authorId", UUID.randomUUID().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void moderatorReadsDislikedComments() throws Exception
    {
        given(commentService.getDislikedComments(any(), any(Pageable.class))).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/comments/moderation/disliked")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("MODERATOR"))))
                .andExpect(status().isOk());
    }

    @Test
    void adminReadsModeration() throws Exception
    {
        given(commentService.getAllComments(any(), any(), any(Pageable.class))).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/comments/moderation")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void adminReadsDislikedComments() throws Exception
    {
        // Часть админов сайта имеет только роль ADMIN без MODERATOR, а админка ходит сюда.
        given(commentService.getDislikedComments(any(), any(Pageable.class))).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/comments/moderation/disliked")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void adminDeletesSomeoneElsesComment() throws Exception
    {
        final UUID commentId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/comments/" + commentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("ADMIN"))))
                .andExpect(status().isNoContent());

        // Роль ADMIN должна дойти до сервиса флагом canModerate == true.
        verify(commentService).deleteComment(eq(commentId), any(UUID.class), eq(true));
    }

    @Test
    void moderatorEditsSomeoneElsesComment() throws Exception
    {
        final UUID commentId = UUID.randomUUID();
        given(commentService.updateComment(any(UUID.class), any(UUID.class), any(), anyBoolean()))
                .willReturn(new CommentResponse());

        mockMvc.perform(patch("/api/v1/comments/" + commentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("MODERATOR")))
                        .contentType("application/json")
                        .content("{\"content\":\"отмодерировано\"}"))
                .andExpect(status().isOk());

        verify(commentService).updateComment(eq(commentId), any(UUID.class), any(), eq(true));
    }

    @Test
    void plainUserForbiddenOnSomeoneElsesComment() throws Exception
    {
        final UUID commentId = UUID.randomUUID();
        // Обычный пользователь: canModerate == false, сервис отклоняет чужой комментарий 403.
        doThrow(new CommentAccessDeniedException("You can modify only your own comment"))
                .when(commentService).deleteComment(eq(commentId), any(UUID.class), eq(false));

        mockMvc.perform(delete("/api/v1/comments/" + commentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void guestCannotRestoreComment() throws Exception
    {
        mockMvc.perform(post("/api/v1/comments/" + UUID.randomUUID() + "/restore"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Восстановление лежит не под /moderation, поэтому опирается на собственное правило в
     * SecurityConfig. Без него запрос попал бы под anyRequest().authenticated() и любой
     * вошедший пользователь возвращал бы себе удалённые комментарии.
     */
    @Test
    void plainUserCannotRestoreComment() throws Exception
    {
        mockMvc.perform(post("/api/v1/comments/" + UUID.randomUUID() + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("USER"))))
                .andExpect(status().isForbidden());

        verify(commentService, never()).restoreComment(any(UUID.class), anyBoolean());
    }

    @Test
    void moderatorRestoresComment() throws Exception
    {
        final UUID commentId = UUID.randomUUID();
        given(commentService.restoreComment(any(UUID.class), anyBoolean())).willReturn(new CommentResponse());

        mockMvc.perform(post("/api/v1/comments/" + commentId + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("MODERATOR"))))
                .andExpect(status().isOk());

        verify(commentService).restoreComment(commentId, true);
    }

    @Test
    void adminRestoresComment() throws Exception
    {
        // Часть админов сайта имеет только роль ADMIN без MODERATOR, а восстанавливают из админки.
        final UUID commentId = UUID.randomUUID();
        given(commentService.restoreComment(any(UUID.class), anyBoolean())).willReturn(new CommentResponse());

        mockMvc.perform(post("/api/v1/comments/" + commentId + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("ADMIN"))))
                .andExpect(status().isOk());

        verify(commentService).restoreComment(commentId, true);
    }

    @Test
    void restoringCommentThatIsNotDeletedIsConflict() throws Exception
    {
        final UUID commentId = UUID.randomUUID();
        given(commentService.restoreComment(eq(commentId), anyBoolean()))
                .willThrow(new CommentStateException("Comment is not deleted, current status: PUBLISHED"));

        mockMvc.perform(post("/api/v1/comments/" + commentId + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(List.of("MODERATOR"))))
                .andExpect(status().isConflict());
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
        return issueToken(roles, UUID.randomUUID());
    }

    private static String issueToken(final List<String> roles, final UUID userId)
    {
        final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", "peterko")
                .claim("roles", roles)
                .issuedAt(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }
}
