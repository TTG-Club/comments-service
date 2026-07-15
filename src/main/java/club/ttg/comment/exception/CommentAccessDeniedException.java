package club.ttg.comment.exception;

/**
 * Пользователь пытается изменить чужой комментарий. Маппится на HTTP 403.
 */
public class CommentAccessDeniedException extends RuntimeException
{
    public CommentAccessDeniedException(final String message)
    {
        super(message);
    }
}
