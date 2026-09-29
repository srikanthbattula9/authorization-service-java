package com.srikanth.authservice.dto;

public record AuthorizeResponse(
        String authId,
        String status,
        String declineCode
) {}
