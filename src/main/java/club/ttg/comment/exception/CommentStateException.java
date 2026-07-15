package club.ttg.comment.exception;

/**
 * Операция несовместима с текущим состоянием комментария
 * (например, комментарий уже удалён или ответ на неопубликованный). Маппится на HTTP 409.
 */
public class CommentStateException extends RuntimeException
{
    public CommentStateException(final String message)
    {
        super(message);
    }
}
