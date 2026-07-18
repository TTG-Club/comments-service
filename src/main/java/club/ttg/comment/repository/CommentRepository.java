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
}