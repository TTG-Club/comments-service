package club.ttg.comment.controller;

import club.ttg.comment.dto.request.AuthorCommentsRequest;
import club.ttg.comment.dto.request.RenameAuthorRequest;
import club.ttg.comment.dto.response.AffectedCommentsResponse;
import club.ttg.comment.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Межсервисные ручки: их вызывает auth-service при блокировке и разблокировке пользователя.
 * Аутентификация не по JWT (пользовательского токена у вызывающего нет), а по общему секрету
 * в заголовке {@code X-Service-Token} — см.
 * {@link club.ttg.comment.security.InternalServiceTokenFilter}.
 */
@RestController
@RequestMapping("/api/v1/internal/comments")
@RequiredArgsConstructor
@Tag(name = "Internal", description = "Межсервисные операции, защищённые общим секретом X-Service-Token")
public class InternalCommentController
{
    private final CommentService commentService;

    @PostMapping("/hide-by-author")
    @Operation(
            summary = "Скрыть комментарии автора",
            description = "Переводит все опубликованные комментарии автора в статус HIDDEN_BY_BAN — "
                    + "они пропадают из всех публичных выдач. Текст комментариев сохраняется, поэтому "
                    + "разблокировка возвращает их как есть. Комментарии, удалённые самим пользователем "
                    + "или снятые модератором, не затрагиваются. Операция идемпотентна: повторный вызов "
                    + "вернёт affected = 0."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Комментарии скрыты"),
            @ApiResponse(responseCode = "400", description = "Некорректное тело запроса", content = @Content),
            @ApiResponse(responseCode = "401", description = "Неверный или отсутствующий X-Service-Token",
                    content = @Content)
    })
    public AffectedCommentsResponse hideByAuthor(@Valid @RequestBody final AuthorCommentsRequest request)
    {
        return new AffectedCommentsResponse(commentService.hideCommentsByAuthor(request.getAuthorId()));
    }

    @PostMapping("/restore-by-author")
    @Operation(
            summary = "Вернуть комментарии автора",
            description = "Возвращает в выдачу комментарии, скрытые баном автора (HIDDEN_BY_BAN → PUBLISHED). "
                    + "Удалённые самим пользователем остаются удалёнными, снятые модератором — снятыми. "
                    + "Операция идемпотентна: повторный вызов вернёт affected = 0."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Комментарии восстановлены"),
            @ApiResponse(responseCode = "400", description = "Некорректное тело запроса", content = @Content),
            @ApiResponse(responseCode = "401", description = "Неверный или отсутствующий X-Service-Token",
                    content = @Content)
    })
    public AffectedCommentsResponse restoreByAuthor(@Valid @RequestBody final AuthorCommentsRequest request)
    {
        return new AffectedCommentsResponse(commentService.restoreCommentsByAuthor(request.getAuthorId()));
    }

    @PostMapping("/rename-by-author")
    @Operation(
            summary = "Обновить имя автора в его комментариях",
            description = "Массово переписывает снимок имени в комментариях автора на переданное "
                    + "отображаемое имя. Чинит имя и в старых комментариях, и в подписи «в ответ {имя}». "
                    + "sourcePlatform ограничивает переименование одним сайтом (без него — все "
                    + "платформы автора): authorId общий на всех сайтах, а имя у каждого сайта своё. "
                    + "Вызывается сайтом при смене пользователем имени и после публикации комментария. "
                    + "Идемпотентно: повторный вызов с тем же именем вернёт affected = 0."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Имя обновлено"),
            @ApiResponse(responseCode = "400", description = "Некорректное тело запроса", content = @Content),
            @ApiResponse(responseCode = "401", description = "Неверный или отсутствующий X-Service-Token",
                    content = @Content)
    })
    public AffectedCommentsResponse renameByAuthor(@Valid @RequestBody final RenameAuthorRequest request)
    {
        return new AffectedCommentsResponse(commentService.renameAuthor(
                request.getAuthorId(),
                request.getSourcePlatform(),
                request.getDisplayName()));
    }
}
