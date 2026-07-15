package club.ttg.comment.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig
{
    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI commentsOpenAPI()
    {
        return new OpenAPI()
                .info(new Info()
                        .title("Comments API")
                        .description("""
                                REST API сервиса комментариев для веб-страниц.
                                Поддерживает древовидные комментарии (корневые и ответы),
                                привязку к разделу (`section`) и URL страницы,
                                аутентификацию по JWT (OAuth2 Resource Server).""")
                        .version("v1")
                        .contact(new Contact()
                                .name("TTG Club")
                                .url("https://ttg.club")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT-токен доступа. Формат: `Bearer <token>`")));
    }
}
