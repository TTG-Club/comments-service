package club.ttg.comment.service;

import club.ttg.comment.dto.request.CreateCommentRequest;
import club.ttg.comment.dto.request.UpdateCommentRequest;
import club.ttg.comment.dto.response.CommentResponse;
import club.ttg.comment.exception.CommentAccessDeniedException;
import club.ttg.comment.exception.CommentStateException;
import club.ttg.comment.mapper.CommentMapperImpl;
import club.ttg.comment.model.Comment;
import club.ttg.comment.model.CommentStatus;
import club.ttg.comment.model.SourcePlatform;
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
import static org.mockito.ArgumentMatchers.isNull;
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

        // Лимитер здесь замокан и всегда пропускает: его поведение проверяется отдельно
        // в CommentRateLimitServiceTest.
        commentService = new CommentService(
                commentRepository,
                complaintRepository,
                new CommentMapperImpl(),
                mock(CommentRateLimitService.class)
        );

        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createReplyIncrementsTotalsAlongWholeAncestorChain()
    {
        final Comment grandparent = published(UUID.randomUUID(), null);
        final Comment parent = published(UUID.randomUUID(), grandparent.getId());

        stubFindById(grandparent, parent);

        commentService.createReply(parent.getId(), request("ответ"), UUID.randomUUID(), "user", false);

        // Прямых детей родителя +1, число потомков +1 у родителя и деда.
        verify(commentRepository).addToReplyCount(parent.getId(), 1);
        verify(commentRepository).addToTotalReplyCount(parent.getId(), 1);
        verify(commentRepository).addToTotalReplyCount(grandparent.getId(), 1);
    }

    @Test
    void createReplyUnderTombstonePropagatesTotalsThroughItToRoot()
    {
        final Comment root = published(UUID.randomUUID(), null);

        final Comment deletedMiddle = published(UUID.randomUUID(), root.getId());
        deletedMiddle.markAsDeleted();

        final Comment orphan = published(UUID.randomUUID(), deletedMiddle.getId());

        stubFindById(root, deletedMiddle, orphan);

        commentService.createReply(orphan.getId(), request("ответ осиротевшему"), UUID.randomUUID(), "user", false);

        verify(commentRepository).addToReplyCount(orphan.getId(), 1);
        verify(commentRepository).addToTotalReplyCount(orphan.getId(), 1);
        // Удалённый узел для счётчиков проницаем: новый потомок входит и в его счётчик
        // (держит надгробие видимым), и в счётчик корня над ним.
        verify(commentRepository).addToTotalReplyCount(deletedMiddle.getId(), 1);
        verify(commentRepository).addToTotalReplyCount(root.getId(), 1);
    }

    @Test
    void deleteCommentDecrementsAncestorsByNodeOnlyKeepingSubtreeCounted()
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
        // Поддерево из 3 ответов остаётся видимым под надгробием и продолжает считаться —
        // из числа потомков деда уходит только сам parent.
        verify(commentRepository).addToTotalReplyCount(grandparent.getId(), -1);
    }

    /**
     * Сервис ничего не удаляет безвозвратно: мягкое удаление меняет статус и проставляет
     * deletedAt, но текст остаётся в базе. Публично он не отдаётся (надгробие приходит
     * без текста), зато остаётся доступен модератору в /moderation.
     */
    @Test
    void deleteKeepsCommentContentInDatabase()
    {
        final UUID authorId = UUID.randomUUID();

        final Comment comment = published(UUID.randomUUID(), null);
        comment.setAuthorId(authorId);
        comment.setContent("исходный текст");

        stubFindById(comment);

        commentService.deleteComment(comment.getId(), authorId, false);

        assertThat(comment.isDeleted()).isTrue();
        assertThat(comment.getDeletedAt()).isNotNull();
        assertThat(comment.getContent()).isEqualTo("исходный текст");
    }

    /** То же для удаления модератором: чужой комментарий тоже не затирается. */
    @Test
    void moderatorDeleteKeepsCommentContentInDatabase()
    {
        final Comment comment = published(UUID.randomUUID(), null);
        comment.setContent("чужой текст");

        stubFindById(comment);

        commentService.deleteComment(comment.getId(), UUID.randomUUID(), true);

        assertThat(comment.isDeleted()).isTrue();
        assertThat(comment.getContent()).isEqualTo("чужой текст");
    }

    @Test
    void deleteOrphanPropagatesMinusOneThroughTombstoneToRoot()
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
        // Минус один — сквозь надгробие до корня: так счётчик надгробия падает до нуля,
        // когда под ним не остаётся опубликованного, и оно уходит из выдачи.
        verify(commentRepository).addToTotalReplyCount(deletedMiddle.getId(), -1);
        verify(commentRepository).addToTotalReplyCount(root.getId(), -1);
    }

    /**
     * Надгробие в публичной выдаче — каркас без содержимого: удалённые текст, автор, отметка
     * о правке и дизлайки не должны утекать даже частями. Каркас (id, место в дереве, счётчики,
     * дата) остаётся — по нему фронт рисует «комментарий удалён» и ветку ответов под ним.
     */
    @Test
    void getRepliesMasksTombstoneButKeepsSkeleton()
    {
        final Comment parent = published(UUID.randomUUID(), null);

        final Comment tombstone = published(UUID.randomUUID(), parent.getId());
        tombstone.setContent("удалённый текст");
        tombstone.setAuthorNameSnapshot("удалённый автор");
        tombstone.setDislikeCount(5);
        tombstone.markAsEdited();
        tombstone.setReplyCount(2);
        tombstone.setTotalReplyCount(3);
        tombstone.markAsDeleted();

        when(commentRepository.findVisibleByParentId(parent.getId())).thenReturn(List.of(tombstone));

        final CommentResponse masked = commentService.getReplies(parent.getId()).get(0);

        assertThat(masked.getStatus()).isEqualTo(CommentStatus.DELETED);
        assertThat(masked.getContent()).isNull();
        assertThat(masked.getAuthorId()).isNull();
        assertThat(masked.getAuthorName()).isNull();
        assertThat(masked.getEditedAt()).isNull();
        assertThat(masked.getDislikeCount()).isNull();
        assertThat(masked.getParentAuthorName()).isNull();

        assertThat(masked.getId()).isEqualTo(tombstone.getId());
        assertThat(masked.getParentId()).isEqualTo(parent.getId());
        assertThat(masked.getReplyCount()).isEqualTo(2);
        assertThat(masked.getTotalReplyCount()).isEqualTo(3);
        assertThat(masked.getCreatedAt()).isEqualTo(tombstone.getCreatedAt());
    }

    @Test
    void getRootCommentsMaskTombstonesAndLeavePublishedIntact()
    {
        final Comment live = published(UUID.randomUUID(), null);
        live.setContent("живой текст");

        final Comment tombstone = published(UUID.randomUUID(), null);
        tombstone.setContent("удалённый текст");
        tombstone.setTotalReplyCount(1);
        tombstone.markAsDeleted();

        when(commentRepository.findVisibleRootComments(
                eq(SourcePlatform.SITE_5E24), eq("blog"), eq("/posts/x"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(live, tombstone)));

        final Page<CommentResponse> page = commentService.getRootComments(null, "Blog", " /posts/X ", PageRequest.of(0, 20));

        assertThat(responseFor(page, live.getId()).getContent()).isEqualTo("живой текст");
        assertThat(responseFor(page, tombstone.getId()).getContent()).isNull();
        assertThat(responseFor(page, tombstone.getId()).getStatus()).isEqualTo(CommentStatus.DELETED);
    }

    /** Надгробие видно в выдаче, но живым узлом не является: отвечать на него нельзя. */
    @Test
    void replyToTombstoneIsRejected()
    {
        final Comment tombstone = published(UUID.randomUUID(), null);
        tombstone.setTotalReplyCount(1);
        tombstone.markAsDeleted();
        stubFindById(tombstone);

        assertThatThrownBy(() -> commentService.createReply(
                tombstone.getId(), request("ответ надгробию"), UUID.randomUUID(), "user", false))
                .isInstanceOf(CommentStateException.class);
    }

    /**
     * Скрытый при бане автора комментарий с живой веткой — такое же надгробие, как удалённый,
     * и наружу уходит под тем же статусом DELETED: публично эти два случая различать незачем,
     * а HIDDEN_BY_BAN в открытой выдаче сообщал бы любому читателю, что автора заблокировали.
     */
    @Test
    void getRootCommentsMaskBanTombstoneAsDeleted()
    {
        final Comment hidden = published(UUID.randomUUID(), null);
        hidden.setContent("текст забаненного");
        hidden.setAuthorNameSnapshot("забаненный");
        hidden.setTotalReplyCount(1);
        hidden.setStatus(CommentStatus.HIDDEN_BY_BAN);

        when(commentRepository.findVisibleRootComments(
                eq(SourcePlatform.SITE_5E24), eq("blog"), eq("/posts/x"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(hidden)));

        final CommentResponse masked =
                commentService.getRootComments(SourcePlatform.SITE_5E24, "blog", "/posts/x", PageRequest.of(0, 20)).getContent().get(0);

        assertThat(masked.getStatus()).isEqualTo(CommentStatus.DELETED);
        assertThat(masked.getContent()).isNull();
        assertThat(masked.getAuthorId()).isNull();
        assertThat(masked.getAuthorName()).isNull();
        // Сущность в базе статус не меняет: по нему разблокировка находит, что вернуть.
        assertThat(hidden.getStatus()).isEqualTo(CommentStatus.HIDDEN_BY_BAN);
    }

    /** Скрытый баном без живых потомков надгробием не становится — обычный 404. */
    @Test
    void getCommentThrowsForBanHiddenWithoutLiveReplies()
    {
        final Comment hidden = published(UUID.randomUUID(), null);
        hidden.setStatus(CommentStatus.HIDDEN_BY_BAN);
        stubFindById(hidden);

        assertThatThrownBy(() -> commentService.getComment(hidden.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    /**
     * Отвечать на надгробие бана нельзя, как и на надгробие удаления. Текст отказа общий —
     * иначе сообщение об ошибке выдавало бы блокировку автора.
     */
    @Test
    void replyToBanTombstoneIsRejected()
    {
        final Comment hidden = published(UUID.randomUUID(), null);
        hidden.setTotalReplyCount(1);
        hidden.setStatus(CommentStatus.HIDDEN_BY_BAN);
        stubFindById(hidden);

        assertThatThrownBy(() -> commentService.createReply(
                hidden.getId(), request("ответ надгробию"), UUID.randomUUID(), "user", false))
                .isInstanceOf(CommentStateException.class)
                .hasMessageContaining("deleted");
    }

    /** Жалоба на надгробие бессмысленна и только замусорила бы ленту модерации. */
    @Test
    void dislikeOnBanTombstoneIsRejected()
    {
        final Comment hidden = published(UUID.randomUUID(), null);
        hidden.setTotalReplyCount(1);
        hidden.setStatus(CommentStatus.HIDDEN_BY_BAN);
        stubFindById(hidden);

        assertThatThrownBy(() -> commentService.dislikeComment(hidden.getId(), UUID.randomUUID()))
                .isInstanceOf(CommentStateException.class);
        verify(commentRepository, never()).incrementDislikeCount(any(UUID.class));
    }

    /**
     * Надгробие бана проницаемо для счётчиков наравне с надгробием удаления: новый ответ под
     * ним входит и в его счётчик (держит надгробие видимым), и в счётчик корня над ним. Пока
     * HIDDEN_BY_BAN был барьером, ветка под комментарием забаненного выпадала из счётчиков
     * предков — и уходила из выдачи вместе с ним.
     */
    @Test
    void createReplyUnderBanTombstonePropagatesTotalsThroughItToRoot()
    {
        final Comment root = published(UUID.randomUUID(), null);

        final Comment hiddenMiddle = published(UUID.randomUUID(), root.getId());
        hiddenMiddle.setStatus(CommentStatus.HIDDEN_BY_BAN);

        final Comment orphan = published(UUID.randomUUID(), hiddenMiddle.getId());

        stubFindById(root, hiddenMiddle, orphan);

        commentService.createReply(orphan.getId(), request("ответ осиротевшему"), UUID.randomUUID(), "user", false);

        verify(commentRepository).addToTotalReplyCount(orphan.getId(), 1);
        verify(commentRepository).addToTotalReplyCount(hiddenMiddle.getId(), 1);
        verify(commentRepository).addToTotalReplyCount(root.getId(), 1);
    }

    @Test
    void restoreReturnsDeletedCommentToPublished()
    {
        final Comment comment = published(UUID.randomUUID(), null);
        comment.setContent("восстанавливаемый текст");
        comment.markAsDeleted();
        stubFindById(comment);

        final CommentResponse response = commentService.restoreComment(comment.getId(), true);

        assertThat(comment.getStatus()).isEqualTo(CommentStatus.PUBLISHED);
        assertThat(comment.isDeleted()).isFalse();
        // Отметка об удалении снимается: повторное удаление должно проставить новую, а не старую.
        assertThat(comment.getDeletedAt()).isNull();
        assertThat(response.getStatus()).isEqualTo(CommentStatus.PUBLISHED);
        assertThat(response.getContent()).isEqualTo("восстанавливаемый текст");
    }

    /**
     * Счётчики после восстановления приводятся полным пересчётом, а не дельтой: сохранённый
     * в удалённой строке totalReplyCount успел устареть, и симметричное сложение разошлось бы
     * с деревом.
     */
    @Test
    void restoreRecalculatesCountersInsteadOfApplyingDeltas()
    {
        final Comment parent = published(UUID.randomUUID(), null);

        final Comment deleted = published(UUID.randomUUID(), parent.getId());
        deleted.setTotalReplyCount(3);
        deleted.markAsDeleted();

        stubFindById(parent, deleted);

        commentService.restoreComment(deleted.getId(), true);

        verify(commentRepository).recalculateReplyCounts();
        verify(commentRepository).recalculateTotalReplyCounts();
        verify(commentRepository, never()).addToReplyCount(any(UUID.class), anyInt());
        verify(commentRepository, never()).addToTotalReplyCount(any(UUID.class), anyInt());
    }

    /**
     * Ветку ответов восстановление не трогает: они и так остались PUBLISHED, а невидимыми их
     * делал обход дерева от корня. Возврат узла поднимает всё поддерево под ним.
     */
    @Test
    void restoreTouchesOnlyTheNodeItself()
    {
        final Comment deleted = published(UUID.randomUUID(), null);
        deleted.markAsDeleted();

        final Comment reply = published(UUID.randomUUID(), deleted.getId());

        stubFindById(deleted, reply);

        commentService.restoreComment(deleted.getId(), true);

        verify(commentRepository).save(deleted);
        verify(commentRepository, never()).save(reply);
        assertThat(reply.getStatus()).isEqualTo(CommentStatus.PUBLISHED);
    }

    @Test
    void restoreRejectsCommentThatIsNotDeleted()
    {
        final Comment live = published(UUID.randomUUID(), null);
        stubFindById(live);

        assertThatThrownBy(() -> commentService.restoreComment(live.getId(), true))
                .isInstanceOf(CommentStateException.class);
    }

    /**
     * Скрытый баном автора не восстанавливается этой ручкой: причина скрытия другая, снимать её
     * должна разблокировка в auth-service — иначе следующая синхронизация вернула бы комментарий
     * обратно в скрытые.
     */
    @Test
    void restoreRejectsCommentHiddenByBan()
    {
        final Comment hidden = published(UUID.randomUUID(), null);
        hidden.setStatus(CommentStatus.HIDDEN_BY_BAN);
        stubFindById(hidden);

        assertThatThrownBy(() -> commentService.restoreComment(hidden.getId(), true))
                .isInstanceOf(CommentStateException.class);
        assertThat(hidden.getStatus()).isEqualTo(CommentStatus.HIDDEN_BY_BAN);
    }

    @Test
    void plainUserCannotRestoreComment()
    {
        final Comment deleted = published(UUID.randomUUID(), null);
        deleted.markAsDeleted();
        stubFindById(deleted);

        // Даже свой удалённый комментарий автор не возвращает — это право модератора.
        assertThatThrownBy(() -> commentService.restoreComment(deleted.getId(), false))
                .isInstanceOf(CommentAccessDeniedException.class);
        assertThat(deleted.isDeleted()).isTrue();
    }

    @Test
    void restoreThrowsForMissingComment()
    {
        final UUID missingId = UUID.randomUUID();
        when(commentRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.restoreComment(missingId, true))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void getLatestReplyFillsParentAuthorName()
    {
        final Comment parent = published(UUID.randomUUID(), null);
        parent.setAuthorNameSnapshot("родитель");

        final Comment latestReply = published(UUID.randomUUID(), parent.getId());
        latestReply.setAuthorNameSnapshot("отвечающий");
        latestReply.setTotalReplyCount(0);

        when(commentRepository.findFirstBySourcePlatformAndSectionAndUrlAndStatusOrderByCreatedAtDescIdDesc(
                SourcePlatform.SITE_5E24, "blog", "/posts/x", CommentStatus.PUBLISHED))
                .thenReturn(Optional.of(latestReply));
        when(commentRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        final Optional<CommentResponse> result = commentService.getLatestComment(null, "Blog", " /posts/X ");

        assertThat(result).isPresent();
        assertThat(result.get().getAuthorName()).isEqualTo("отвечающий");
        assertThat(result.get().getParentAuthorName()).isEqualTo("родитель");
    }

    @Test
    void getLatestRootHasNoParentAuthorName()
    {
        final Comment latestRoot = published(UUID.randomUUID(), null);
        latestRoot.setAuthorNameSnapshot("автор");

        when(commentRepository.findFirstBySourcePlatformAndSectionAndUrlAndStatusOrderByCreatedAtDescIdDesc(
                SourcePlatform.SITE_5E24, "blog", "/x", CommentStatus.PUBLISHED))
                .thenReturn(Optional.of(latestRoot));

        final Optional<CommentResponse> result = commentService.getLatestComment(SourcePlatform.SITE_5E24, "blog", "/x");

        assertThat(result).isPresent();
        assertThat(result.get().getParentAuthorName()).isNull();
    }

    /**
     * Фронт, не знающий про sourcePlatform, продолжает читать обсуждения сайта 2024:
     * отсутствие значения — это запрос старой версии, а не отдельный тред с пустым ключом.
     * На этом держится выкат: сервис уезжает раньше фронтов, и текущий прод не должен
     * заметить миграцию. Значение обязано совпадать с бэкфиллом миграции 008.
     */
    @Test
    void readsFallBackToDefaultPlatformWhenNotProvided()
    {
        commentService.getCommentCount(null, "blog", "/x");

        verify(commentRepository)
                .countBySourcePlatformAndSectionAndUrlAndStatus(
                        Comment.DEFAULT_SOURCE_PLATFORM, "blog", "/x", CommentStatus.PUBLISHED);
    }

    /** Переданная платформа уходит в запрос как есть — дефолт её не перебивает. */
    @Test
    void readsUseProvidedPlatform()
    {
        commentService.getCommentCount(SourcePlatform.SITE_5E14, "blog", "/x");

        verify(commentRepository)
                .countBySourcePlatformAndSectionAndUrlAndStatus(
                        SourcePlatform.SITE_5E14, "blog", "/x", CommentStatus.PUBLISHED);
    }

    /** Ответ наследует платформу родителя: тред не может разъехаться внутри одной ветки. */
    @Test
    void replyInheritsSourcePlatformFromParent()
    {
        final Comment parent = published(UUID.randomUUID(), null);
        parent.setSourcePlatform(SourcePlatform.SITE_5E14);
        parent.setSection("spells");
        parent.setUrl("/spells/acid_splash");

        when(commentRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        final CreateCommentRequest request = new CreateCommentRequest();
        request.setContent("ответ");

        final CommentResponse reply =
                commentService.createReply(parent.getId(), request, UUID.randomUUID(), "автор", false);

        assertThat(reply.getSourcePlatform()).isEqualTo(SourcePlatform.SITE_5E14);
        assertThat(reply.getSection()).isEqualTo("spells");
    }

    @Test
    void getLatestReturnsEmptyWhenPageHasNoComments()
    {
        when(commentRepository.findFirstBySourcePlatformAndSectionAndUrlAndStatusOrderByCreatedAtDescIdDesc(
                any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(commentService.getLatestComment(SourcePlatform.SITE_5E24, "blog", "/x")).isEmpty();
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

    /** Удалённый без опубликованных потомков — не надгробие, а обычный 404. */
    @Test
    void getCommentThrowsForDeletedWithoutLiveReplies()
    {
        final Comment deleted = published(UUID.randomUUID(), null);
        deleted.markAsDeleted();
        stubFindById(deleted);

        assertThatThrownBy(() -> commentService.getComment(deleted.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    /**
     * Удалённый с живой веткой отдаётся по прямой ссылке надгробием: фронт поднимается по
     * parentId к корню, и удалённое звено не должно обрывать цепочку. Текст и автор при этом
     * не утекают.
     */
    @Test
    void getCommentReturnsMaskedTombstoneWhenBranchIsAlive()
    {
        final Comment tombstone = published(UUID.randomUUID(), null);
        tombstone.setContent("удалённый текст");
        tombstone.setAuthorNameSnapshot("удалённый автор");
        tombstone.setTotalReplyCount(2);
        tombstone.markAsDeleted();
        stubFindById(tombstone);

        final CommentResponse response = commentService.getComment(tombstone.getId());

        assertThat(response.getId()).isEqualTo(tombstone.getId());
        assertThat(response.getStatus()).isEqualTo(CommentStatus.DELETED);
        assertThat(response.getContent()).isNull();
        assertThat(response.getAuthorId()).isNull();
        assertThat(response.getAuthorName()).isNull();
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
    void userCommentCountSkipsDeleted()
    {
        final UUID authorId = UUID.randomUUID();

        final Comment first = authoredBy(authorId);
        final Comment second = authoredBy(authorId);
        final Comment deleted = authoredBy(authorId);
        deleted.markAsDeleted();

        stubCountByAuthorAndStatus(first, second, deleted);

        // Удалённый в «живой» вклад профиля не входит.
        assertThat(commentService.getUserCommentCount(authorId)).isEqualTo(2);
    }

    @Test
    void userCommentCountIsZeroWithoutComments()
    {
        stubCountByAuthorAndStatus();

        assertThat(commentService.getUserCommentCount(UUID.randomUUID())).isZero();
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
        when(commentRepository.findForModeration(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(parent, reply)));

        final Page<CommentResponse> page = commentService.getAllComments(null, null, PageRequest.of(0, 20));

        assertThat(responseFor(page, reply.getId()).getParentAuthorName()).isEqualTo("родитель");
        assertThat(responseFor(page, parent.getId()).getParentAuthorName()).isNull();
    }

    /**
     * Без параметров оба фильтра — null, и единый запрос модерации возвращает общую ленту
     * по всем платформам и авторам. На этом держится существующая страница модерации.
     */
    @Test
    void moderationListWithoutFiltersPassesBothNulls()
    {
        when(commentRepository.findForModeration(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        commentService.getAllComments(null, null, PageRequest.of(0, 20));

        verify(commentRepository).findForModeration(isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void moderationListWithAuthorFiltersByThatAuthor()
    {
        final UUID authorId = UUID.randomUUID();
        final Comment own = authoredBy(authorId);

        when(commentRepository.findForModeration(isNull(), eq(authorId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(own)));

        final Page<CommentResponse> page = commentService.getAllComments(null, authorId, PageRequest.of(0, 20));

        assertThat(page.getContent()).singleElement()
                .extracting(CommentResponse::getAuthorId).isEqualTo(authorId);
    }

    @Test
    void moderationListWithPlatformFiltersByThatPlatform()
    {
        when(commentRepository.findForModeration(eq(SourcePlatform.SITE_5E14), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        commentService.getAllComments(SourcePlatform.SITE_5E14, null, PageRequest.of(0, 20));

        verify(commentRepository).findForModeration(eq(SourcePlatform.SITE_5E14), isNull(), any(Pageable.class));
    }

    /**
     * Переименование скоупится платформой: authorId общий у пользователя на всех сайтах,
     * поэтому имя, заданное на одном сайте, не должно трогать комментарии на других. Имя
     * перед запросом обрезается от пробелов.
     */
    @Test
    void renameAuthorScopesByPlatformAndTrims()
    {
        final UUID authorId = UUID.randomUUID();

        commentService.renameAuthor(authorId, SourcePlatform.SITE_5E14, "  Новое имя  ");

        verify(commentRepository).renameAuthor(
                eq(authorId), eq(SourcePlatform.SITE_5E14), eq("Новое имя"));
    }

    /** Без платформы (null) правятся комментарии автора на всех сайтах. */
    @Test
    void renameAuthorWithoutPlatformAffectsAll()
    {
        final UUID authorId = UUID.randomUUID();

        commentService.renameAuthor(authorId, null, "Имя");

        verify(commentRepository).renameAuthor(eq(authorId), isNull(), eq("Имя"));
    }

    /**
     * Ради чего фильтр и делался: в карточке пользователя админка показывает и удалённые,
     * и скрытые баном комментарии — со ссылкой на страницу (section + url). Фильтра по статусу
     * здесь нет, как и в общей ленте модерации.
     */
    @Test
    void moderationListByAuthorKeepsDeletedAndHiddenByBan()
    {
        final UUID authorId = UUID.randomUUID();

        final Comment live = authoredBy(authorId);

        final Comment deleted = authoredBy(authorId);
        deleted.setContent("удалённый текст");
        deleted.markAsDeleted();

        final Comment hidden = authoredBy(authorId);
        hidden.setStatus(CommentStatus.HIDDEN_BY_BAN);

        when(commentRepository.findForModeration(isNull(), eq(authorId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(live, deleted, hidden)));

        final Page<CommentResponse> page = commentService.getAllComments(null, authorId, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(CommentResponse::getStatus)
                .containsExactlyInAnyOrder(
                        CommentStatus.PUBLISHED,
                        CommentStatus.DELETED,
                        CommentStatus.HIDDEN_BY_BAN
                );

        // Ссылка на страницу, где комментарий оставлен, и текст удалённого — то, что нужно админке.
        assertThat(responseFor(page, deleted.getId()).getContent()).isEqualTo("удалённый текст");
        assertThat(responseFor(page, deleted.getId()).getSection()).isEqualTo("blog");
        assertThat(responseFor(page, deleted.getId()).getUrl()).isEqualTo("/posts/x");
    }

    /**
     * Обратная сторона того, что текст больше не затирается: в модераторском списке (он идёт
     * без фильтра по статусу) виден полный текст удалённого комментария. Публично он не
     * доступен нигде — все пользовательские выдачи фильтруют PUBLISHED.
     */
    @Test
    void moderationListShowsContentOfDeletedComment()
    {
        final Comment deleted = published(UUID.randomUUID(), null);
        deleted.setContent("удалённый текст");
        deleted.markAsDeleted();

        when(commentRepository.findForModeration(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(deleted)));

        final Page<CommentResponse> page = commentService.getAllComments(null, null, PageRequest.of(0, 20));

        assertThat(responseFor(page, deleted.getId()).getContent()).isEqualTo("удалённый текст");
        assertThat(responseFor(page, deleted.getId()).getStatus()).isEqualTo(CommentStatus.DELETED);
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
        when(commentRepository.findDislikedForModeration(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(reply)));

        final Page<CommentResponse> page = commentService.getDislikedComments(null, PageRequest.of(0, 20));

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

    private static Comment authoredBy(final UUID authorId)
    {
        final Comment comment = published(UUID.randomUUID(), null);
        comment.setAuthorId(authorId);
        return comment;
    }

    /**
     * Считает по переданным комментариям так же, как это сделала бы БД по derived-запросу —
     * с фильтром и по автору, и по статусу. Благодаря этому тест ловит подсчёт не того статуса,
     * а не просто возвращает заранее заданное число.
     */
    private void stubCountByAuthorAndStatus(final Comment... comments)
    {
        when(commentRepository.countByAuthorIdAndStatus(any(UUID.class), any(CommentStatus.class)))
                .thenAnswer(invocation -> {
                    final UUID authorId = invocation.getArgument(0);
                    final CommentStatus status = invocation.getArgument(1);

                    return List.of(comments).stream()
                            .filter(comment -> authorId.equals(comment.getAuthorId()))
                            .filter(comment -> comment.getStatus() == status)
                            .count();
                });
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
