package club.ttg.comment.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "Запрос на редактирование комментария")
public class UpdateCommentRequest
{
    @Schema(description = "Новый текст комментария", example = "Обновлённый текст")
    private String content;
}