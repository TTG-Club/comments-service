package club.ttg.comment.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Schema(description = "Автор, комментарии которого нужно скрыть или вернуть")
public class AuthorCommentsRequest
{
    @NotNull(message = "authorId обязателен")
    @Schema(description = "ID автора — тот же UUID, что и клейм sub в JWT", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID authorId;
}
