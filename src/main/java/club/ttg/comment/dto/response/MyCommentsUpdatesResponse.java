package club.ttg.comment.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@AllArgsConstructor
@Schema(description = "Сводка новых ответов на комментарии пользователя — по ней профиль рисует "
        + "индикатор «вам ответили», не загружая список.")
public class MyCommentsUpdatesResponse
{
    @Schema(description = "Число чужих ответов, появившихся позже отметки since "
            + "(без отметки — все ответы пользователю)", example = "3")
    private long count;

    @Schema(description = "Дата самого свежего чужого ответа за всё время (null — ответов не было). "
            + "Её клиент присылает обратно параметром since, когда пометит ответы просмотренными")
    private OffsetDateTime lastReplyAt;
}
