package club.ttg.comment.controller;

import club.ttg.comment.dto.request.CreateCommentRequest;
import club.ttg.comment.dto.request.UpdateCommentRequest;
import club.ttg.comment.dto.response.CommentResponse;
import club.ttg.comment.model.SourcePlatform;
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
    /**
     * Общее описание параметра для Swagger: платформа-источник необязательна, чтобы фронт,
     * ещё не знающий про поле, продолжал читать обсуждения сайта 2024 во время выката.
     */
    private static final String SOURCE_PLATFORM_PARAM_DESCRIPTION =
            "Платформа-источник обсуждения. Необязательна: без неё берётся SITE_5E24.";

    private final CommentService commentService;

    @GetMapping
    @Operation(
            summary = "Список корневых комментариев",
            description = "Возвращает корневые комментарии страницы (тройка sourcePlatform + section + url) постранично: "
                    + "опубликованные, а также надгробия — удалённые комментарии, под которыми остались "
                    + "опубликованные ответы. Надгробие приходит со status = DELETED и без текста и автора "
                    + "(content/authorId/authorName = null): само сообщение удалено, но ветка ответов "
                    + "под ним сохраняется."
    )
    @ApiResponse(responseCode = "200", description = "Страница комментариев")
    public Page<CommentResponse> getRootComments(
            @Parameter(description = SOURCE_PLATFORM_PARAM_DESCRIPTION, example = "SITE_5E24")
            @RequestParam(required = false) final SourcePlatform sourcePlatform,
            @Parameter(description = "Раздел страницы", example = "blog", required = true)
            @RequestParam final String section,
            @Parameter(description = "URL страницы", example = "/posts/hello-world", required = true)
            @RequestParam final String url,
            @Parameter(description = "Параметры пагинации (page, size, sort)")
            @PageableDefault(size = 20) final Pageable pageable
    )
    {
        return commentService.getRootComments(sourcePlatform, section, url, pageable);
    }

    @GetMapping("/moderation")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Все комментарии (модерация)",
            description = "Возвращает все комментарии независимо от статуса, постранично, "
                    + "отсортированные от самого нового к старому. Доступно модератору и администратору. "
                    + "Необязательный authorId сужает выдачу до комментариев одного автора — для карточки "
                    + "пользователя в админке. Статусы не фильтруются: удалённые (DELETED) и скрытые "
                    + "баном (HIDDEN_BY_BAN) тоже возвращаются, вместе с section и url страницы, "
                    + "где комментарий оставлен."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Страница комментариев"),
            @ApiResponse(responseCode = "400", description = "authorId не является UUID", content = @Content),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация", content = @Content),
            @ApiResponse(responseCode = "403", description = "Недостаточно прав", content = @Content)
    })
    public Page<CommentResponse> getAllComments(
            @Parameter(description = "ID автора — тот же UUID, что и клейм sub в JWT. "
                    + "Без параметра возвращается вся лента модерации.",
                    example = "6b1f8f6e-6f2a-4a5e-9c4d-2f1b3c4d5e6f")
            @RequestParam(required = false) final UUID authorId,
            @Parameter(description = "Параметры пагинации (page, size)")
            @PageableDefault(size = 20) final Pageable pageable
    )
    {
        return commentService.getAllComments(authorId, pageable);
    }

    @GetMapping("/moderation/disliked")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Комментарии с жалобами (модерация)",
            description = "Возвращает комментарии, на которые есть хотя бы одна жалоба (дизлайк), "
                    + "постранично, отсортированные по числу жалоб (от большего к меньшему). "
                    + "Доступно модератору и администратору."
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
            description = "Возвращает ответы на указанный родительский комментарий: опубликованные "
                    + "и надгробия (удалённые ответы, под которыми остались опубликованные ветки; "
                    + "status = DELETED, без текста и автора)."
    )
    @ApiResponse(responseCode = "200", description = "Список ответов")
    public List<CommentResponse> getReplies(
            @Parameter(description = "ID родительского комментария", required = true)
            @PathVariable final UUID parentId
    )
    {
        return commentService.getReplies(parentId);
    }

    @GetMapping("/{commentId}")
    @Operation(
            summary = "Комментарий по id",
            description = "Возвращает один комментарий по его id (для перехода по прямой ссылке). "
                    + "Опубликованный отдаётся целиком; удалённый с живой веткой ответов — надгробием "
                    + "(status = DELETED, без текста и автора), чтобы подъём по parentId к корню не "
                    + "обрывался. Удалённый без живых ответов или несуществующий — 404."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Комментарий"),
            @ApiResponse(responseCode = "404", description = "Комментарий не найден", content = @Content)
    })
    public CommentResponse getComment(
            @Parameter(description = "ID комментария", required = true)
            @PathVariable final UUID commentId
    )
    {
        return commentService.getComment(commentId);
    }

    @GetMapping("/count")
    @Operation(
            summary = "Число комментариев страницы",
            description = "Возвращает количество опубликованных комментариев для тройки sourcePlatform + section + url."
    )
    @ApiResponse(responseCode = "200", description = "Количество комментариев")
    public ResponseEntity<Long> getCommentCount(
            @Parameter(description = SOURCE_PLATFORM_PARAM_DESCRIPTION, example = "SITE_5E24")
            @RequestParam(required = false) final SourcePlatform sourcePlatform,
            @Parameter(description = "Раздел страницы", example = "blog", required = true)
            @RequestParam final String section,
            @Parameter(description = "URL страницы", example = "/posts/hello-world", required = true)
            @RequestParam final String url
    )
    {
        return ResponseEntity.ok(commentService.getCommentCount(sourcePlatform, section, url));
    }

    @GetMapping("/my/count")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Число комментариев текущего пользователя",
            description = "Возвращает количество опубликованных комментариев пользователя из токена "
                    + "(клейм sub) — для статистики в профиле. Учитываются и корневые комментарии, "
                    + "и ответы. Удалённые, отклонённые модерацией и помеченные как спам в счётчик "
                    + "не входят: в профиле показывается «живой» вклад пользователя."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Количество комментариев"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация", content = @Content)
    })
    public ResponseEntity<Long> getMyCommentCount(
            @Parameter(hidden = true) @AuthenticationPrincipal final Jwt jwt
    )
    {
        return ResponseEntity.ok(commentService.getUserCommentCount(extractUserId(jwt)));
    }

    @GetMapping("/latest")
    @Operation(
            summary = "Последний комментарий страницы",
            description = "Возвращает самый свежий опубликованный комментарий страницы с учётом ответов "
                    + "(для свёрнутого блока). Если комментарий — ответ, в поле parentAuthorName будет имя "
                    + "автора родителя (кому отвечали). Если комментариев нет — 204 No Content."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Последний комментарий"),
            @ApiResponse(responseCode = "204", description = "На странице ещё нет комментариев", content = @Content)
    })
    public ResponseEntity<CommentResponse> getLatestComment(
            @Parameter(description = SOURCE_PLATFORM_PARAM_DESCRIPTION, example = "SITE_5E24")
            @RequestParam(required = false) final SourcePlatform sourcePlatform,
            @Parameter(description = "Раздел страницы", example = "blog", required = true)
            @RequestParam final String section,
            @Parameter(description = "URL страницы", example = "/posts/hello-world", required = true)
            @RequestParam final String url
    )
    {
        return commentService.getLatestComment(sourcePlatform, section, url)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Создать комментарий",
            description = "Создаёт корневой комментарий от имени текущего пользователя (по JWT). "
                    + "Действует антиспам-лимит: не чаще раза в 20 секунд, после нарушения — раза в минуту, "
                    + "после следующего — временная блокировка на 3 часа. Модератор и администратор от лимита "
                    + "освобождены."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Комментарий создан"),
            @ApiResponse(responseCode = "400", description = "Некорректные данные запроса", content = @Content),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация", content = @Content),
            @ApiResponse(responseCode = "429",
                    description = "Слишком часто. В заголовке Retry-After и в поле retryAfterSeconds — "
                            + "через сколько секунд можно повторить; blocked=true означает блокировку за спам",
                    content = @Content)
    })
    public ResponseEntity<CommentResponse> createComment(
            @Valid @RequestBody final CreateCommentRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal final Jwt jwt
    )
    {
        final CommentResponse response = commentService.createComment(
                request,
                extractUserId(jwt),
                extractUserName(jwt),
                canModerate(jwt)
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{parentId}/replies")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Создать ответ",
            description = "Создаёт ответ на существующий опубликованный комментарий. "
                    + "Антиспам-лимит общий с корневыми комментариями."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Ответ создан"),
            @ApiResponse(responseCode = "400", description = "Некорректные данные запроса", content = @Content),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация", content = @Content),
            @ApiResponse(responseCode = "404", description = "Родительский комментарий не найден", content = @Content),
            @ApiResponse(responseCode = "429",
                    description = "Слишком часто. В заголовке Retry-After и в поле retryAfterSeconds — "
                            + "через сколько секунд можно повторить; blocked=true означает блокировку за спам",
                    content = @Content)
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
                extractUserName(jwt),
                canModerate(jwt)
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{commentId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Редактировать комментарий",
            description = "Изменяет текст комментария. Доступно автору, модератору и администратору. "
                    + "authorId/authorName при этом не меняются."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Комментарий обновлён"),
            @ApiResponse(responseCode = "400", description = "Некорректные данные запроса", content = @Content),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация", content = @Content),
            @ApiResponse(responseCode = "403",
                    description = "Можно менять только свой комментарий (кроме модератора и администратора)",
                    content = @Content),
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
                request,
                canModerate(jwt)
        );
    }

    @DeleteMapping("/{commentId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Удалить комментарий",
            description = "Мягкое удаление комментария (статус DELETED). "
                    + "Доступно автору, модератору и администратору. Ответы не удаляются: если они есть, "
                    + "комментарий остаётся в выдаче надгробием (без текста и автора), и ветка под ним "
                    + "продолжает жить. Надгробие исчезнет из выдачи само, когда будет удалён последний "
                    + "ответ под ним. Отвечать на надгробие нельзя."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Комментарий удалён"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация", content = @Content),
            @ApiResponse(responseCode = "403",
                    description = "Можно удалять только свой комментарий (кроме модератора и администратора)",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "Комментарий не найден", content = @Content)
    })
    public ResponseEntity<Void> deleteComment(
            @Parameter(description = "ID комментария", required = true)
            @PathVariable final UUID commentId,
            @Parameter(hidden = true) @AuthenticationPrincipal final Jwt jwt
    )
    {
        commentService.deleteComment(commentId, extractUserId(jwt), canModerate(jwt));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{commentId}/restore")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Восстановить удалённый комментарий",
            description = "Возвращает удалённый комментарий (DELETED) в опубликованные. "
                    + "Доступно модератору и администратору. Восстанавливается один узел — ответы "
                    + "под ним и так остались опубликованными и снова становятся видны вместе с ним. "
                    + "Комментарий в любом другом статусе (в том числе скрытый баном автора) — 409: "
                    + "скрытие баном снимается разблокировкой автора в auth-service."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Комментарий восстановлен"),
            @ApiResponse(responseCode = "401", description = "Требуется аутентификация", content = @Content),
            @ApiResponse(responseCode = "403", description = "Недостаточно прав", content = @Content),
            @ApiResponse(responseCode = "404", description = "Комментарий не найден", content = @Content),
            @ApiResponse(responseCode = "409", description = "Комментарий не в статусе DELETED", content = @Content)
    })
    public CommentResponse restoreComment(
            @Parameter(description = "ID комментария", required = true)
            @PathVariable final UUID commentId,
            @Parameter(hidden = true) @AuthenticationPrincipal final Jwt jwt
    )
    {
        return commentService.restoreComment(commentId, canModerate(jwt));
    }

    private UUID extractUserId(final Jwt jwt)
    {
        return UUID.fromString(jwt.getSubject());
    }

    /**
     * true, если у пользователя есть роль модератора или администратора — им разрешено
     * править и удалять любой комментарий. Роли берутся из claim "roles" JWT, как и в
     * SecurityConfig при построении authorities.
     */
    private boolean canModerate(final Jwt jwt)
    {
        final List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && (roles.contains("MODERATOR") || roles.contains("ADMIN"));
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
