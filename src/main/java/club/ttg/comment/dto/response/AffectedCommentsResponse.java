package club.ttg.comment.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@Schema(description = "Результат массовой операции над комментариями автора")
public class AffectedCommentsResponse
{
    @Schema(description = "Сколько комментариев затронула операция. При повторном вызове — 0", example = "3")
    private int affected;
}
