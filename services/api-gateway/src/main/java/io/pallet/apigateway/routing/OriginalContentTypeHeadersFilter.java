package io.pallet.apigateway.routing;

import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

@Component
class OriginalContentTypeHeadersFilter implements HttpHeadersFilter.RequestHttpHeadersFilter {

    @Override
    public HttpHeaders apply(HttpHeaders input, ServerRequest request) {
        String original = request.servletRequest().getHeader(HttpHeaders.CONTENT_TYPE);
        if (original == null || original.equals(input.getFirst(HttpHeaders.CONTENT_TYPE))) {
            return input;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(input);
        headers.set(HttpHeaders.CONTENT_TYPE, original);
        return headers;
    }
}
