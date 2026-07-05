package com.example.showcase;

import io.micrometer.observation.annotation.Observed;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A handful of endpoints that produce metrics, traces and logs so there is
 * something interesting to look at in Grafana.
 */
@RestController
public class GreetingController {

	private static final Logger log = LoggerFactory.getLogger(GreetingController.class);

	@GetMapping("/")
	public Map<String, Object> index() {
		log.info("index requested");
		return Map.of(
				"app", "grafana-showcase",
				"endpoints", new String[] {"/api/hello", "/api/work", "/api/flaky", "/api/error", "/api/boom"});
	}

	@GetMapping("/api/hello")
	public Map<String, String> hello(@RequestParam(defaultValue = "world") String name) {
		log.info("saying hello to {}", name);
		return Map.of("greeting", "Hello, " + name + "!");
	}

	/** Simulates variable back-end latency so response-time panels have a distribution. */
	@GetMapping("/api/work")
	@Observed(name = "app.work", contextualName = "do-work")
	public Map<String, Object> work() throws InterruptedException {
		long millis = ThreadLocalRandom.current().nextLong(20, 400);
		Thread.sleep(millis);
		log.info("did work for {}ms", millis);
		return Map.of("workedMillis", millis);
	}

	/** Fails roughly a third of the time with a 500 so the error-rate panels move. */
	@GetMapping("/api/flaky")
	public Map<String, String> flaky() {
		if (ThreadLocalRandom.current().nextInt(3) == 0) {
			// Logged (with traceId) and turned into a 500 by GlobalExceptionHandler.
			throw new IllegalStateException("Flaky failure — this happens ~1/3 of the time");
		}
		return Map.of("status", "ok");
	}

	/** Always returns a 4xx client error. */
	@GetMapping("/api/error")
	public void clientError() {
		throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Deliberate bad request");
	}

	/** Always throws -> 500 server error. */
	@GetMapping("/api/boom")
	public void boom() {
		throw new RuntimeException("Boom! Deliberate server error");
	}
}
