package club.ttg.comment.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Фильтр списка своих комментариев в профиле")
public enum MyCommentsFilter
{
    @Schema(description = "Все комментарии пользователя")
    ALL,

    @Schema(description = "Только те, на которые кто-то ответил")
    WITH_REPLIES,

    @Schema(description = "Только те, где есть ответы новее отметки since")
    NEW_REPLIES
}
