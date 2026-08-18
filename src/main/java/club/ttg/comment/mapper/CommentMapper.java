package club.ttg.comment.mapper;

import club.ttg.comment.dto.request.CreateCommentRequest;
import club.ttg.comment.dto.response.CommentReplyPreview;
import club.ttg.comment.dto.response.CommentResponse;
import club.ttg.comment.dto.response.MyCommentResponse;
import club.ttg.comment.model.Comment;
import club.ttg.comment.model.SourcePlatform;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.UUID;

@Mapper(componentModel = "spring",
        imports = club.ttg.comment.model.CommentStatus.class)
public interface CommentMapper
{
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "parentId", ignore = true)
    @Mapping(target = "authorNameSnapshot", source = "authorName")
    @Mapping(target = "status", expression = "java(CommentStatus.PUBLISHED)")
    @Mapping(target = "replyCount", constant = "0")
    @Mapping(target = "totalReplyCount", constant = "0")
    @Mapping(target = "dislikeCount", constant = "0")
    @Mapping(target = "editedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Comment toEntity(
            CreateCommentRequest request,
            UUID authorId,
            String authorName
    );

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "authorNameSnapshot", source = "authorName")
    @Mapping(target = "status", expression = "java(CommentStatus.PUBLISHED)")
    @Mapping(target = "replyCount", constant = "0")
    @Mapping(target = "totalReplyCount", constant = "0")
    @Mapping(target = "dislikeCount", constant = "0")
    @Mapping(target = "editedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Comment toReply(
            SourcePlatform sourcePlatform,
            String section,
            String url,
            UUID parentId,
            UUID authorId,
            String authorName,
            String content
    );

    @Mapping(target = "authorName", source = "authorNameSnapshot")
    @Mapping(target = "parentAuthorName", ignore = true)
    CommentResponse toResponse(Comment comment);

    List<CommentResponse> toResponseList(List<Comment> comments);

    /**
     * Комментарий пользователя для его профиля. Всё, что связано с ответами
     * ({@code replyCount}, {@code newReplyCount}, {@code lastReplyAt}, {@code lastReply}),
     * и имя автора родителя считаются пачкой на страницу и проставляются сервисом — счётчик
     * сущности здесь не годится: в нём и свои ответы тоже.
     */
    @Mapping(target = "parentAuthorName", ignore = true)
    @Mapping(target = "replyCount", ignore = true)
    @Mapping(target = "newReplyCount", ignore = true)
    @Mapping(target = "lastReplyAt", ignore = true)
    @Mapping(target = "lastReply", ignore = true)
    MyCommentResponse toMyResponse(Comment comment);

    @Mapping(target = "authorName", source = "authorNameSnapshot")
    CommentReplyPreview toReplyPreview(Comment reply);
}
