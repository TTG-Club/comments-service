package club.ttg.comment.controller;

import club.ttg.comment.dto.request.CreateCommentRequest;
import club.ttg.comment.dto.request.UpdateCommentRequest;
import club.ttg.comment.dto.response.CommentResponse;
import club.ttg.comment.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/comments")
@RequiredArgsConstructor
@Tag(name = "Comments", description = "Управление комментариями к страницам")
public class CommentController
{
    private final CommentService commentService;

    @GetMapping
    @Operation(
            summary = "Список корневых комментариев",
            description = "Возвращает опубликованные корневые комментарии страницы (пара section + url) постранично."
    )
    @ApiResponse(responseCode = "200", description = "Страница комментариев")
    public Page<CommentResponse> getRootComments(
            @Parameter(description = "Раздел страницы", example = "blog", required = true)
            @RequestParam final String section,
            @Parameter(description = "URL страницы", example = "/posts/hello-world", required = true)
            @RequestParam final String url,
            @Parameter(description = "Параметры пагинации (page, size, sort)")
            @PageableDefault(size = 20) final Pageable pageable
    )
    {
        return commentService.getRootComments(section, url, pageable);
    }

    @GetMapping("/moderation")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Все комментарии (модерация)",
            description = "Возвращает все комментарии независимо от статуса, постранично, "
                    + "отсортированные от самого нового к старому. Доступно только модератору."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Страница комментариев"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация", content = @Content),
            @ApiResponse(responseCode = "403", description = "Недостаточно прав", content = @Content)
    })
    public Page<CommentResponse> getAllComments(
            @Parameter(description = "Параметры пагинации (page, size)")
            @PageableDefault(size = 20) final Pageable pageable
    )
    {
        return commentService.getAllComments(pageable);
    }

    @GetMapping("/moderation/disliked")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Комментарии с жалобами (модерация)",
            description = "Возвращает комментарии, на которые есть хотя бы одна жалоба (дизлайк), "
                    + "постранично, отсортированные по числу жалоб (от большего к меньшему). "
                    + "Доступно только модератору."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Страница комментариев с жалобами"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация", content = @Content),
            @ApiResponse(responseCode = "403", description = "Недостаточно прав", content = @Content)
    })
    public Page<CommentResponse> getDislikedComments(
            @Parameter(description = "Параметры пагинации (page, size)")
            @PageableDefault(size = 20) final Pageable pageable
    )
    {
        return commentService.getDislikedComments(pageable);
    }

    @PostMapping("/{commentId}/dislike")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Пожаловаться на комментарий",
            description = "Отправляет жалобу (дизлайк) на комментарий. "
                    + "Каждый пользователь может пожаловаться на комментарий только один раз."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Жалоба учтена"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация", content = @Content),
            @ApiResponse(responseCode = "404", description = "Комментарий не найден", content = @Content),
            @ApiResponse(responseCode = "409", description = "Жалоба уже отправлена или комментарий удалён",
                    content = @Content)
    })
    public CommentResponse dislikeComment(
            @Parameter(description = "ID комментария", required = true)
            @PathVariable final UUID commentId,
            @Parameter(hidden = true) @AuthenticationPrincipal final Jwt jwt
    )
    {
        return commentService.dislikeComment(commentId, extractUserId(jwt));
    }

    @GetMapping("/{parentId}/replies")
    @Operation(
            summary = "Ответы на комментарий",
            description = "Возвращает опубликованные ответы на указанный родительский комментарий."
    )
    @ApiResponse(responseCode = "200", description = "Список ответов")
    public List<CommentResponse> getReplies(
            @Parameter(description = "ID родительского комментария", required = true)
            @PathVariable final UUID parentId
    )
    {
        return commentService.getReplies(parentId);
    }

    @GetMapping("/count")
    @Operation(
            summary = "Число комментариев страницы",
            description = "Возвращает количество опубликованных комментариев для пары section + url."
    )
    @ApiResponse(responseCode = "200", description = "Количество комментариев")
    public ResponseEntity<Long> getCommentCount(
            @Parameter(description = "Раздел страницы", example = "blog", required = true)
            @RequestParam final String section,
            @Parameter(description = "URL страницы", example = "/posts/hello-world", required = true)
            @RequestParam final String url
    )
    {
        return ResponseEntity.ok(commentService.getCommentCount(section, url));
    }

    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Создать комментарий",
            description = "Создаёт корневой комментарий от имени текущего пользователя (по JWT)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Комментарий создан"),
            @ApiResponse(responseCode = "400", description = "Некорректные данные запроса", content = @Content),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация", content = @Content)
    })
    public ResponseEntity<CommentResponse> createComment(
            @Valid @RequestBody final CreateCommentRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal final Jwt jwt
    )
    {
        final CommentResponse response = commentService.createComment(
                request,
                extractUserId(jwt),
                extractUserName(jwt)
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{parentId}/replies")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Создать ответ",
            description = "Создаёт ответ на существующий опубликованный комментарий."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Ответ создан"),
            @ApiResponse(responseCode = "400", description = "Некорректные данные запроса", content = @Content),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация", content = @Content),
            @ApiResponse(responseCode = "404", description = "Родительский комментарий не найден", content = @Content)
    })
    public ResponseEntity<CommentResponse> createReply(
            @Parameter(description = "ID родительского комментария", required = true)
            @PathVariable final UUID parentId,
            @Valid @RequestBody final CreateCommentRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal final Jwt jwt
    )
    {
        final CommentResponse response = commentService.createReply(
                parentId,
                request,
                extractUserId(jwt),
                extractUserName(jwt)
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{commentId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Редактировать комментарий",
            description = "Изменяет текст комментария. Доступно только автору комментария."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Комментарий обновлён"),
            @ApiResponse(responseCode = "400", description = "Некорректные данные запроса", content = @Content),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация", content = @Content),
            @ApiResponse(responseCode = "403", description = "Можно менять только свой комментарий", content = @Content),
            @ApiResponse(responseCode = "404", description = "Комментарий не найден", content = @Content),
            @ApiResponse(responseCode = "409", description = "Комментарий уже удалён", content = @Content)
    })
    public CommentResponse updateComment(
            @Parameter(description = "ID комментария", required = true)
            @PathVariable final UUID commentId,
            @Valid @RequestBody final UpdateCommentRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal final Jwt jwt
    )
    {
        return commentService.updateComment(
                commentId,
                extractUserId(jwt),
                request
        );
    }

    @DeleteMapping("/{commentId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Удалить комментарий",
            description = "Мягкое удаление комментария (статус DELETED). Доступно только автору."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Комментарий удалён"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация", content = @Content),
            @ApiResponse(responseCode = "403", description = "Можно удалять только свой комментарий", content = @Content),
            @ApiResponse(responseCode = "404", description = "Комментарий не найден", content = @Content)
    })
    public ResponseEntity<Void> deleteComment(
            @Parameter(description = "ID комментария", required = true)
            @PathVariable final UUID commentId,
            @Parameter(hidden = true) @AuthenticationPrincipal final Jwt jwt
    )
    {
        commentService.deleteComment(commentId, extractUserId(jwt));
        return ResponseEntity.noContent().build();
    }

    private UUID extractUserId(final Jwt jwt)
    {
        return UUID.fromString(jwt.getSubject());
    }

    private String extractUserName(final Jwt jwt)
    {
        final String preferredUsername = jwt.getClaimAsString("preferred_username");
        if (preferredUsername != null && !preferredUsername.isBlank())
        {
            return preferredUsername;
        }

        final String username = jwt.getClaimAsString("username");
        if (username != null && !username.isBlank())
        {
            return username;
        }

        return jwt.getSubject();
    }
}
