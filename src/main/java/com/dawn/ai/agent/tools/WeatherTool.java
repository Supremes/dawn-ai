package com.dawn.ai.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;

/**
 * Mock Weather Tool demonstrating Agent Function Calling.
 *
 * In production, replace with real weather API calls (OpenWeatherMap, etc.)
 * and add circuit breaker (Resilience4j) for fault tolerance.
 */
@Slf4j
@Component
@Description("Get current weather information for a city. Input: city name")
public class WeatherTool implements Function<WeatherTool.Request, WeatherTool.Response> {

    // Simulated weather data — replace with real API call in production
    private static final Map<String, WeatherInfo> MOCK_DATA = Map.of(
            "beijing",    new WeatherInfo("Beijing",    "Cloudy",  12, 65),
            "shanghai",   new WeatherInfo("Shanghai",   "Sunny",   18, 55),
            "shenzhen",   new WeatherInfo("Shenzhen",   "Rainy",   22, 80),
            "chengdu",    new WeatherInfo("Chengdu",    "Overcast", 15, 70)
    );

    public record Request(String city) {}
    public record Response(String city, String condition, int temperatureCelsius, int humidity, String message) {}
    private record WeatherInfo(String name, String condition, int temp, int humidity) {}

    @Override
    public Response apply(Request request) {
        log.info("[WeatherTool] Querying weather for city: {}", request.city());
        String key = request.city().toLowerCase().trim();
        WeatherInfo info = MOCK_DATA.getOrDefault(key,
                new WeatherInfo(request.city(), "Unknown", 0, 0));

        return new Response(
                info.name(),
                info.condition(),
                info.temp(),
                info.humidity(),
                String.format("Weather in %s: %s, %d°C, humidity %d%%",
                        info.name(), info.condition(), info.temp(), info.humidity())
        );
    }
}
