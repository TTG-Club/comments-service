package club.ttg.comment.exception;

import lombok.Getter;

/**
 * Комментарий отклонён антиспам-лимитом. Отдаётся как 429 с заголовком {@code Retry-After}.
 */
@Getter
public class CommentRateLimitException extends RuntimeException
{
    /** Через сколько секунд можно повторить попытку. */
    private final long retryAfterSeconds;

    /** true — автор в бане за серию нарушений, false — просто не выдержан интервал. */
    private final boolean blocked;

    private CommentRateLimitException(
            final String message,
            final long retryAfterSeconds,
            final boolean blocked
    )
    {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
        this.blocked = blocked;
    }

    public static CommentRateLimitException tooFast(final long retryAfterSeconds)
    {
        return new CommentRateLimitException(
                "Слишком часто. Следующий комментарий можно отправить через " + retryAfterSeconds + " сек.",
                retryAfterSeconds,
                false
        );
    }

    public static CommentRateLimitException blocked(final long retryAfterSeconds)
    {
        final long minutes = Math.max(1, (retryAfterSeconds + 59) / 60);

        return new CommentRateLimitException(
                "Отправка комментариев временно заблокирована из-за слишком частых сообщений. "
                        + "Повторите через " + minutes + " мин.",
                retryAfterSeconds,
                true
        );
    }
}
