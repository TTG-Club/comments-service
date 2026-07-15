package club.ttg.comment.dto.response;

import club.ttg.comment.model.CommentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Schema(description = "Комментарий")
public class CommentResponse
{
    @Schema(description = "Идентификатор комментария")
    private UUID id;

    @Schema(description = "Раздел страницы", example = "blog")
    private String section;

    @Schema(description = "URL страницы", example = "/posts/hello-world")
    private String url;

    @Schema(description = "ID родительского комментария (null — корневой)")
    private UUID parentId;

    @Schema(description = "ID автора")
    private UUID authorId;

    @Schema(description = "Имя автора на момент создания", example = "john")
    private String authorName;

    @Schema(description = "Текст комментария")
    private String content;

    @Schema(description = "Статус комментария", example = "PUBLISHED")
    private CommentStatus status;

    @Schema(description = "Число ответов", example = "0")
    private Integer replyCount;

    @Schema(description = "Число жалоб (дизлайков)", example = "0")
    private Integer dislikeCount;

    @Schema(description = "Дата создания")
    private OffsetDateTime createdAt;

    @Schema(description = "Дата последнего редактирования (null, если не редактировался)")
    private OffsetDateTime editedAt;
}