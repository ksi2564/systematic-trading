package my.side.trading.adapter.out.persistence.jpa.repository;

import my.side.trading.adapter.out.persistence.jpa.entity.TradingControlEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradingControlJpaRepository extends JpaRepository<TradingControlEntity, String> {
}
