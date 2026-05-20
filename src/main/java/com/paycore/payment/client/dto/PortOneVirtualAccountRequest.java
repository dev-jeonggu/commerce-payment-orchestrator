package com.paycore.payment.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

/**
 * PortOne 가상계좌 발급 요청
 * POST /vbanks
 *
 * [참고] PortOne V1 API에서 가상계좌는 pg 파라미터와 pay_method=vbank 조합으로 처리.
 * 실제 구현 시 PG사별 연동 매뉴얼 참조 필요.
 */
@Getter
@Builder
public class PortOneVirtualAccountRequest {

    @JsonProperty("merchant_uid")
    private String merchantUid;

    private Long amount;

    private String name;

    @JsonProperty("vbank_code")
    private String vbankCode;

    @JsonProperty("vbank_due")
    private Long vbankDue;  // Unix timestamp (입금 기한)

    @JsonProperty("vbank_holder")
    private String vbankHolder;
}
