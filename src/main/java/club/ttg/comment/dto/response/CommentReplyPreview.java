package club.ttg.comment.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Schema(description = "Последний чужой ответ на комментарий пользователя — «кто и что ответил» "
        + "для карточки в профиле. Отдаётся вместе с самим комментарием, чтобы показать ответ "
        + "без отдельного запроса за веткой.")
public class CommentReplyPreview
{
    @Schema(description = "Идентификатор ответа — по нему строится якорная ссылка на странице обсуждения")
    private UUID id;

    @Schema(description = "Имя автора ответа на момент его создания", example = "john")
    private String authorName;

    @Schema(description = "Текст ответа")
    private String content;

    @Schema(description = "Дата ответа")
    private OffsetDateTime createdAt;
}
