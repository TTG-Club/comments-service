package club.ttg.comment.mapper;

import club.ttg.comment.dto.request.CreateCommentRequest;
import club.ttg.comment.dto.response.CommentResponse;
import club.ttg.comment.model.Comment;
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
    @Mapping(target = "editedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Comment toReply(
            String section,
            String url,
            UUID parentId,
            UUID authorId,
            String authorName,
            String content
    );

    @Mapping(target = "authorName", source = "authorNameSnapshot")
    CommentResponse toResponse(Comment comment);

    List<CommentResponse> toResponseList(List<Comment> comments);
}