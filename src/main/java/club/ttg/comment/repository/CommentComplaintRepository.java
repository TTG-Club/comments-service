package club.ttg.comment.repository;

import club.ttg.comment.model.CommentComplaint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CommentComplaintRepository extends JpaRepository<CommentComplaint, UUID>
{
    boolean existsByCommentIdAndAuthorId(UUID commentId, UUID authorId);
}
