package club.ttg.comment.dto.request;

import club.ttg.comment.model.SourcePlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Schema(description = "Новое отображаемое имя автора для массового обновления снимков в его комментариях")
public class RenameAuthorRequest
{
    @NotNull(message = "authorId обязателен")
    @Schema(description = "ID автора — тот же UUID, что и клейм sub в JWT", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID authorId;

    @Schema(description = "Платформа-источник: ограничивает переименование комментариями одного "
            + "сайта. Необязательна — без неё правятся комментарии автора на всех платформах.",
            example = "SITE_5E24")
    private SourcePlatform sourcePlatform;

    @NotBlank(message = "displayName обязателен")
    @Size(max = 255, message = "displayName не длиннее 255 символов")
    @Schema(description = "Новое отображаемое имя автора", requiredMode = Schema.RequiredMode.REQUIRED)
    private String displayName;
}
