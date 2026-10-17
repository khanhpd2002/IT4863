package com.hust.it4863.searchapi.extract;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Tai noi dung 1 URL bang java.net.http.HttpClient (co san trong JDK), tu theo redirect. */
@Component
public class HttpFetcher {

    /**
     * @param finalUrl    URL sau khi theo redirect
     * @param body        noi dung; null neu vuot maxBytes (tooLarge = true)
     */
    public record FetchResult(String finalUrl, int httpStatus, String contentType, byte[] body, boolean tooLarge) {
    }

    private final ExtractProperties props;
    private final HttpClient client;

    public HttpFetcher(ExtractProperties props) {
        this.props = props;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(props.timeoutSeconds()))
                .build();
    }

    public FetchResult fetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .header("User-Agent", props.userAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/pdf;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "vi,en;q=0.8")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String contentType = response.headers().firstValue("Content-Type").orElse(null);
        String finalUrl = response.uri().toString();

        // Doc toi da maxBytes + 1 byte: neu doc duoc nhieu hon maxBytes thi la tep qua lon -
        // dung lai ngay, khong tai het ve bo nho.
        try (InputStream in = response.body()) {
            byte[] body = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, props.maxBytes() + 1));
            if (body.length > props.maxBytes()) {
                return new FetchResult(finalUrl, response.statusCode(), contentType, null, true);
            }
            return new FetchResult(finalUrl, response.statusCode(), contentType, body, false);
        }
    }
}
