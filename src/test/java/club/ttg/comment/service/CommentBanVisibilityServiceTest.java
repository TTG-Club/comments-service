package club.ttg.comment.service;

import club.ttg.comment.mapper.CommentMapperImpl;
import club.ttg.comment.model.Comment;
import club.ttg.comment.model.CommentStatus;
import club.ttg.comment.repository.CommentComplaintRepository;
import club.ttg.comment.repository.CommentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Проверяет оркестрацию скрытия и восстановления комментариев забаненного автора: какой запрос
 * вызывается и когда пересчитываются счётчики. Сами SQL-запросы (что именно переводится и как
 * считается дерево) проверяются на настоящем PostgreSQL в
 * {@link CommentBanVisibilityIntegrationTest} — здесь репозиторий замокан.
 */
class CommentBanVisibilityServiceTest
{
    private CommentRepository commentRepository;
    private CommentService commentService;

    @BeforeEach
    void setUp()
    {
        commentRepository = mock(CommentRepository.class);

        commentService = new CommentService(
                commentRepository,
                mock(CommentComplaintRepository.class),
                new CommentMapperImpl(),
                mock(CommentRateLimitService.class)
        );
    }

    @Test
    void hideRunsBulkQueryThenRecalculatesCounters()
    {
        final UUID authorId = UUID.randomUUID();
        when(commentRepository.hidePublishedByAuthor(authorId)).thenReturn(3);

        assertThat(commentService.hideCommentsByAuthor(authorId)).isEqualTo(3);

        // Порядок несущий: пересчёт до смены статусов увидел бы старое дерево.
        final InOrder order = inOrder(commentRepository);
        order.verify(commentRepository).hidePublishedByAuthor(authorId);
        order.verify(commentRepository).recalculateReplyCounts();
        order.verify(commentRepository).recalculateTotalReplyCounts();

        // Скрытие не должно случайно вызывать восстановление.
        verify(commentRepository, never()).restoreHiddenByBanByAuthor(any());
    }

    @Test
    void restoreRunsBulkQueryThenRecalculatesCounters()
    {
        final UUID authorId = UUID.randomUUID();
        when(commentRepository.restoreHiddenByBanByAuthor(authorId)).thenReturn(2);

        assertThat(commentService.restoreCommentsByAuthor(authorId)).isEqualTo(2);

        final InOrder order = inOrder(commentRepository);
        order.verify(commentRepository).restoreHiddenByBanByAuthor(authorId);
        order.verify(commentRepository).recalculateReplyCounts();
        order.verify(commentRepository).recalculateTotalReplyCounts();

        verify(commentRepository, never()).hidePublishedByAuthor(any());
    }

    /**
     * Массовое скрытие не должно идти дельтами, как удаление одного комментария: у забаненного
     * автора комментарии могут быть вложены друг в друга, и вычитание {@code 1 + totalReplyCount}
     * по каждому задвоилось бы. Отсюда — никаких addToReplyCount/addToTotalReplyCount и никакого
     * сохранения сущностей по одной (заодно это исключает случайный перевод в DELETED, который
     * разблокировка снять уже не смогла бы).
     */
    @Test
    void hideDoesNotAdjustCountersByDeltas()
    {
        final UUID authorId = UUID.randomUUID();
        when(commentRepository.hidePublishedByAuthor(authorId)).thenReturn(1);

        commentService.hideCommentsByAuthor(authorId);

        verify(commentRepository, never()).save(any(Comment.class));
        verify(commentRepository, never()).addToReplyCount(any(), anyInt());
        verify(commentRepository, never()).addToTotalReplyCount(any(), anyInt());
    }

    @Test
    void restoreDoesNotAdjustCountersByDeltas()
    {
        final UUID authorId = UUID.randomUUID();
        when(commentRepository.restoreHiddenByBanByAuthor(authorId)).thenReturn(1);

        commentService.restoreCommentsByAuthor(authorId);

        verify(commentRepository, never()).save(any(Comment.class));
        verify(commentRepository, never()).addToReplyCount(any(), anyInt());
        verify(commentRepository, never()).addToTotalReplyCount(any(), anyInt());
    }

    /**
     * Скрытый баном комментарий модератор видит в общем списке и может удалить. Дельты для
     * такого перехода неприменимы: вклад узла в счётчики предков зависит от формы дерева и
     * статусов вокруг, а не от одного числа. Отсюда полный пересчёт — общая ветка для всех
     * не-PUBLISHED статусов. Для HIDDEN_BY_BAN он избыточен (оба надгробных статуса одинаково
     * проницаемы, форма дерева не меняется), но идемпотентен, и отдельной ветки не стоит.
     */
    @Test
    void deletingAlreadyHiddenCommentRecalculatesInsteadOfApplyingDeltas()
    {
        final UUID authorId = UUID.randomUUID();

        final Comment parent = new Comment();
        parent.setId(UUID.randomUUID());
        parent.setAuthorId(UUID.randomUUID());
        parent.setStatus(CommentStatus.PUBLISHED);
        parent.setContent("родитель");

        final Comment hidden = new Comment();
        hidden.setId(UUID.randomUUID());
        hidden.setParentId(parent.getId());
        hidden.setAuthorId(authorId);
        hidden.setStatus(CommentStatus.HIDDEN_BY_BAN);
        hidden.setContent("скрыт баном");
        hidden.setTotalReplyCount(2);

        when(commentRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(commentRepository.findById(hidden.getId())).thenReturn(Optional.of(hidden));
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        commentService.deleteComment(hidden.getId(), authorId, true);

        assertThat(hidden.isDeleted()).isTrue();
        verify(commentRepository, never()).addToReplyCount(any(), anyInt());
        verify(commentRepository, never()).addToTotalReplyCount(any(), anyInt());
        verify(commentRepository).recalculateReplyCounts();
        verify(commentRepository).recalculateTotalReplyCounts();
    }

    /**
     * Повторный вызов ничего не находит — и не должен гонять пересчёт по всему дереву
     * комментариев впустую.
     */
    @Test
    void repeatedHideChangesNothing()
    {
        final UUID authorId = UUID.randomUUID();
        when(commentRepository.hidePublishedByAuthor(authorId)).thenReturn(0);

        assertThat(commentService.hideCommentsByAuthor(authorId)).isZero();

        verify(commentRepository, never()).recalculateReplyCounts();
        verify(commentRepository, never()).recalculateTotalReplyCounts();
    }

    @Test
    void repeatedRestoreChangesNothing()
    {
        final UUID authorId = UUID.randomUUID();
        when(commentRepository.restoreHiddenByBanByAuthor(authorId)).thenReturn(0);

        assertThat(commentService.restoreCommentsByAuthor(authorId)).isZero();

        verify(commentRepository, never()).recalculateReplyCounts();
        verify(commentRepository, never()).recalculateTotalReplyCounts();
    }
}
