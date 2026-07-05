package com.example.showcase;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Handles exceptions <em>inside</em> the request's trace scope. This matters for two reasons:
 * <ul>
 *   <li>The error is logged while the MDC still holds the {@code traceId}, so the log line links
 *       to its trace instead of showing {@code traceId=none}.</li>
 *   <li>The exception no longer propagates out to Tomcat, so its redundant, trace-less
 *       "Servlet.service() ... threw exception" ERROR line disappears.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, Object>> handle(ResponseStatusException ex) {
		return build(ex.getStatusCode(), ex.getReason(), ex);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handle(Exception ex) {
		return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
	}

	private ResponseEntity<Map<String, Object>> build(HttpStatusCode status, String message, Exception ex) {
		Span span = Span.current();
		if (status.is5xxServerError()) {
			log.error("request failed [{}]: {}", status.value(), message, ex);
			// Flag the server span as errored so it stands out in Tempo (TraceQL: {status=error}).
			span.setStatus(StatusCode.ERROR, message == null ? "" : message);
			span.recordException(ex);
		} else {
			// 4xx is a client error — log it, but per OTel conventions don't mark the span as errored.
			log.warn("request failed [{}]: {}", status.value(), message);
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", status.value());
		body.put("error", message == null ? "error" : message);
		body.put("traceId", span.getSpanContext().getTraceId());
		return ResponseEntity.status(status).body(body);
	}
}
