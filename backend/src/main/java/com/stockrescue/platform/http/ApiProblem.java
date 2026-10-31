package com.stockrescue.platform.http;

import java.net.URI;

import io.swagger.v3.oas.annotations.media.Schema;

public record ApiProblem(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) URI type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String detail,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ProblemCode code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String correlationId) { }
