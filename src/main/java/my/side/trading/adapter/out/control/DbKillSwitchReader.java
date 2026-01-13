package my.side.trading.adapter.out.control;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import my.side.trading.adapter.out.persistence.jpa.repository.TradingControlJpaRepository;
import my.side.trading.core.domain.guard.KillSwitchReader;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class DbKillSwitchReader implements KillSwitchReader {

    private static final String KEY = "KILL_SWITCH";

    private final TradingControlJpaRepository repo;

    private final Cache<String, Boolean> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(5))
            .maximumSize(100)
            .build();

    @Override
    public boolean isKillSwitchOn() {
        Boolean cached = cache.getIfPresent(KEY);
        if (cached != null) return cached;

        boolean on = repo.findById(KEY)
                .map(e -> "ON".equalsIgnoreCase(e.getValue()))
                .orElse(false);

        cache.put(KEY, on);
        return on;
    }
}
