package club.ttg.comment.repository;

import club.ttg.comment.model.Comment;
import club.ttg.comment.model.CommentStatus;
import club.ttg.comment.model.SourcePlatform;
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

    /**
     * Корневые комментарии страницы для публичной выдачи: опубликованные плюс «надгробия» —
     * скрытые узлы, под которыми остались опубликованные потомки ({@code totalReplyCount > 0}).
     * Надгробие держит ветку ответов видимой; скрытый комментарий без живых потомков в выдачу
     * не попадает. Текст и автора надгробия маскирует сервис — запрос отдаёт строку целиком.
     * <p>
     * Надгробием становятся оба статуса, скрывающих содержимое без обрыва ветки: DELETED
     * (удалил автор или модератор) и HIDDEN_BY_BAN (скрыт при блокировке автора). Второй попал
     * сюда не сразу: пока правило было завязано только на DELETED, блокировка автора уносила
     * из выдачи и чужие ответы под его комментариями.
     */
    @Query("SELECT c FROM Comment c "
            + "WHERE c.sourcePlatform = :sourcePlatform AND c.section = :section "
            + "AND c.url = :url AND c.parentId IS NULL "
            + "AND (c.status = club.ttg.comment.model.CommentStatus.PUBLISHED "
            + "OR (c.status IN (club.ttg.comment.model.CommentStatus.DELETED, "
            + "club.ttg.comment.model.CommentStatus.HIDDEN_BY_BAN) AND c.totalReplyCount > 0)) "
            + "ORDER BY c.createdAt DESC")
    Page<Comment> findVisibleRootComments(
            @Param("sourcePlatform") SourcePlatform sourcePlatform,
            @Param("section") String section,
            @Param("url") String url,
            Pageable pageable
    );

    /**
     * Ответы на комментарий для публичной выдачи — то же правило видимости, что и у
     * {@link #findVisibleRootComments}: PUBLISHED либо надгробие с живыми потомками.
     */
    @Query("SELECT c FROM Comment c WHERE c.parentId = :parentId "
            + "AND (c.status = club.ttg.comment.model.CommentStatus.PUBLISHED "
            + "OR (c.status IN (club.ttg.comment.model.CommentStatus.DELETED, "
            + "club.ttg.comment.model.CommentStatus.HIDDEN_BY_BAN) AND c.totalReplyCount > 0)) "
            + "ORDER BY c.createdAt ASC")
    List<Comment> findVisibleByParentId(@Param("parentId") UUID parentId);

    // Вторичная сортировка по id даёт детерминированного «последнего» при равном created_at.
    Optional<Comment> findFirstBySourcePlatformAndSectionAndUrlAndStatusOrderByCreatedAtDescIdDesc(
            SourcePlatform sourcePlatform,
            String section,
            String url,
            CommentStatus status
    );

    long countBySourcePlatformAndSectionAndUrlAndStatus(
            SourcePlatform sourcePlatform,
            String section,
            String url,
            CommentStatus status
    );

    long countByAuthorIdAndStatus(
            UUID authorId,
            CommentStatus status
    );

    /**
     * Лента модерации: все комментарии независимо от статуса. Оба фильтра опциональны и
     * независимы — {@code null} снимает соответствующее условие:
     * <ul>
     *   <li>{@code authorId} сужает до одного автора (карточка пользователя в админке);</li>
     *   <li>{@code sourcePlatform} сужает до одной платформы (лента общая на все сайты,
     *       модератор отсекает нужный).</li>
     * </ul>
     * Фильтра по статусу нет намеренно: администратору нужно видеть и DELETED, и HIDDEN_BY_BAN.
     * Сортировку задаёт {@code Pageable}.
     */
    @Query("SELECT c FROM Comment c "
            + "WHERE (:authorId IS NULL OR c.authorId = :authorId) "
            + "AND (:sourcePlatform IS NULL OR c.sourcePlatform = :sourcePlatform)")
    Page<Comment> findForModeration(
            @Param("sourcePlatform") SourcePlatform sourcePlatform,
            @Param("authorId") UUID authorId,
            Pageable pageable
    );

    /**
     * Лента жалоб: комментарии хотя бы с одной жалобой. {@code sourcePlatform} опционален —
     * {@code null} возвращает жалобы со всех платформ. Сортировку задаёт {@code Pageable}.
     */
    @Query("SELECT c FROM Comment c "
            + "WHERE c.dislikeCount > 0 "
            + "AND (:sourcePlatform IS NULL OR c.sourcePlatform = :sourcePlatform)")
    Page<Comment> findDislikedForModeration(
            @Param("sourcePlatform") SourcePlatform sourcePlatform,
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
     * Массово обновляет снимок имени автора во всех его комментариях — при смене
     * пользователем отображаемого имени на сайте. Снимок кормит в выдаче и
     * {@code authorName} автора, и {@code parentAuthorName} («в ответ {имя}») в его
     * ответах, поэтому один UPDATE чинит имя везде сразу, включая старые комментарии.
     * <p>
     * Условие {@code <> :displayName} делает вызов идемпотентным и дешёвым при повторе:
     * строки, уже несущие это имя, не переписываются, а {@code affected} считает только
     * реально изменённые. Индекс {@code idx_comments_author_created_at} по {@code author_id}
     * поддерживает выборку.
     *
     * @return число затронутых комментариев; 0, если менять было нечего
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Comment c SET c.authorNameSnapshot = :displayName "
            + "WHERE c.authorId = :authorId "
            + "AND c.authorNameSnapshot <> :displayName")
    int renameAuthor(@Param("authorId") UUID authorId, @Param("displayName") String displayName);

    /**
     * Пересчитывает {@code reply_count} (число прямых опубликованных ответов) у всех
     * комментариев, которые могут оказаться в выдаче: опубликованных и обоих надгробных
     * статусов (DELETED, HIDDEN_BY_BAN). Надгробия включены не случайно: счётчики нужны им
     * для собственной видимости и для «N ответов» в выдаче.
     * <p>
     * Массовое скрытие нельзя свести к дельтам, как это делается при удалении одного
     * комментария: комментарии забаненного автора могут быть вложены друг в друга, и вычитание
     * задвоилось бы. Пересчёт с нуля идемпотентен и одинаково корректен в обе стороны.
     * Строки в остальных статусах не трогаются — в выдаче они не участвуют, а их счётчики
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
                WHERE p.status IN ('PUBLISHED', 'DELETED', 'HIDDEN_BY_BAN')
                GROUP BY p.id
            ) child_counts
            WHERE t.id = child_counts.parent_id
              AND t.reply_count <> child_counts.cnt
            """, nativeQuery = true)
    void recalculateReplyCounts();

    /**
     * Пересчитывает {@code total_reply_count} — число опубликованных потомков, достижимых
     * по цепочке из PUBLISHED- и надгробных узлов, — у всех опубликованных и надгробных
     * комментариев.
     * <p>
     * Надгробные статусы (DELETED, HIDDEN_BY_BAN) проницаемы для рекурсии: такой узел остаётся
     * в выдаче надгробием, и ветка под ним живёт, поэтому её нельзя выкидывать из счётчиков
     * предков. Сами надгробия при этом не считаются (надгробие — не комментарий), но счётчик им
     * ведётся: по {@code total_reply_count > 0} выборки решают, показывать ли надгробие. Любой
     * другой статус (REJECTED, SPAM, PENDING_MODERATION) обрывает путь — ровно так же, как
     * {@code adjustAncestorTotals} останавливается на первом таком предке.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            WITH RECURSIVE subtree AS (
                SELECT c.id AS root_id, c.id AS node_id, c.status AS node_status
                FROM comment.comments c
                WHERE c.status IN ('PUBLISHED', 'DELETED', 'HIDDEN_BY_BAN')
                UNION ALL
                SELECT s.root_id, child.id, child.status
                FROM subtree s
                JOIN comment.comments child ON child.parent_id = s.node_id
                WHERE child.status IN ('PUBLISHED', 'DELETED', 'HIDDEN_BY_BAN')
            )
            UPDATE comment.comments t
            SET total_reply_count = sub.cnt
            FROM (
                SELECT root_id,
                       COUNT(*) FILTER (WHERE node_status = 'PUBLISHED' AND node_id <> root_id) AS cnt
                FROM subtree
                GROUP BY root_id
            ) sub
            WHERE t.id = sub.root_id
              AND t.total_reply_count <> sub.cnt
            """, nativeQuery = true)
    void recalculateTotalReplyCounts();
}