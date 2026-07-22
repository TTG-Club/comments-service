package club.ttg.comment.dto.request;

import club.ttg.comment.model.SourcePlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "Запрос на создание комментария или ответа")
public class CreateCommentRequest
{
    @Schema(description = "Платформа-источник; вместе с section и url образует ключ обсуждения. "
            + "Необязательна: пустое значение трактуется как SITE_5E24.", example = "SITE_5E24")
    private SourcePlatform sourcePlatform;

    @Schema(description = "Раздел страницы", example = "blog")
    private String section;

    @Schema(description = "URL страницы", example = "/posts/hello-world")
    private String url;

    @Schema(description = "Текст комментария", example = "Отличная статья!")
    private String content;
}