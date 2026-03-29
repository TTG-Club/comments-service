package club.ttg.comment.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateCommentRequest
{
    private String section;
    private String url;
    private String content;
}