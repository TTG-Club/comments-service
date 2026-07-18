package club.ttg.comment.service;

import club.ttg.comment.config.CommentRateLimitProperties;
import club.ttg.comment.exception.CommentRateLimitException;
import club.ttg.comment.model.CommentRateLimit;
import club.ttg.comment.repository.CommentRateLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Проверяет эскалацию антиспам-лимита: 20с → после нарушения 60с → после следующего бан на
 * 3 часа, и обнуление серии через час без нарушений. Репозиторий заменён хранилищем в памяти,
 * время двигается вручную через изменяемые часы — иначе тесты пришлось бы ждать в реальном
 * времени.
 */
class CommentRateLimitServiceTest
{
    private static final Instant START = Instant.parse("2026-07-18T12:00:00Z");

    private final UUID authorId = UUID.randomUUID();

    private MutableClock clock;
    private Map<UUID, CommentRateLimit> storage;
    private CommentRateLimitRepository rateLimitRepository;
    private CommentRateLimitService rateLimitService;

    @BeforeEach
    void setUp()
    {
        clock = new MutableClock(START);
        storage = new HashMap<>();
        rateLimitRepository = inMemoryRepository();

        rateLimitService = new CommentRateLimitService(
                rateLimitRepository,
                new CommentRateLimitProperties(),
                clock
        );
    }

    @Test
    void firstCommentPasses()
    {
        rateLimitService.ensureCanPost(authorId, false);

        assertThat(state().getLastCommentAt()).isNotNull();
        assertThat(state().getViolationLevel()).isZero();
    }

    @Test
    void secondCommentWithinTwentySecondsIsRejectedAndRaisesInterval()
    {
        rateLimitService.ensureCanPost(authorId, false);
        clock.advance(Duration.ofSeconds(10));

        final CommentRateLimitException rejection = catchThrowableOfType(
                CommentRateLimitException.class,
                () -> rateLimitService.ensureCanPost(authorId, false)
        );

        assertThat(rejection).isNotNull();
        assertThat(rejection.isBlocked()).isFalse();
        // Интервал ужесточён до минуты и считается от последнего успешного комментария:
        // прошло 10с из 60 — ждать ещё 50.
        assertThat(rejection.getRetryAfterSeconds()).isEqualTo(50);
        assertThat(state().getViolationLevel()).isEqualTo(1);
    }

    @Test
    void rejectedAttemptDoesNotShiftWindow()
    {
        rateLimitService.ensureCanPost(authorId, false);
        final var firstCommentAt = state().getLastCommentAt();

        clock.advance(Duration.ofSeconds(5));
        assertThatThrownBy(() -> rateLimitService.ensureCanPost(authorId, false))
                .isInstanceOf(CommentRateLimitException.class);

        // Отклонённая попытка не двигает точку отсчёта — иначе поток запросов не дал бы
        // автору написать никогда.
        assertThat(state().getLastCommentAt()).isEqualTo(firstCommentAt);
    }

    @Test
    void waitingOutRaisedIntervalLetsCommentThrough()
    {
        rateLimitService.ensureCanPost(authorId, false);

        clock.advance(Duration.ofSeconds(10));
        assertThatThrownBy(() -> rateLimitService.ensureCanPost(authorId, false))
                .isInstanceOf(CommentRateLimitException.class);

        // Выдержана уже минута от первого комментария — на уровне 1 этого достаточно.
        clock.advance(Duration.ofSeconds(51));
        rateLimitService.ensureCanPost(authorId, false);

        assertThat(state().getBlockedUntil()).isNull();
        assertThat(state().getViolationLevel()).isEqualTo(1);
    }

    @Test
    void secondViolationBlocksForThreeHours()
    {
        rateLimitService.ensureCanPost(authorId, false);

        clock.advance(Duration.ofSeconds(10));
        assertThatThrownBy(() -> rateLimitService.ensureCanPost(authorId, false))
                .isInstanceOf(CommentRateLimitException.class);

        clock.advance(Duration.ofSeconds(10));
        final CommentRateLimitException rejection = catchThrowableOfType(
                CommentRateLimitException.class,
                () -> rateLimitService.ensureCanPost(authorId, false)
        );

        assertThat(rejection).isNotNull();
        assertThat(rejection.isBlocked()).isTrue();
        assertThat(rejection.getRetryAfterSeconds()).isEqualTo(Duration.ofHours(3).toSeconds());
        assertThat(state().getBlockedUntil()).isNotNull();
    }

    @Test
    void blockedAuthorIsRejectedUntilItExpires()
    {
        blockAuthor();

        clock.advance(Duration.ofHours(2));
        final CommentRateLimitException rejection = catchThrowableOfType(
                CommentRateLimitException.class,
                () -> rateLimitService.ensureCanPost(authorId, false)
        );

        assertThat(rejection).isNotNull();
        assertThat(rejection.isBlocked()).isTrue();
        assertThat(rejection.getRetryAfterSeconds()).isEqualTo(Duration.ofHours(1).toSeconds());
    }

    @Test
    void expiredBlockResetsAuthorToBaseInterval()
    {
        blockAuthor();

        clock.advance(Duration.ofHours(3).plusSeconds(1));
        rateLimitService.ensureCanPost(authorId, false);

        assertThat(state().getBlockedUntil()).isNull();
        assertThat(state().getViolationLevel()).isZero();

        // Серия обнулена — снова действует базовый интервал 20с, а не минута.
        clock.advance(Duration.ofSeconds(21));
        rateLimitService.ensureCanPost(authorId, false);
        assertThat(state().getViolationLevel()).isZero();
    }

    @Test
    void violationsDecayAfterAnHourOfGoodBehaviour()
    {
        rateLimitService.ensureCanPost(authorId, false);

        clock.advance(Duration.ofSeconds(10));
        assertThatThrownBy(() -> rateLimitService.ensureCanPost(authorId, false))
                .isInstanceOf(CommentRateLimitException.class);
        assertThat(state().getViolationLevel()).isEqualTo(1);

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        rateLimitService.ensureCanPost(authorId, false);

        assertThat(state().getViolationLevel()).isZero();
    }

    @Test
    void authorStillOnRaisedIntervalBeforeDecay()
    {
        rateLimitService.ensureCanPost(authorId, false);

        clock.advance(Duration.ofSeconds(10));
        assertThatThrownBy(() -> rateLimitService.ensureCanPost(authorId, false))
                .isInstanceOf(CommentRateLimitException.class);

        // Полчаса спустя серия ещё не остыла: пишет успешно, но уровень сохраняется,
        // поэтому следующий комментарий раньше минуты снова нарушение — и это уже бан.
        clock.advance(Duration.ofMinutes(30));
        rateLimitService.ensureCanPost(authorId, false);
        assertThat(state().getViolationLevel()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(30));
        final CommentRateLimitException rejection = catchThrowableOfType(
                CommentRateLimitException.class,
                () -> rateLimitService.ensureCanPost(authorId, false)
        );

        assertThat(rejection).isNotNull();
        assertThat(rejection.isBlocked()).isTrue();
    }

    @Test
    void moderatorIsExemptFromLimit()
    {
        rateLimitService.ensureCanPost(authorId, true);
        rateLimitService.ensureCanPost(authorId, true);

        // Освобождённому автору состояние вообще не заводится.
        verify(rateLimitRepository, never()).insertIfAbsent(any(), any());
        assertThat(storage).isEmpty();
    }

    @Test
    void disabledLimitLetsEverythingThrough()
    {
        final CommentRateLimitProperties disabled = new CommentRateLimitProperties();
        disabled.setEnabled(false);

        final CommentRateLimitService service =
                new CommentRateLimitService(rateLimitRepository, disabled, clock);

        service.ensureCanPost(authorId, false);
        service.ensureCanPost(authorId, false);

        assertThat(storage).isEmpty();
    }

    @Test
    void limitsAreTrackedPerAuthor()
    {
        final UUID otherAuthorId = UUID.randomUUID();

        rateLimitService.ensureCanPost(authorId, false);
        clock.advance(Duration.ofSeconds(1));

        // Чужой лимит не мешает: у второго автора это первый комментарий.
        rateLimitService.ensureCanPost(otherAuthorId, false);

        assertThat(storage.get(authorId).getViolationLevel()).isZero();
        assertThat(storage.get(otherAuthorId).getViolationLevel()).isZero();
    }

    @Test
    void intervalsAreConfigurable()
    {
        final CommentRateLimitProperties strict = new CommentRateLimitProperties();
        strict.setIntervals(List.of(Duration.ofSeconds(5)));
        strict.setBlockDuration(Duration.ofMinutes(10));

        final CommentRateLimitService service =
                new CommentRateLimitService(rateLimitRepository, strict, clock);

        service.ensureCanPost(authorId, false);

        // Уровней интервала всего один, поэтому первое же нарушение сразу даёт бан.
        clock.advance(Duration.ofSeconds(1));
        final CommentRateLimitException rejection = catchThrowableOfType(
                CommentRateLimitException.class,
                () -> service.ensureCanPost(authorId, false)
        );

        assertThat(rejection).isNotNull();
        assertThat(rejection.isBlocked()).isTrue();
        assertThat(rejection.getRetryAfterSeconds()).isEqualTo(Duration.ofMinutes(10).toSeconds());
    }

    /** Доводит автора до бана: успешный комментарий и два нарушения подряд. */
    private void blockAuthor()
    {
        rateLimitService.ensureCanPost(authorId, false);

        clock.advance(Duration.ofSeconds(5));
        assertThatThrownBy(() -> rateLimitService.ensureCanPost(authorId, false))
                .isInstanceOf(CommentRateLimitException.class);

        clock.advance(Duration.ofSeconds(5));
        assertThatThrownBy(() -> rateLimitService.ensureCanPost(authorId, false))
                .isInstanceOf(CommentRateLimitException.class);

        assertThat(state().getBlockedUntil()).isNotNull();
    }

    private CommentRateLimit state()
    {
        return storage.get(authorId);
    }

    /**
     * Мок репозитория поверх обычной Map: insertIfAbsent заводит строку, если её нет,
     * findByAuthorIdForUpdate и save работают с той же Map. Блокировку строки в памяти
     * воспроизводить незачем — тесты однопоточные.
     */
    private CommentRateLimitRepository inMemoryRepository()
    {
        final CommentRateLimitRepository repository = mock(CommentRateLimitRepository.class);

        doAnswer(invocation ->
        {
            final UUID id = invocation.getArgument(0);
            storage.computeIfAbsent(id, key ->
            {
                final CommentRateLimit created = new CommentRateLimit();
                created.setAuthorId(key);
                return created;
            });
            return null;
        }).when(repository).insertIfAbsent(any(), any());

        when(repository.findByAuthorIdForUpdate(any()))
                .thenAnswer(invocation -> Optional.ofNullable(storage.get(invocation.<UUID>getArgument(0))));

        when(repository.save(any(CommentRateLimit.class))).thenAnswer(invocation ->
        {
            final CommentRateLimit saved = invocation.getArgument(0);
            storage.put(saved.getAuthorId(), saved);
            return saved;
        });

        return repository;
    }

    /** Часы с ручным переводом стрелок. */
    private static final class MutableClock extends Clock
    {
        private Instant instant;

        private MutableClock(final Instant instant)
        {
            this.instant = instant;
        }

        private void advance(final Duration duration)
        {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return instant;
        }
    }
}
