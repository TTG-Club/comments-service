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
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CommentService
{
    private final CommentRepository commentRepository;
    private final CommentComplaintRepository commentComplaintRepository;
    private final CommentMapper commentMapper;
    private final CommentRateLimitService commentRateLimitService;

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
        ).map(this::buildResponse);
    }

    /**
     * Лента модерации: все комментарии независимо от статуса, от новых к старым.
     * {@code authorId == null} — вся лента (поведение до появления фильтра, на нём держится
     * страница модерации); иначе только комментарии этого автора, для карточки пользователя
     * в админке.
     * <p>
     * Фильтра по статусу нет ни в одной из веток сознательно: и удалённые, и скрытые баном
     * должны быть видны модератору — группировку по статусам делает фронт.
     */
    @Transactional(readOnly = true)
    public Page<CommentResponse> getAllComments(
            final UUID authorId,
            final Pageable pageable
    )
    {
        final Pageable sortedByNewest = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        final Page<Comment> comments = authorId == null
                ? commentRepository.findAll(sortedByNewest)
                : commentRepository.findByAuthorId(authorId, sortedByNewest);

        return comments.map(this::buildResponse);
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
                .map(this::buildResponse);
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

        final int dislikesBefore = comment.getDislikeCount() == null ? 0 : comment.getDislikeCount();
        commentRepository.incrementDislikeCount(commentId);

        // Сущность не трогаем (иначе её dirty-update перезатёр бы атомарный инкремент);
        // актуальное значение проставляем прямо в DTO.
        final CommentResponse response = buildResponse(comment);
        response.setDislikeCount(dislikesBefore + 1);
        return response;
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> getReplies(final UUID parentId)
    {
        return commentRepository.findByParentIdAndStatusOrderByCreatedAtAsc(
                        parentId,
                        CommentStatus.PUBLISHED
                )
                .stream()
                .map(this::buildResponse)
                .toList();
    }

    /**
     * Один опубликованный комментарий по id (для перехода по прямой ссылке). Удалённый или
     * несуществующий — 404. Фронт по {@code parentId} поднимается по цепочке к корню.
     */
    @Transactional(readOnly = true)
    public CommentResponse getComment(final UUID commentId)
    {
        final Comment comment = commentRepository.findById(commentId)
                .filter(candidate -> candidate.getStatus() == CommentStatus.PUBLISHED)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found: " + commentId));

        return buildResponse(comment);
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

    /**
     * Число комментариев пользователя для его профиля. Считаются только PUBLISHED: удалённые,
     * отклонённые модерацией и спам в «живой» вклад не входят. Ответы от корневых комментариев
     * не отличаются — учитывается всё написанное пользователем.
     */
    @Transactional(readOnly = true)
    public long getUserCommentCount(final UUID authorId)
    {
        return commentRepository.countByAuthorIdAndStatus(authorId, CommentStatus.PUBLISHED);
    }

    /**
     * Самый свежий опубликованный комментарий страницы с учётом ответов (для свёрнутого
     * блока на фронте). {@code parentAuthorName} заполняется, только если это ответ —
     * тогда в нём имя автора родителя (кому отвечали) для подписи в превью.
     */
    @Transactional(readOnly = true)
    public Optional<CommentResponse> getLatestComment(
            final String section,
            final String url
    )
    {
        return commentRepository.findFirstBySectionAndUrlAndStatusOrderByCreatedAtDescIdDesc(
                normalize(section),
                normalize(url),
                CommentStatus.PUBLISHED
        ).map(this::buildResponse);
    }

    /**
     * Маппит комментарий в ответ и проставляет {@code parentAuthorName} — имя автора родителя
     * (кому отвечали), либо null для корневого. Для родителя нужен точечный поиск по id;
     * у корней ({@code parentId == null}) запрос не выполняется, а для набора ответов одного
     * родителя повторные вызовы обслуживаются кэшем персистентности в пределах транзакции.
     */
    private CommentResponse buildResponse(final Comment comment)
    {
        final CommentResponse response = commentMapper.toResponse(comment);
        response.setParentAuthorName(resolveParentAuthorName(comment.getParentId()));
        return response;
    }

    private String resolveParentAuthorName(final UUID parentId)
    {
        if (parentId == null)
        {
            return null;
        }

        return commentRepository.findById(parentId)
                .filter(parent -> parent.getStatus() == CommentStatus.PUBLISHED)
                .map(Comment::getAuthorNameSnapshot)
                .orElse(null);
    }

    /**
     * Создаёт корневой комментарий. Перед сохранением проверяется антиспам-лимит;
     * {@code canModerate == true} освобождает от него модератора и администратора.
     */
    @Transactional
    public CommentResponse createComment(
            final CreateCommentRequest request,
            final UUID authorId,
            final String authorName,
            final boolean canModerate
    )
    {
        commentRateLimitService.ensureCanPost(authorId, canModerate);

        final CreateCommentRequest normalizedRequest = normalizeRequest(request);

        final Comment comment = commentMapper.toEntity(
                normalizedRequest,
                authorId,
                authorName
        );
        comment.setStatus(CommentStatus.PUBLISHED);

        return buildResponse(commentRepository.save(comment));
    }

    /**
     * Создаёт ответ. Антиспам-лимит общий с корневыми комментариями — иначе его можно было бы
     * обойти, спамя ответами. Проверка идёт после проверок родителя: запрос к несуществующему
     * или удалённому комментарию не должен тратить попытку автора.
     */
    @Transactional
    public CommentResponse createReply(
            final UUID parentId,
            final CreateCommentRequest request,
            final UUID authorId,
            final String authorName,
            final boolean canModerate
    )
    {
        final Comment parent = commentRepository.findById(parentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found: " + parentId));

        validateParentForReply(parent);

        commentRateLimitService.ensureCanPost(authorId, canModerate);

        final Comment reply = commentMapper.toReply(
                parent.getSection(),
                parent.getUrl(),
                parentId,
                authorId,
                authorName,
                normalizeContent(request.getContent())
        );
        reply.setStatus(CommentStatus.PUBLISHED);

        final Comment savedReply = commentRepository.save(reply);

        // Прямых детей у родителя стало на 1 больше; всем предкам по цепочке +1 к числу потомков.
        commentRepository.addToReplyCount(parentId, 1);
        adjustAncestorTotals(parentId, 1);

        return buildResponse(savedReply);
    }

    /**
     * Редактирует текст комментария. Автор правит только свой; модератор и администратор
     * ({@code canModerate == true}) — любой (модерация оскорбительного текста без удаления
     * всей ветки). {@code authorId}/{@code authorNameSnapshot} не меняются, {@code editedAt}
     * обновляется как при обычной правке.
     */
    @Transactional
    public CommentResponse updateComment(
            final UUID commentId,
            final UUID userId,
            final UpdateCommentRequest request,
            final boolean canModerate
    )
    {
        final Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found: " + commentId));

        validateCommentAccess(comment, userId, canModerate);

        if (comment.isDeleted())
        {
            throw new CommentStateException("Comment already deleted");
        }

        comment.setContent(normalizeContent(request.getContent()));
        comment.markAsEdited();

        return buildResponse(commentRepository.save(comment));
    }

    /**
     * Мягко удаляет комментарий (статус DELETED, ветка ответов скрывается из выдачи). Автор
     * удаляет только свой; модератор и администратор ({@code canModerate == true}) — любой.
     * <p>
     * Удаление мягкое во всём: строка и её текст остаются в базе, публично не отдаются нигде,
     * но видны модератору в {@code /moderation}. Отменить удаление может только модератор или
     * администратор — через {@link #restoreComment}; ни автор, ни разблокировка автора статус
     * DELETED не снимают.
     */
    @Transactional
    public void deleteComment(
            final UUID commentId,
            final UUID userId,
            final boolean canModerate
    )
    {
        final Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found: " + commentId));

        validateCommentAccess(comment, userId, canModerate);

        if (comment.isDeleted())
        {
            return;
        }

        // Вычитать из счётчиков предков нужно только то, что в них входило. В счётчики входят
        // лишь PUBLISHED-узлы, поэтому удаление уже скрытого баном комментария (его видно
        // модератору в общем списке) не должно вычитать его повторно — пересчёт при скрытии
        // уже убрал его вместе с поддеревом, и вычитание задвоилось бы.
        final boolean wasCountedInAncestors = comment.getStatus() == CommentStatus.PUBLISHED;

        // Из счётчиков предков уходит сам комментарий и всё его достижимое поддерево.
        final int removedFromTotals = 1 + safeTotalReplyCount(comment);

        comment.markAsDeleted();
        commentRepository.save(comment);

        if (!wasCountedInAncestors)
        {
            return;
        }

        if (comment.getParentId() != null)
        {
            commentRepository.addToReplyCount(comment.getParentId(), -1);
        }

        adjustAncestorTotals(comment.getParentId(), -removedFromTotals);
    }

    /**
     * Возвращает удалённый комментарий в выдачу (DELETED → PUBLISHED). Доступно только модератору
     * и администратору: обычный пользователь удаление не отменяет.
     * <p>
     * Восстанавливается ровно один узел. Ответы под ним отдельного возврата не требуют — они
     * остались PUBLISHED, а невидимыми их делал обход дерева от корня, поэтому возврат узла
     * поднимает и всю ветку под ним.
     * <p>
     * Статус, отличный от DELETED, — конфликт (409), а не «уже восстановлен»: у HIDDEN_BY_BAN
     * причина скрытия другая (бан автора), и снимать её должна разблокировка в auth-service,
     * иначе следующая же синхронизация вернула бы комментарий обратно в скрытые. REJECTED и SPAM
     * — решения модерации, которые не следует отменять ручкой «восстановить удалённое».
     * <p>
     * Счётчики восстанавливаются полным пересчётом, как при разбане, а не дельтой: сохранённый
     * в удалённой строке {@code totalReplyCount} успел устареть (пока узел был удалён, ответы
     * под ним могли удалять — обход предков останавливается на первом удалённом, и до этой
     * строки не доходил), поэтому симметричное сложение задвоило бы или недосчитало. Пересчёт
     * с нуля идемпотентен и заодно чинит уже накопленное расхождение. Он дороже дельты, но
     * восстановление — редкая ручная операция модератора.
     * <p>
     * Два следствия, о которых стоит знать вызывающему. Во-первых, если родитель комментария
     * удалён, восстановленный ответ останется невидимым на странице: обход дерева идёт от корня
     * и обрывается на удалённом узле. Вернуть ветку целиком можно, восстановив её верхний
     * удалённый узел. Во-вторых, комментарий, удалённый до бана автора, скрытие при бане не
     * затронуло (оно берёт только PUBLISHED), поэтому его восстановление сделает комментарий
     * заблокированного автора видимым — состояния банов сервис у себя не хранит и проверить
     * его не может.
     */
    @Transactional
    public CommentResponse restoreComment(
            final UUID commentId,
            final boolean canModerate
    )
    {
        if (!canModerate)
        {
            throw new CommentAccessDeniedException("Only moderator or administrator can restore comments");
        }

        final Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found: " + commentId));

        if (comment.getStatus() != CommentStatus.DELETED)
        {
            throw new CommentStateException("Comment is not deleted, current status: " + comment.getStatus());
        }

        comment.markAsRestored();
        commentRepository.save(comment);

        recalculateReplyCounters();

        // Пересчёт очищает контекст персистентности (clearAutomatically), поэтому сущность выше
        // отсоединена и хранит счётчики до пересчёта. Перечитываем, чтобы отдать актуальные.
        final Comment restored = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found: " + commentId));

        return buildResponse(restored);
    }

    /**
     * Скрывает все опубликованные комментарии автора — вызывается auth-service при блокировке
     * пользователя. Меняется только статус, поэтому разблокировка возвращает комментарии как есть.
     * От {@link #deleteComment} отличается именно обратимостью: удаление пользователь делает сам
     * и разбан его отменять не должен.
     *
     * @return сколько комментариев скрыто; 0, если скрывать было нечего
     */
    @Transactional
    public int hideCommentsByAuthor(final UUID authorId)
    {
        return applyAuthorVisibilityChange(commentRepository.hidePublishedByAuthor(authorId));
    }

    /**
     * Возвращает в выдачу комментарии, скрытые баном автора, — вызывается auth-service при
     * разблокировке. Удалённые самим пользователем не воскресают: условие по исходному статусу
     * в запросе отбирает только HIDDEN_BY_BAN.
     *
     * @return сколько комментариев восстановлено; 0, если восстанавливать было нечего
     */
    @Transactional
    public int restoreCommentsByAuthor(final UUID authorId)
    {
        return applyAuthorVisibilityChange(commentRepository.restoreHiddenByBanByAuthor(authorId));
    }

    /**
     * Общий хвост скрытия и восстановления: пересчитать счётчики ответов, если видимость
     * действительно поменялась.
     * <p>
     * Дельты здесь неприменимы — у забаненного автора комментарии могут быть вложены друг
     * в друга, и вычитание {@code 1 + totalReplyCount} по каждому задвоилось бы. Пересчёт
     * с нуля даёт один и тот же результат независимо от формы дерева и от числа вызовов.
     * <p>
     * При {@code affected == 0} пересчёт пропускается: ничего не изменилось, а значит и
     * счётчики прежние. Это делает повторный вызов (идемпотентный по построению запроса)
     * ещё и дешёвым — без обхода всего дерева комментариев.
     */
    private int applyAuthorVisibilityChange(final int affected)
    {
        if (affected == 0)
        {
            return 0;
        }

        recalculateReplyCounters();

        return affected;
    }

    /**
     * Приводит {@code replyCount} и {@code totalReplyCount} к состоянию дерева. Оба пересчёта
     * идут парой и всегда в этом порядке — по отдельности они оставили бы счётчики
     * рассогласованными между собой.
     */
    private void recalculateReplyCounters()
    {
        commentRepository.recalculateReplyCounts();
        commentRepository.recalculateTotalReplyCounts();
    }

    /**
     * Меняет {@code totalReplyCount} у всех предков, начиная с {@code startParentId} и выше,
     * на {@code delta}. Обход прекращается на первом удалённом предке: поддерево под ним уже
     * исключено из счётчиков вышестоящих узлов (при его удалении), поэтому идти выше нельзя —
     * иначе изменение задвоится. Это же покрывает ответы на осиротевший (но опубликованный)
     * комментарий под удалённым родителем.
     */
    private void adjustAncestorTotals(
            final UUID startParentId,
            final int delta
    )
    {
        UUID currentId = startParentId;

        while (currentId != null)
        {
            final Comment ancestor = commentRepository.findById(currentId).orElse(null);
            if (ancestor == null || ancestor.isDeleted())
            {
                return;
            }

            commentRepository.addToTotalReplyCount(ancestor.getId(), delta);

            currentId = ancestor.getParentId();
        }
    }

    private int safeTotalReplyCount(final Comment comment)
    {
        return comment.getTotalReplyCount() == null ? 0 : comment.getTotalReplyCount();
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

    /**
     * Модератор и администратор ({@code canModerate == true}) правят/удаляют любой комментарий;
     * обычный пользователь — только свой, иначе 403.
     */
    private void validateCommentAccess(
            final Comment comment,
            final UUID userId,
            final boolean canModerate
    )
    {
        if (canModerate)
        {
            return;
        }

        if (!comment.getAuthorId().equals(userId))
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