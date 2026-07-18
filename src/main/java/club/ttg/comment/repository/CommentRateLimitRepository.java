package club.ttg.comment.repository;

import club.ttg.comment.model.CommentRateLimit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface CommentRateLimitRepository extends JpaRepository<CommentRateLimit, UUID>
{
    /**
     * Создаёт строку состояния, если её ещё нет. {@code ON CONFLICT DO NOTHING} вместо
     * «проверить и вставить»: два параллельных первых комментария одного автора иначе
     * разошлись бы по гонке в нарушение первичного ключа.
     */
    @Modifying
    @Query(value = "INSERT INTO comment.comment_rate_limits (author_id, violation_level, updated_at) "
            + "VALUES (:authorId, 0, :now) ON CONFLICT (author_id) DO NOTHING", nativeQuery = true)
    void insertIfAbsent(@Param("authorId") UUID authorId, @Param("now") OffsetDateTime now);

    /**
     * Читает состояние под блокировкой строки (SELECT ... FOR UPDATE). Без неё два
     * одновременных запроса одного автора прочитали бы одинаковое {@code lastCommentAt}
     * и оба прошли бы лимит. Блокировка держится до конца короткой транзакции лимитера
     * и не пересекается с таблицей комментариев.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT state FROM CommentRateLimit state WHERE state.authorId = :authorId")
    Optional<CommentRateLimit> findByAuthorIdForUpdate(@Param("authorId") UUID authorId);
}
