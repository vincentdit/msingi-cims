package tz.co.msingi.cims.common.web;

import org.springframework.web.client.RestClient;

/**
 * RestClient for the outside systems (NACTVET, NECTA, NHIF, GePG, aggregators, SMS). Since Spring Boot 4 the whole application reads
 * and writes JSON with Jackson 3 (tools.jackson), so the defaults of Spring Framework 7 are used as they are; this one place stays so
 * the clients keep a single point to change should an outside system ever need a different converter.
 */
public final class Json2Http {

    private Json2Http() {
    }

    public static RestClient.Builder builder() {
        return RestClient.builder();
    }
}
