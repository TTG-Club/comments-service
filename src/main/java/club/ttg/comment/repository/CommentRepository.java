package club.ttg.comment.repository;

import club.ttg.comment.model.Comment;
import club.ttg.comment.model.CommentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID>
{
    /**
     * Атомарные инкременты счётчиков одним UPDATE в БД, а не read-modify-write в памяти —
     * иначе при конкурентных ответах/дизлайках инкременты терялись бы (lost update).
     * GREATEST(0, ...) удерживает счётчик неотрицательным на случай рассинхронизации данных.
     */
    @Modifying
    @Query(value = "UPDATE comment.comments SET reply_count = GREATEST(0, reply_count + :delta) WHERE id = :id",
            nativeQuery = true)
    void addToReplyCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Query(value = "UPDATE comment.comments SET total_reply_count = GREATEST(0, total_reply_count + :delta) "
            + "WHERE id = :id", nativeQuery = true)
    void addToTotalReplyCount(@Param("id") UUID id, @Param("delta") int delta);

    @Modifying
    @Query(value = "UPDATE comment.comments SET dislike_count = dislike_count + 1 WHERE id = :id",
            nativeQuery = true)
    void incrementDislikeCount(@Param("id") UUID id);
    Page<Comment> findBySectionAndUrlAndParentIdIsNullAndStatusOrderByCreatedAtDesc(
            String section,
            String url,
            CommentStatus status,
            Pageable pageable
    );

    // Вторичная сортировка по id даёт детерминированного «последнего» при равном created_at.
    Optional<Comment> findFirstBySectionAndUrlAndStatusOrderByCreatedAtDescIdDesc(
            String section,
            String url,
            CommentStatus status
    );

    List<Comment> findByParentIdAndStatusOrderByCreatedAtAsc(
            UUID parentId,
            CommentStatus status
    );

    long countBySectionAndUrlAndStatus(
            String section,
            String url,
            CommentStatus status
    );

    long countByAuthorIdAndStatus(
            UUID authorId,
            CommentStatus status
    );

    Page<Comment> findByDislikeCountGreaterThan(
            int dislikeCount,
            Pageable pageable
    );

    /**
     * Все комментарии автора для модераторской выдачи — намеренно без фильтра по статусу,
     * как и {@code findAll} в общей ленте модерации: администратору в карточке пользователя
     * нужно видеть и DELETED, и HIDDEN_BY_BAN, а не только живые.
     */
    Page<Comment> findByAuthorId(
            UUID authorId,
            Pageable pageable
    );

    /**
     * Скрывает все опубликованные комментарии автора при его блокировке. Текст не трогается —
     * при разблокировке он возвращается как есть.
     * <p>
     * Идемпотентно по построению: повторный вызов не найдёт PUBLISHED-строк этого автора.
     * Уже удалённые самим пользователем (DELETED) и отклонённые модерацией (REJECTED, SPAM)
     * не затрагиваются: их состояние не связано с баном.
     *
     * @return число скрытых комментариев
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Comment c SET c.status = club.ttg.comment.model.CommentStatus.HIDDEN_BY_BAN "
            + "WHERE c.authorId = :authorId "
            + "AND c.status = club.ttg.comment.model.CommentStatus.PUBLISHED")
    int hidePublishedByAuthor(@Param("authorId") UUID authorId);

    /**
     * Возвращает в выдачу комментарии, скрытые баном автора. Условие по исходному статусу
     * гарантирует, что удалённые самим пользователем (DELETED) и снятые модератором
     * (REJECTED, SPAM) не воскреснут — восстанавливается ровно то, что скрыл бан.
     *
     * @return число восстановленных комментариев
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Comment c SET c.status = club.ttg.comment.model.CommentStatus.PUBLISHED "
            + "WHERE c.authorId = :authorId "
            + "AND c.status = club.ttg.comment.model.CommentStatus.HIDDEN_BY_BAN")
    int restoreHiddenByBanByAuthor(@Param("authorId") UUID authorId);

    /**
     * Пересчитывает {@code reply_count} (число прямых опубликованных ответов) у всех
     * опубликованных комментариев.
     * <p>
     * Массовое скрытие нельзя свести к дельтам, как это делается при удалении одного
     * комментария: комментарии забаненного автора могут быть вложены друг в друга, и вычитание
     * задвоилось бы. Пересчёт с нуля идемпотентен и одинаково корректен в обе стороны.
     * Неопубликованные строки не трогаются — в выдаче они не участвуют, а их счётчики
     * восстановятся при возврате в PUBLISHED.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE comment.comments t
            SET reply_count = child_counts.cnt
            FROM (
                SELECT p.id AS parent_id, COUNT(child.id) AS cnt
                FROM comment.comments p
                LEFT JOIN comment.comments child
                       ON child.parent_id = p.id AND child.status = 'PUBLISHED'
                WHERE p.status = 'PUBLISHED'
                GROUP BY p.id
            ) child_counts
            WHERE t.id = child_counts.parent_id
              AND t.reply_count <> child_counts.cnt
            """, nativeQuery = true)
    void recalculateReplyCounts();

    /**
     * Пересчитывает {@code total_reply_count} (всё поддерево) у всех опубликованных комментариев.
     * Повторяет логику changeSet {@code backfill-total-reply-count} миграции
     * {@code 003-total-reply-count.yaml}: рекурсия идёт только через PUBLISHED-узлы, поэтому
     * любой другой статус обрывает путь и ветви под скрытым узлом в счёт не входят — ровно так же,
     * как {@code adjustAncestorTotals} останавливается на первом удалённом предке.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            WITH RECURSIVE subtree AS (
                SELECT c.id AS root_id, c.id AS node_id
                FROM comment.comments c
                WHERE c.status = 'PUBLISHED'
                UNION ALL
                SELECT s.root_id, child.id
                FROM subtree s
                JOIN comment.comments child ON child.parent_id = s.node_id
                WHERE child.status = 'PUBLISHED'
            )
            UPDATE comment.comments t
            SET total_reply_count = sub.cnt
            FROM (
                SELECT root_id, COUNT(*) - 1 AS cnt
                FROM subtree
                GROUP BY root_id
            ) sub
            WHERE t.id = sub.root_id
              AND t.total_reply_count <> sub.cnt
            """, nativeQuery = true)
    void recalculateTotalReplyCounts();
}