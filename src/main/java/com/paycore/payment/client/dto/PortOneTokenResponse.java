package com.paycore.payment.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PortOneTokenResponse {

    private int code;
    private String message;
    private TokenData response;

    @Getter
    @NoArgsConstructor
    public static class TokenData {
        @JsonProperty("access_token")
        private String accessToken;
    }

    public String getAccessToken() {
        return response != null ? response.getAccessToken() : null;
    }

    public boolean isSuccess() {
        return code == 0;
    }
}
