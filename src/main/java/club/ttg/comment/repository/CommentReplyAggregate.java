package club.ttg.comment.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Сводка чужих ответов на один комментарий пользователя. Считается пачкой на страницу списка,
 * чтобы не звать базу по разу на карточку.
 */
public interface CommentReplyAggregate
{
    /** Комментарий пользователя, на который отвечали. */
    UUID getParentId();

    /** Сколько всего чужих опубликованных ответов у этого комментария. */
    long getReplyCount();

    /** Когда ответили в последний раз. */
    OffsetDateTime getLastReplyAt();

    /** Сколько ответов появилось позже отметки просмотра. */
    long getNewReplyCount();
}
