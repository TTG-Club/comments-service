package club.ttg.comment.repository;

import club.ttg.comment.model.Comment;
import club.ttg.comment.model.CommentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID>
{
    Page<Comment> findBySectionAndUrlAndParentIdIsNullAndStatusOrderByCreatedAtDesc(
            String section,
            String url,
            CommentStatus status,
            Pageable pageable
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

    Page<Comment> findByDislikeCountGreaterThan(
            int dislikeCount,
            Pageable pageable
    );
}