package club.ttg.comment.service;

import club.ttg.comment.dto.request.CreateCommentRequest;
import club.ttg.comment.dto.request.UpdateCommentRequest;
import club.ttg.comment.dto.response.CommentResponse;
import club.ttg.comment.exception.CommentAccessDeniedException;
import club.ttg.comment.exception.CommentStateException;
import club.ttg.comment.mapper.CommentMapper;
import club.ttg.comment.model.Comment;
import club.ttg.comment.model.CommentComplaint;
import club.ttg.comment.model.CommentStatus;
import club.ttg.comment.repository.CommentComplaintRepository;
import club.ttg.comment.repository.CommentRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CommentService
{
    private final CommentRepository commentRepository;
    private final CommentComplaintRepository commentComplaintRepository;
    private final CommentMapper commentMapper;

    @Transactional(readOnly = true)
    public Page<CommentResponse> getRootComments(
            final String section,
            final String url,
            final Pageable pageable
    )
    {
        return commentRepository.findBySectionAndUrlAndParentIdIsNullAndStatusOrderByCreatedAtDesc(
                normalize(section),
                normalize(url),
                CommentStatus.PUBLISHED,
                pageable
        ).map(commentMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<CommentResponse> getAllComments(final Pageable pageable)
    {
        final Pageable sortedByNewest = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        return commentRepository.findAll(sortedByNewest).map(commentMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<CommentResponse> getDislikedComments(final Pageable pageable)
    {
        final Pageable sortedByDislikes = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "dislikeCount")
        );

        return commentRepository.findByDislikeCountGreaterThan(0, sortedByDislikes)
                .map(commentMapper::toResponse);
    }

    @Transactional
    public CommentResponse dislikeComment(
            final UUID commentId,
            final UUID authorId
    )
    {
        final Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found: " + commentId));

        if (comment.isDeleted())
        {
            throw new CommentStateException("Cannot dislike deleted comment");
        }

        if (commentComplaintRepository.existsByCommentIdAndAuthorId(commentId, authorId))
        {
            throw new CommentStateException("You have already disliked this comment");
        }

        final CommentComplaint complaint = new CommentComplaint();
        complaint.setCommentId(commentId);
        complaint.setAuthorId(authorId);
        commentComplaintRepository.save(complaint);

        comment.incrementDislikeCount();

        return commentMapper.toResponse(commentRepository.save(comment));
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> getReplies(final UUID parentId)
    {
        return commentMapper.toResponseList(
                commentRepository.findByParentIdAndStatusOrderByCreatedAtAsc(
                        parentId,
                        CommentStatus.PUBLISHED
                )
        );
    }

    @Transactional(readOnly = true)
    public long getCommentCount(
            final String section,
            final String url
    )
    {
        return commentRepository.countBySectionAndUrlAndStatus(
                normalize(section),
                normalize(url),
                CommentStatus.PUBLISHED
        );
    }

    @Transactional
    public CommentResponse createComment(
            final CreateCommentRequest request,
            final UUID authorId,
            final String authorName
    )
    {
        final CreateCommentRequest normalizedRequest = normalizeRequest(request);

        final Comment comment = commentMapper.toEntity(
                normalizedRequest,
                authorId,
                authorName
        );
        comment.setStatus(CommentStatus.PUBLISHED);

        return commentMapper.toResponse(commentRepository.save(comment));
    }

    @Transactional
    public CommentResponse createReply(
            final UUID parentId,
            final CreateCommentRequest request,
            final UUID authorId,
            final String authorName
    )
    {
        final Comment parent = commentRepository.findById(parentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found: " + parentId));

        validateParentForReply(parent);

        final Comment reply = commentMapper.toReply(
                parent.getSection(),
                parent.getUrl(),
                parentId,
                authorId,
                authorName,
                normalizeContent(request.getContent())
        );
        reply.setStatus(CommentStatus.PUBLISHED);

        parent.incrementReplyCount();
        commentRepository.save(parent);

        return commentMapper.toResponse(commentRepository.save(reply));
    }

    @Transactional
    public CommentResponse updateComment(
            final UUID commentId,
            final UUID authorId,
            final UpdateCommentRequest request
    )
    {
        final Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found: " + commentId));

        validateCommentOwnership(comment, authorId);

        if (comment.isDeleted())
        {
            throw new CommentStateException("Comment already deleted");
        }

        comment.setContent(normalizeContent(request.getContent()));
        comment.markAsEdited();

        return commentMapper.toResponse(commentRepository.save(comment));
    }

    @Transactional
    public void deleteComment(
            final UUID commentId,
            final UUID authorId
    )
    {
        final Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found: " + commentId));

        validateCommentOwnership(comment, authorId);

        if (comment.isDeleted())
        {
            return;
        }

        comment.markAsDeleted();
        commentRepository.save(comment);

        if (comment.getParentId() != null)
        {
            commentRepository.findById(comment.getParentId())
                    .ifPresent(parent ->
                    {
                        parent.decrementReplyCount();
                        commentRepository.save(parent);
                    });
        }
    }

    private void validateParentForReply(final Comment parent)
    {
        if (parent.isDeleted())
        {
            throw new CommentStateException("Cannot reply to deleted comment");
        }

        if (parent.getStatus() != CommentStatus.PUBLISHED)
        {
            throw new CommentStateException("Cannot reply to unpublished comment");
        }
    }

    private void validateCommentOwnership(
            final Comment comment,
            final UUID authorId
    )
    {
        if (!comment.getAuthorId().equals(authorId))
        {
            throw new CommentAccessDeniedException("You can modify only your own comment");
        }
    }

    private CreateCommentRequest normalizeRequest(final CreateCommentRequest request)
    {
        final CreateCommentRequest normalizedRequest = new CreateCommentRequest();
        normalizedRequest.setSection(normalize(request.getSection()));
        normalizedRequest.setUrl(normalize(request.getUrl()));
        normalizedRequest.setContent(normalizeContent(request.getContent()));
        return normalizedRequest;
    }

    private String normalize(final String value)
    {
        return value == null ? null : value.trim().toLowerCase();
    }

    private String normalizeContent(final String value)
    {
        return value == null ? null : value.trim();
    }
}