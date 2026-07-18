package club.ttg.comment.service;

import club.ttg.comment.config.CommentRateLimitProperties;
import club.ttg.comment.exception.CommentRateLimitException;
import club.ttg.comment.model.CommentRateLimit;
import club.ttg.comment.repository.CommentRateLimitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Антиспам-лимит на создание комментариев: не чаще раза в 20 секунд, после нарушения —
 * не чаще раза в минуту, после следующего — бан на 3 часа (значения настраиваются в
 * {@link CommentRateLimitProperties}). Через час без нарушений серия обнуляется.
 * <p>
 * Лимит обязан жить на сервере, а не на фронте: обратный отсчёт в интерфейсе — подсказка
 * пользователю, но POST с валидным токеном можно отправить и мимо интерфейса.
 */
@Service
@RequiredArgsConstructor
public class CommentRateLimitService
{
    private final CommentRateLimitRepository rateLimitRepository;
    private final CommentRateLimitProperties properties;
    private final Clock clock;

    /**
     * Пропускает попытку создать комментарий или бросает {@link CommentRateLimitException}.
     * Успешная попытка отмечается как «последний комментарий сейчас», неуспешная удлиняет
     * серию нарушений.
     * <p>
     * Транзакция здесь своя ({@code REQUIRES_NEW}) и {@code noRollbackFor} — оба атрибута
     * несущие, а не косметические. Учёт нарушения должен сохраниться именно в тот момент,
     * когда попытка отклоняется: без {@code noRollbackFor} исключение откатило бы только что
     * записанный инкремент серии, а без отдельной транзакции его откатил бы вызывающий
     * {@code CommentService}, у которого исключение прерывает создание комментария. В обоих
     * случаях серия нарушений никогда не росла бы и эскалация 20с → 60с → бан не работала.
     *
     * @param authorId автор из JWT
     * @param exempt   true для модератора и администратора — они пишут без ограничений
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = CommentRateLimitException.class)
    public void ensureCanPost(
            final UUID authorId,
            final boolean exempt
    )
    {
        if (exempt || !properties.isEnabled() || properties.getIntervals().isEmpty())
        {
            return;
        }

        final OffsetDateTime now = OffsetDateTime.now(clock);
        final CommentRateLimit state = lockState(authorId, now);

        rejectIfBlocked(state, now);
        decayViolations(state, now);

        final OffsetDateTime nextAllowedAt = nextAllowedAt(state);
        if (nextAllowedAt != null && nextAllowedAt.isAfter(now))
        {
            throw registerViolation(state, now);
        }

        state.setLastCommentAt(now);
        rateLimitRepository.save(state);
    }

    private CommentRateLimit lockState(
            final UUID authorId,
            final OffsetDateTime now
    )
    {
        rateLimitRepository.insertIfAbsent(authorId, now);

        return rateLimitRepository.findByAuthorIdForUpdate(authorId)
                .orElseThrow(() -> new IllegalStateException("Rate limit state is missing for author " + authorId));
    }

    /** Активный бан отклоняет попытку; отбытый — снимается, и автор начинает с чистого листа. */
    private void rejectIfBlocked(
            final CommentRateLimit state,
            final OffsetDateTime now
    )
    {
        final OffsetDateTime blockedUntil = state.getBlockedUntil();

        if (blockedUntil == null)
        {
            return;
        }

        if (blockedUntil.isAfter(now))
        {
            throw CommentRateLimitException.blocked(secondsUntil(now, blockedUntil));
        }

        state.resetViolations();
    }

    /**
     * Обнуляет серию нарушений, если последнее было давно. Без этого один случайный двойной
     * клик навсегда оставил бы добросовестного автора на минутном интервале.
     */
    private void decayViolations(
            final CommentRateLimit state,
            final OffsetDateTime now
    )
    {
        if (state.getViolationLevel() == 0)
        {
            return;
        }

        final OffsetDateTime lastViolationAt = state.getLastViolationAt();

        if (lastViolationAt == null || !lastViolationAt.plus(properties.getViolationDecay()).isAfter(now))
        {
            state.resetViolations();
        }
    }

    private OffsetDateTime nextAllowedAt(final CommentRateLimit state)
    {
        if (state.getLastCommentAt() == null)
        {
            return null;
        }

        return state.getLastCommentAt().plus(intervalForLevel(state.getViolationLevel()));
    }

    /**
     * Удлиняет серию нарушений и возвращает исключение для вызывающего. Когда нарушений
     * становится больше, чем настроено уровней интервала, вместо очередного удлинения
     * выдаётся бан.
     */
    private CommentRateLimitException registerViolation(
            final CommentRateLimit state,
            final OffsetDateTime now
    )
    {
        final int level = state.getViolationLevel() + 1;

        state.setViolationLevel(level);
        state.setLastViolationAt(now);

        if (level >= properties.getIntervals().size())
        {
            final OffsetDateTime blockedUntil = now.plus(properties.getBlockDuration());
            state.setBlockedUntil(blockedUntil);
            rateLimitRepository.save(state);

            return CommentRateLimitException.blocked(secondsUntil(now, blockedUntil));
        }

        rateLimitRepository.save(state);

        // Ждать теперь нужно по новому, ужесточённому интервалу, отсчитываемому от последнего
        // успешного комментария.
        final OffsetDateTime retryAt = state.getLastCommentAt().plus(intervalForLevel(level));

        return CommentRateLimitException.tooFast(secondsUntil(now, retryAt));
    }

    private Duration intervalForLevel(final int violationLevel)
    {
        final List<Duration> intervals = properties.getIntervals();
        final int index = Math.min(Math.max(violationLevel, 0), intervals.size() - 1);

        return intervals.get(index);
    }

    /** Секунды до момента с округлением вверх: 0 в Retry-After означал бы «можно прямо сейчас». */
    private static long secondsUntil(
            final OffsetDateTime now,
            final OffsetDateTime target
    )
    {
        final long millis = Duration.between(now, target).toMillis();

        return Math.max(1, (millis + 999) / 1000);
    }
}
