package club.ttg.comment.controller;

import club.ttg.comment.config.SecurityConfig;
import club.ttg.comment.exception.GlobalExceptionHandler;
import club.ttg.comment.security.InternalServiceTokenFilter;
import club.ttg.comment.service.CommentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет контракт межсервисных ручек на реальной цепочке фильтров: внутренние пути помечены
 * permitAll в SecurityConfig, поэтому вся защита лежит на InternalServiceTokenFilter, и тест
 * должен подтверждать, что без секрета ручка недоступна.
 */
@WebMvcTest(InternalCommentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, InternalServiceTokenFilter.class})
@TestPropertySource(properties = {
        "auth-service.jwt-secret=" + InternalCommentControllerSecurityTest.JWT_SECRET,
        "internal.service-secret=" + InternalCommentControllerSecurityTest.SERVICE_SECRET
})
class InternalCommentControllerSecurityTest
{
    static final String JWT_SECRET = "0123456789abcdef0123456789abcdef";
    static final String SERVICE_SECRET = "internal-secret-value";

    private static final String HIDE_PATH = "/api/v1/internal/comments/hide-by-author";
    private static final String RESTORE_PATH = "/api/v1/internal/comments/restore-by-author";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommentService commentService;

    @Test
    void hidesCommentsWithValidServiceToken() throws Exception
    {
        final UUID authorId = UUID.randomUUID();
        given(commentService.hideCommentsByAuthor(authorId)).willReturn(3);

        mockMvc.perform(post(HIDE_PATH)
                        .header(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, SERVICE_SECRET)
                        .contentType("application/json")
                        .content(body(authorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(3));

        verify(commentService).hideCommentsByAuthor(authorId);
    }

    @Test
    void restoresCommentsWithValidServiceToken() throws Exception
    {
        final UUID authorId = UUID.randomUUID();
        given(commentService.restoreCommentsByAuthor(authorId)).willReturn(2);

        mockMvc.perform(post(RESTORE_PATH)
                        .header(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, SERVICE_SECRET)
                        .contentType("application/json")
                        .content(body(authorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(2));

        verify(commentService).restoreCommentsByAuthor(authorId);
    }

    /** Повторный вызов auth-service ничего не находит — контракт отдаёт 0, а не ошибку. */
    @Test
    void returnsZeroAffectedOnRepeatedCall() throws Exception
    {
        final UUID authorId = UUID.randomUUID();
        given(commentService.hideCommentsByAuthor(authorId)).willReturn(0);

        mockMvc.perform(post(HIDE_PATH)
                        .header(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, SERVICE_SECRET)
                        .contentType("application/json")
                        .content(body(authorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affected").value(0));
    }

    @Test
    void rejectsRequestWithoutServiceToken() throws Exception
    {
        mockMvc.perform(post(HIDE_PATH)
                        .contentType("application/json")
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());

        verify(commentService, never()).hideCommentsByAuthor(any(UUID.class));
    }

    @Test
    void rejectsRequestWithWrongServiceToken() throws Exception
    {
        mockMvc.perform(post(RESTORE_PATH)
                        .header(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, "wrong-secret")
                        .contentType("application/json")
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());

        verify(commentService, never()).restoreCommentsByAuthor(any(UUID.class));
    }

    @Test
    void rejectsBodyWithoutAuthorId() throws Exception
    {
        mockMvc.perform(post(HIDE_PATH)
                        .header(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, SERVICE_SECRET)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(commentService, never()).hideCommentsByAuthor(any(UUID.class));
    }

    @Test
    void rejectsBodyWithMalformedAuthorId() throws Exception
    {
        mockMvc.perform(post(HIDE_PATH)
                        .header(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, SERVICE_SECRET)
                        .contentType("application/json")
                        .content("{\"authorId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());

        verify(commentService, never()).hideCommentsByAuthor(any(UUID.class));
    }

    private static String body(final UUID authorId)
    {
        return "{\"authorId\":\"" + authorId + "\"}";
    }
}
