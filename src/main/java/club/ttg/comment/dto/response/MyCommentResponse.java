package club.ttg.comment.dto.response;

import club.ttg.comment.model.CommentStatus;
import club.ttg.comment.model.SourcePlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Комментарий пользователя для его профиля. В отличие от публичных выдач "
        + "надгробием не маскируется: автор смотрит своё, поэтому текст удалённого и скрытого "
        + "комментария он видит вместе со статусом. Поля с ответами считают только чужие "
        + "опубликованные ответы первого уровня — свой ответ самому себе новостью не является.")
@Getter
@Setter
public class MyCommentResponse
{
    @Schema(description = "Идентификатор комментария")
    private UUID id;

    @Schema(description = "Платформа-источник обсуждения", example = "SITE_5E24")
    private SourcePlatform sourcePlatform;

    @Schema(description = "Раздел страницы", example = "spells")
    private String section;

    @Schema(description = "URL страницы", example = "/spells/fireball")
    private String url;

    @Schema(description = "ID родительского комментария (null — корневой)")
    private UUID parentId;

    @Schema(description = "Имя автора родительского комментария — кому отвечал пользователь")
    private String parentAuthorName;

    @Schema(description = "Текст комментария")
    private String content;

    @Schema(description = "Статус комментария", example = "PUBLISHED")
    private CommentStatus status;

    @Schema(description = "Число чужих опубликованных ответов на комментарий", example = "2")
    private int replyCount;

    @Schema(description = "Сколько из них появилось позже отметки since. Без отметки новыми "
            + "считаются все ответы: пользователь не видел ещё ни одного", example = "1")
    private int newReplyCount;

    @Schema(description = "Дата последнего чужого ответа (null — ответов нет)")
    private OffsetDateTime lastReplyAt;

    @Schema(description = "Последний чужой ответ (null — ответов нет)")
    private CommentReplyPreview lastReply;

    @Schema(description = "Дата создания")
    private OffsetDateTime createdAt;

    @Schema(description = "Дата последнего редактирования (null, если не редактировался)")
    private OffsetDateTime editedAt;
}
