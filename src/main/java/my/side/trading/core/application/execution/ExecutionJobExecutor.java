package my.side.trading.core.application.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import my.side.trading.adapter.out.kis.mapper.KisOverseasOrderRequestMapper;
import my.side.trading.core.domain.execution.order.*;
import my.side.trading.adapter.out.kis.client.KisOverseasOrderService;
import my.side.trading.adapter.out.kis.dto.OverseasOrderRequest;
import my.side.trading.adapter.out.kis.dto.OverseasOrderResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionJobExecutor {

    private final ExecutionJobRepository jobRepository;
    private final KisOverseasOrderService kisOrderService;
    private final KisOverseasOrderRequestMapper requestMapper;

    public ExecutionJob execute(Long jobId, LocalDateTime now) {
        ExecutionJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("execution job not found: " + jobId));

        if (job.getStatus() == ExecutionStatus.PENDING) {
            job.start(now);
        }
        if (job.getStatus() != ExecutionStatus.RUNNING) {
            throw new IllegalStateException("RUNNING 상태에서만 실행 가능: " + job.getStatus());
        }

        for (ExecutionOrder order : job.getOrders()) {
            if (order.isTerminal()) continue;

            // PLANNED -> REQUESTED
            if (order.getStatus() == ExecutionOrderStatus.PLANNED) {
                job.markOrderRequested(order.getId());
            }

            OverseasOrderRequest req = requestMapper.toRequest(order);

            try {
                OverseasOrderResponse resp = (order.getSide() == ExecutionOrderSide.BUY)
                        ? kisOrderService.placeUsBuyOrder(req)
                        : kisOrderService.placeUsSellOrder(req);

                if (isSuccess(resp)) {
                    String brokerOrderId = resp.output() != null ? resp.output().orderNo() : null;
                    String msg = buildSuccessMessage(resp);

                    // 성공인데 orderNo가 비어있으면 데이터 무결성상 실패
                    if (brokerOrderId == null || brokerOrderId.isBlank()) {
                        job.rejectOrder(order.getId(), null, "KIS success but missing orderNo. msg=" + msg, now);
                    } else {
                        job.acceptOrder(order.getId(), brokerOrderId, msg, now);
                    }
                } else {
                    String brokerOrderId = resp.output() != null ? resp.output().orderNo() : null;
                    String msg = buildFailureMessage(resp);
                    job.rejectOrder(order.getId(), brokerOrderId, msg, now);
                }

            } catch (WebClientResponseException e) {
                // HTTP 레벨 에러(4xx/5xx)
                String body = e.getResponseBodyAsString();
                job.rejectOrder(order.getId(), null, "HTTP " + e.getStatusCode() + " body=" + body, now);

            } catch (Exception e) {
                job.rejectOrder(order.getId(), null, "Exception: " + e.getMessage(), now);
            }
        }

        job.completeIfAllTerminal(now);
        return jobRepository.save(job);
    }

    private boolean isSuccess(OverseasOrderResponse resp) {
        return resp != null && "0".equals(resp.resultCode());
    }

    private String buildSuccessMessage(OverseasOrderResponse resp) {
        if (resp == null) return "null response";
        String msg = resp.message();
        OverseasOrderResponse.Output out = resp.output();
        if (out == null) return msg;

        return "msg=" + msg + ", orderTime=" + out.orderTime() + ", orgNo=" + out.orgNo();
    }

    private String buildFailureMessage(OverseasOrderResponse resp) {
        if (resp == null) return "null response";
        return "rt_cd=" + resp.resultCode()
                + ", msg_cd=" + resp.messageCode()
                + ", msg=" + resp.message();
    }
}
