package club.ttg.comment.dto.response;

import club.ttg.comment.model.CommentStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class CommentResponse
{
    private UUID id;

    private String section;
    private String url;

    private UUID parentId;

    private UUID authorId;
    private String authorName;

    private String content;

    private CommentStatus status;

    private Integer replyCount;

    private OffsetDateTime createdAt;
    private OffsetDateTime editedAt;
}