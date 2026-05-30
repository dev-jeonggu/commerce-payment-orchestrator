package com.paycore.payment.method;

import com.paycore.common.exception.ErrorCode;
import com.paycore.common.exception.PaycoreException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PaymentMethodRouter {

    private final Map<PaymentMethod, PaymentMethodProcessor> processorMap;

    public PaymentMethodRouter(List<PaymentMethodProcessor> processors) {
        this.processorMap = processors.stream()
                .collect(Collectors.toMap(PaymentMethodProcessor::method, Function.identity()));
        log.info("[PaymentMethodRouter] 등록된 결제 수단: {}", processorMap.keySet());
    }

    public PaymentMethodProcessor route(PaymentMethod method) {
        PaymentMethodProcessor processor = processorMap.get(method);
        if (processor == null) {
            throw new PaycoreException(ErrorCode.PAYMENT_METHOD_NOT_SUPPORTED,
                    "지원하지 않는 결제 수단: " + method);
        }
        return processor;
    }
}
