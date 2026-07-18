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

    @Transactional(readOnly = true)
    public Page<CommentResponse> getAllComments(final Pageable pageable)
    {
        final Pageable sortedByNewest = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        return commentRepository.findAll(sortedByNewest).map(this::buildResponse);
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

        return buildResponse(commentRepository.save(comment));
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

        // Из счётчиков предков уходит сам комментарий и всё его достижимое поддерево.
        final int removedFromTotals = 1 + safeTotalReplyCount(comment);

        comment.markAsDeleted();
        commentRepository.save(comment);

        if (comment.getParentId() != null)
        {
            commentRepository.addToReplyCount(comment.getParentId(), -1);
        }

        adjustAncestorTotals(comment.getParentId(), -removedFromTotals);
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