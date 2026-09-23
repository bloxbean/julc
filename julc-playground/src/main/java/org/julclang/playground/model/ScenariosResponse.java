package org.julclang.playground.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response of {@code GET /api/scenarios/{purpose}}. {@code message} is only present when no templates exist.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScenariosResponse(String purpose, List<ScenarioDto> scenarios, String message) {}
