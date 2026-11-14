package club.ttg.comment.config;

import club.ttg.comment.client.CoreApiUrlHistoryClient;
import club.ttg.comment.service.UrlHistorySyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Периодический перенос обсуждений на новые адреса страниц ({@link UrlHistorySyncService}).
 * Без адреса core-api или межсервисного секрета синхронизация не запускается — об этом одно
 * предупреждение при старте, а не ошибка каждые пять минут.
 * <p>
 * Сбой прогона (core-api недоступен, отказал в доступе) только логируется: следующий прогон
 * продолжит с того же курсора, ничего не потеряв.
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties(CoreApiProperties.class)
@RequiredArgsConstructor
public class UrlHistorySyncConfig
{
    private final CoreApiUrlHistoryClient client;
    private final UrlHistorySyncService syncService;

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfNotConfigured()
    {
        if (!client.isConfigured())
        {
            log.warn("URL history sync is disabled: set CORE_API_URL and INTERNAL_SERVICE_SECRET "
                    + "to move comments after catalog URL changes");
        }
    }

    @Scheduled(
            initialDelayString = "${core-api.url-history.initial-delay:30s}",
            fixedDelayString = "${core-api.url-history.sync-interval:5m}"
    )
    public void syncUrlHistory()
    {
        if (!client.isConfigured())
        {
            return;
        }

        try
        {
            syncService.sync();
        }
        catch (final RuntimeException e)
        {
            log.warn("URL history sync failed, will retry on the next run: {}", e.toString());
        }
    }
}
