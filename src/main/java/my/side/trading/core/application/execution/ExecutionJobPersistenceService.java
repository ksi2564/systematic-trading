package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import my.side.trading.core.domain.execution.order.ExecutionJob;
import my.side.trading.core.domain.execution.order.ExecutionJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * ExecutionJob 영속성 전담 서비스
 * - 외부 I/O(주문/체결/취소)와 DB 트랜잭션을 분리하기 위해 도입
 * - 짧은 트랜잭션으로 DB 커넥션 점유를 최소화
 */
@Service
@RequiredArgsConstructor
public class ExecutionJobPersistenceService {

    private final ExecutionJobRepository jobRepository;

    /**
     * Job을 조회하고 시작 상태로 전환 후 저장
     */
    @Transactional
    public ExecutionJob startJob(Long jobId, LocalDateTime now) {
        ExecutionJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        job.start(now);
        return jobRepository.save(job);
    }

    /**
     * Job을 저장 (상태 변경 후 영속화 용도)
     */
    @Transactional
    public ExecutionJob saveJob(ExecutionJob job) {
        return jobRepository.save(job);
    }
}
