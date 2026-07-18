package club.ttg.comment.service;

import club.ttg.comment.dto.request.CreateCommentRequest;
import club.ttg.comment.dto.request.UpdateCommentRequest;
import club.ttg.comment.dto.response.CommentResponse;
import club.ttg.comment.exception.CommentAccessDeniedException;
import club.ttg.comment.mapper.CommentMapperImpl;
import club.ttg.comment.model.Comment;
import club.ttg.comment.model.CommentStatus;
import club.ttg.comment.repository.CommentComplaintRepository;
import club.ttg.comment.repository.CommentRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Проверяет сопровождение счётчиков при создании ответов и удалении, а также getLatestComment
 * и getComment. Репозитории замоканы, маппер настоящий. Счётчики теперь меняются атомарными
 * методами репозитория (addToReplyCount/addToTotalReplyCount), поэтому проверяются вызовы этих
 * методов с нужными аргументами, а не состояние сущности в памяти.
 */
class CommentServiceTest
{
    private CommentRepository commentRepository;
    private CommentService commentService;

    @BeforeEach
    void setUp()
    {
        commentRepository = mock(CommentRepository.class);
        final CommentComplaintRepository complaintRepository = mock(CommentComplaintRepository.class);
        commentService = new CommentService(commentRepository, complaintRepository, new CommentMapperImpl());

        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createReplyIncrementsTotalsAlongWholeAncestorChain()
    {
        final Comment grandparent = published(UUID.randomUUID(), null);
        final Comment parent = published(UUID.randomUUID(), grandparent.getId());

        stubFindById(grandparent, parent);

        commentService.createReply(parent.getId(), request("ответ"), UUID.randomUUID(), "user");

        // Прямых детей родителя +1, число потомков +1 у родителя и деда.
        verify(commentRepository).addToReplyCount(parent.getId(), 1);
        verify(commentRepository).addToTotalReplyCount(parent.getId(), 1);
        verify(commentRepository).addToTotalReplyCount(grandparent.getId(), 1);
    }

    @Test
    void createReplyToOrphanStopsAtDeletedAncestor()
    {
        final Comment root = published(UUID.randomUUID(), null);

        final Comment deletedMiddle = published(UUID.randomUUID(), root.getId());
        deletedMiddle.markAsDeleted();

        final Comment orphan = published(UUID.randomUUID(), deletedMiddle.getId());

        stubFindById(root, deletedMiddle, orphan);

        commentService.createReply(orphan.getId(), request("ответ осиротевшему"), UUID.randomUUID(), "user");

        verify(commentRepository).addToReplyCount(orphan.getId(), 1);
        verify(commentRepository).addToTotalReplyCount(orphan.getId(), 1);
        // Обход остановился на удалённом узле — корень не тронут.
        verify(commentRepository, never()).addToTotalReplyCount(eq(root.getId()), anyInt());
    }

    @Test
    void deleteCommentDecrementsAncestorsBySubtreePlusSelf()
    {
        final UUID authorId = UUID.randomUUID();

        final Comment grandparent = published(UUID.randomUUID(), null);

        final Comment parent = published(UUID.randomUUID(), grandparent.getId());
        parent.setAuthorId(authorId);
        parent.setTotalReplyCount(3);

        stubFindById(grandparent, parent);

        commentService.deleteComment(parent.getId(), authorId, false);

        assertThat(parent.isDeleted()).isTrue();
        verify(commentRepository).addToReplyCount(grandparent.getId(), -1);
        // Из числа потомков деда уходит сам parent и его поддерево из 3 → -4.
        verify(commentRepository).addToTotalReplyCount(grandparent.getId(), -4);
    }

    @Test
    void deleteOrphanStopsAtDeletedAncestorAndLeavesRootUntouched()
    {
        final UUID authorId = UUID.randomUUID();

        final Comment root = published(UUID.randomUUID(), null);

        final Comment deletedMiddle = published(UUID.randomUUID(), root.getId());
        deletedMiddle.markAsDeleted();

        final Comment orphan = published(UUID.randomUUID(), deletedMiddle.getId());
        orphan.setAuthorId(authorId);
        orphan.setTotalReplyCount(2);

        stubFindById(root, deletedMiddle, orphan);

        commentService.deleteComment(orphan.getId(), authorId, false);

        assertThat(orphan.isDeleted()).isTrue();
        verify(commentRepository).addToReplyCount(deletedMiddle.getId(), -1);
        // Обход totalReplyCount остановился на удалённом родителе — корень не тронут.
        verify(commentRepository, never()).addToTotalReplyCount(eq(root.getId()), anyInt());
    }

    @Test
    void getLatestReplyFillsParentAuthorName()
    {
        final Comment parent = published(UUID.randomUUID(), null);
        parent.setAuthorNameSnapshot("родитель");

        final Comment latestReply = published(UUID.randomUUID(), parent.getId());
        latestReply.setAuthorNameSnapshot("отвечающий");
        latestReply.setTotalReplyCount(0);

        when(commentRepository.findFirstBySectionAndUrlAndStatusOrderByCreatedAtDescIdDesc(
                "blog", "/posts/x", CommentStatus.PUBLISHED))
                .thenReturn(Optional.of(latestReply));
        when(commentRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        final Optional<CommentResponse> result = commentService.getLatestComment("Blog", " /posts/X ");

        assertThat(result).isPresent();
        assertThat(result.get().getAuthorName()).isEqualTo("отвечающий");
        assertThat(result.get().getParentAuthorName()).isEqualTo("родитель");
    }

    @Test
    void getLatestRootHasNoParentAuthorName()
    {
        final Comment latestRoot = published(UUID.randomUUID(), null);
        latestRoot.setAuthorNameSnapshot("автор");

        when(commentRepository.findFirstBySectionAndUrlAndStatusOrderByCreatedAtDescIdDesc(
                "blog", "/x", CommentStatus.PUBLISHED))
                .thenReturn(Optional.of(latestRoot));

        final Optional<CommentResponse> result = commentService.getLatestComment("blog", "/x");

        assertThat(result).isPresent();
        assertThat(result.get().getParentAuthorName()).isNull();
    }

    @Test
    void getLatestReturnsEmptyWhenPageHasNoComments()
    {
        when(commentRepository.findFirstBySectionAndUrlAndStatusOrderByCreatedAtDescIdDesc(
                any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(commentService.getLatestComment("blog", "/x")).isEmpty();
    }

    @Test
    void getCommentReturnsPublishedWithParentAuthorName()
    {
        final Comment parent = published(UUID.randomUUID(), null);
        parent.setAuthorNameSnapshot("родитель");

        final Comment reply = published(UUID.randomUUID(), parent.getId());
        reply.setAuthorNameSnapshot("отвечающий");

        stubFindById(parent, reply);

        final CommentResponse result = commentService.getComment(reply.getId());

        assertThat(result.getAuthorName()).isEqualTo("отвечающий");
        assertThat(result.getParentAuthorName()).isEqualTo("родитель");
    }

    @Test
    void getCommentDoesNotLeakDeletedParentAuthorName()
    {
        final Comment deletedParent = published(UUID.randomUUID(), null);
        deletedParent.setAuthorNameSnapshot("удалённый родитель");
        deletedParent.markAsDeleted();

        final Comment orphan = published(UUID.randomUUID(), deletedParent.getId());
        orphan.setAuthorNameSnapshot("осиротевший");

        stubFindById(deletedParent, orphan);

        final CommentResponse result = commentService.getComment(orphan.getId());

        assertThat(result.getParentAuthorName()).isNull();
    }

    @Test
    void getCommentThrowsForDeleted()
    {
        final Comment deleted = published(UUID.randomUUID(), null);
        deleted.markAsDeleted();
        stubFindById(deleted);

        assertThatThrownBy(() -> commentService.getComment(deleted.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void getCommentThrowsForMissing()
    {
        final UUID missingId = UUID.randomUUID();
        when(commentRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.getComment(missingId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void moderatorDeletesSomeoneElsesComment()
    {
        final Comment comment = published(UUID.randomUUID(), null);
        stubFindById(comment);

        // userId — не автор; canModerate == true даёт право удалить чужой.
        commentService.deleteComment(comment.getId(), UUID.randomUUID(), true);

        assertThat(comment.isDeleted()).isTrue();
    }

    @Test
    void plainUserCannotDeleteSomeoneElsesComment()
    {
        final Comment comment = published(UUID.randomUUID(), null);
        stubFindById(comment);

        assertThatThrownBy(() -> commentService.deleteComment(comment.getId(), UUID.randomUUID(), false))
                .isInstanceOf(CommentAccessDeniedException.class);
        assertThat(comment.isDeleted()).isFalse();
    }

    @Test
    void moderatorEditsSomeoneElsesCommentWithoutChangingAuthor()
    {
        final UUID originalAuthorId = UUID.randomUUID();
        final Comment comment = published(UUID.randomUUID(), null);
        comment.setAuthorId(originalAuthorId);
        comment.setAuthorNameSnapshot("автор");
        stubFindById(comment);

        final CommentResponse response = commentService.updateComment(
                comment.getId(), UUID.randomUUID(), updateRequest("отмодерировано"), true);

        assertThat(response.getContent()).isEqualTo("отмодерировано");
        assertThat(comment.getEditedAt()).isNotNull();
        assertThat(comment.getAuthorId()).isEqualTo(originalAuthorId);
        assertThat(comment.getAuthorNameSnapshot()).isEqualTo("автор");
    }

    @Test
    void plainUserCannotEditSomeoneElsesComment()
    {
        final Comment comment = published(UUID.randomUUID(), null);
        stubFindById(comment);

        assertThatThrownBy(() -> commentService.updateComment(
                comment.getId(), UUID.randomUUID(), updateRequest("правка"), false))
                .isInstanceOf(CommentAccessDeniedException.class);
    }

    @Test
    void moderationListFillsParentAuthorNameForReplies()
    {
        final Comment parent = published(UUID.randomUUID(), null);
        parent.setAuthorNameSnapshot("родитель");

        final Comment reply = published(UUID.randomUUID(), parent.getId());
        reply.setAuthorNameSnapshot("отвечающий");

        when(commentRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(commentRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(parent, reply)));

        final Page<CommentResponse> page = commentService.getAllComments(PageRequest.of(0, 20));

        assertThat(responseFor(page, reply.getId()).getParentAuthorName()).isEqualTo("родитель");
        assertThat(responseFor(page, parent.getId()).getParentAuthorName()).isNull();
    }

    @Test
    void dislikedModerationListFillsParentAuthorNameForReplies()
    {
        final Comment parent = published(UUID.randomUUID(), null);
        parent.setAuthorNameSnapshot("родитель");

        final Comment reply = published(UUID.randomUUID(), parent.getId());
        reply.setAuthorNameSnapshot("отвечающий");
        reply.setDislikeCount(2);

        when(commentRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(commentRepository.findByDislikeCountGreaterThan(eq(0), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(reply)));

        final Page<CommentResponse> page = commentService.getDislikedComments(PageRequest.of(0, 20));

        assertThat(responseFor(page, reply.getId()).getParentAuthorName()).isEqualTo("родитель");
    }

    private static CommentResponse responseFor(final Page<CommentResponse> page, final UUID id)
    {
        return page.getContent().stream()
                .filter(response -> id.equals(response.getId()))
                .findFirst()
                .orElseThrow();
    }

    private static Comment published(final UUID id, final UUID parentId)
    {
        final Comment comment = new Comment();
        comment.setId(id);
        comment.setParentId(parentId);
        comment.setAuthorId(UUID.randomUUID());
        comment.setSection("blog");
        comment.setUrl("/posts/x");
        comment.setContent("текст");
        comment.setStatus(CommentStatus.PUBLISHED);
        comment.setReplyCount(0);
        comment.setTotalReplyCount(0);
        comment.setDislikeCount(0);
        return comment;
    }

    private void stubFindById(final Comment... comments)
    {
        for (final Comment comment : comments)
        {
            when(commentRepository.findById(comment.getId())).thenReturn(Optional.of(comment));
        }
    }

    private static CreateCommentRequest request(final String content)
    {
        final CreateCommentRequest request = new CreateCommentRequest();
        request.setContent(content);
        return request;
    }

    private static UpdateCommentRequest updateRequest(final String content)
    {
        final UpdateCommentRequest request = new UpdateCommentRequest();
        request.setContent(content);
        return request;
    }
}
