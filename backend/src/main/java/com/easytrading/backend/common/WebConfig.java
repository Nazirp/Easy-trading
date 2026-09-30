package com.easytrading.backend.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Page routing for the HTML/JS frontend, which this application serves itself
 * (same origin as the API — see the note in backend/CONTRACTS.md on why).
 *
 * Frontend files live in src/main/resources/static/ and are served
 * automatically at their own filename:
 *   static/index.html          -> /              (Spring Boot maps index.html to root)
 *   static/demo-trading.html   -> /demo-trading.html
 *   static/js/app.js           -> /js/app.js
 *   static/css/style.css       -> /css/style.css
 *
 * The one mapping below exists so the demo trading page is reachable at the
 * agreed clean URL /demo-trading rather than /demo-trading.html. It forwards
 * rather than redirects, so the URL in the address bar stays clean.
 *
 * Everything under /api/** is JSON (see InstrumentController, PriceController).
 * Keeping pages and API on separate prefixes means adding a page route can
 * never shadow an endpoint.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/demo-trading").setViewName("forward:/demo-trading.html");
    }
}
