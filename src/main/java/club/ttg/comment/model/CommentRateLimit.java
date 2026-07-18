package club.ttg.comment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Состояние антиспам-лимита одного автора. Строка создаётся при первой попытке написать
 * комментарий и живёт дальше, накапливая серию нарушений.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "comment_rate_limits")
public class CommentRateLimit
{
    @Id
    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    /**
     * Время последнего успешно созданного комментария; null — пользователь ещё не писал.
     * Отклонённая попытка это поле не двигает: иначе непрерывный поток запросов сдвигал бы
     * окно вперёд бесконечно и автор не смог бы отправить комментарий никогда.
     */
    @Column(name = "last_comment_at")
    private OffsetDateTime lastCommentAt;

    /** Длина текущей серии нарушений: индекс интервала в {@code comments.rate-limit.intervals}. */
    @Column(name = "violation_level", nullable = false)
    private int violationLevel;

    @Column(name = "last_violation_at")
    private OffsetDateTime lastViolationAt;

    /** Момент окончания бана; null или прошлое — бана нет. */
    @Column(name = "blocked_until")
    private OffsetDateTime blockedUntil;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Сбрасывает серию нарушений и бан — автор снова на базовом интервале. */
    public void resetViolations()
    {
        violationLevel = 0;
        lastViolationAt = null;
        blockedUntil = null;
    }

    @Override
    public boolean equals(final Object o)
    {
        if (this == o)
        {
            return true;
        }

        if (!(o instanceof CommentRateLimit that))
        {
            return false;
        }

        return Objects.equals(authorId, that.authorId);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(authorId);
    }
}
