package club.ttg.comment.repository;

import club.ttg.comment.model.Comment;
import club.ttg.comment.model.CommentComplaint;
import club.ttg.comment.model.CommentRateLimit;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Компилирует все JPQL-запросы {@link CommentRepository} настоящим парсером Hibernate.
 * <p>
 * Ошибка в строке {@code @Query} не видна ни компилятору Java, ни юнит-тестам с замоканным
 * репозиторием — она роняет приложение при старте, когда Spring Data создаёт репозиторий.
 * Тест ловит это без БД: SessionFactory поднимается с явным диалектом и без доступа к JDBC,
 * поэтому Docker и PostgreSQL здесь не нужны (нативные запросы Hibernate не разбирает —
 * их корректность проверяет {@code CommentBanVisibilityIntegrationTest}).
 */
class CommentRepositoryQuerySyntaxTest
{
    private static SessionFactory sessionFactory;

    @BeforeAll
    static void bootstrapHibernate()
    {
        sessionFactory = new Configuration()
                .addAnnotatedClass(Comment.class)
                .addAnnotatedClass(CommentComplaint.class)
                .addAnnotatedClass(CommentRateLimit.class)
                .setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .setProperty("hibernate.boot.allow_jdbc_metadata_access", "false")
                .setProperty("hibernate.temp.use_jdbc_metadata_defaults", "false")
                .buildSessionFactory();
    }

    @AfterAll
    static void closeHibernate()
    {
        if (sessionFactory != null)
        {
            sessionFactory.close();
        }
    }

    @Test
    void everyJpqlQueryCompiles()
    {
        final List<Method> annotated = jpqlMethods();

        // Страховка от «тест зелёный, потому что ничего не проверял».
        assertThat(annotated).isNotEmpty();

        for (final Method method : annotated)
        {
            final String jpql = method.getAnnotation(Query.class).value();

            assertThatCode(() -> compile(jpql))
                    .as("JPQL в %s не компилируется: %s", method.getName(), jpql)
                    .doesNotThrowAnyException();
        }
    }

    /**
     * Отдельно и явно — про статус бана: имя константы попадает в запрос строкой, и переименование
     * {@code HIDDEN_BY_BAN} в enum сломает именно эти два запроса.
     */
    @Test
    void banVisibilityQueriesReferenceExistingEnumConstants() throws Exception
    {
        final String hideQuery = CommentRepository.class
                .getMethod("hidePublishedByAuthor", java.util.UUID.class)
                .getAnnotation(Query.class)
                .value();

        final String restoreQuery = CommentRepository.class
                .getMethod("restoreHiddenByBanByAuthor", java.util.UUID.class)
                .getAnnotation(Query.class)
                .value();

        assertThatCode(() -> compile(hideQuery)).doesNotThrowAnyException();
        assertThatCode(() -> compile(restoreQuery)).doesNotThrowAnyException();
    }

    private static void compile(final String jpql)
    {
        try (var session = sessionFactory.openSession())
        {
            if (isMutation(jpql))
            {
                session.createMutationQuery(jpql);
            }
            else
            {
                session.createSelectionQuery(jpql, Object.class);
            }
        }
    }

    private static boolean isMutation(final String jpql)
    {
        final String normalized = jpql.trim().toLowerCase();
        return normalized.startsWith("update") || normalized.startsWith("delete") || normalized.startsWith("insert");
    }

    private static List<Method> jpqlMethods()
    {
        final List<Method> methods = new ArrayList<>();

        for (final Method method : CommentRepository.class.getDeclaredMethods())
        {
            final Query query = method.getAnnotation(Query.class);

            if (query != null && !query.nativeQuery())
            {
                methods.add(method);
            }
        }

        return methods;
    }
}
