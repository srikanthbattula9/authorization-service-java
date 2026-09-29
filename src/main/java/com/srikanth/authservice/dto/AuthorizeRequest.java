package com.srikanth.authservice.dto;

public record AuthorizeRequest(
        String cardToken,
        long amountMinor,
        String merchantId,
        String mcc
) {}
