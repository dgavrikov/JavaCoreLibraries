package io.github.dgavrikov.core.logging.service;

import io.github.dgavrikov.core.masking.MaskingMarker;
import io.github.dgavrikov.core.properties.MdcLoggingProperties;
import io.github.dgavrikov.core.utils.Constants;
import io.github.dgavrikov.core.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.zalando.logbook.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class CoreLogbookSink implements Sink {
    private final MdcLoggingProperties loggingProperties;

    @Override
    public void write(@NonNull Precorrelation precorrelation, HttpRequest request) throws IOException {
        boolean isServer = request.getOrigin() == Origin.REMOTE;
        String prefix = isServer ? ">>" : ">>>";
        var path = request.getPath();
        var headers = request.getHeaders();
        if (isServer) {
            log.info(MaskingMarker.MASKING_MARKER,
                    "Income request:\n>> Method:{}\n>> URI: {}\n>> Service: {}\n>> Host: {}\n",
                    request.getMethod(),
                    request.getRequestUri(),
                    headers.getFirst(Constants.X_INITIATOR_SERVICE),
                    headers.getFirst(Constants.X_INITIATOR_HOST));

            log.debug(MaskingMarker.MASKING_HEADER, "\n{} Headers: {}\n", prefix, headers(headers));
            writeBody(request.getBodyAsString(), loggingProperties, path, prefix);
        } else {
            var serviceTo = headers.getFirst(Constants.SERVICE_TO);
            var methodName = headers.getFirst(Constants.METHOD_NAME);
            var mdmId = headers.getFirst(Constants.X_MDM_ID);
            log.info(MaskingMarker.MASKING_MARKER,
                    "Request to an external system:\n>>> Method: {}\n>>> URI: {}\n>>> MethodName: {}\n>>> Service:{}\n{}\n",
                    request.getMethod(),
                    request.getRequestUri(),
                    methodName,
                    serviceTo,
                    mdmId != null ? ">>>MdmId: " + mdmId : "");
        }
    }

    @Override
    public void write(@NonNull Correlation correlation, @NonNull HttpRequest request, @NonNull HttpResponse response) throws IOException {
        boolean isServer = response.getOrigin() == Origin.LOCAL;
        var prefix = isServer ? "<<" : "<<<";
        var headersRequest = request.getHeaders();
        var headersResponse = response.getHeaders();
        var path = request.getPath();

        if (isServer) {
            writeBody(response.getBodyAsString(), loggingProperties, path, prefix);
            log.debug(MaskingMarker.MASKING_MARKER, "\n{} Headers: {}\n", prefix, headers(headersResponse));
            log.info(MaskingMarker.MASKING_MARKER,
                    "Response to incoming request:\n<< URI: {}\n<< Status: {}\n<< Service: {}\n<< Host: {}\n",
                    request.getRequestUri(),
                    response.getStatus(),
                    headersRequest.getFirst(Constants.X_INITIATOR_SERVICE),
                    headersRequest.getFirst(Constants.X_INITIATOR_HOST));
        } else {
            var serviceTo = headersRequest.getFirst(Constants.SERVICE_TO);
            var methodName = headersRequest.getFirst(Constants.METHOD_NAME);
            var mdmId = headersRequest.getFirst(Constants.X_MDM_ID);
            log.info(MaskingMarker.MASKING_MARKER,
                    "Response from external system:\n<<< URI: {}\n<<< Status: {}\n<<< MethodName: {}\n<<< Service: {}\n{}\n",
                    request.getRequestUri(),
                    response.getStatus(),
                    methodName,
                    serviceTo,
                    mdmId != null ? "<<< MdmId: " + mdmId : "");

            writeBody(response.getBodyAsString(), loggingProperties, path, prefix);
        }
    }

    private void writeBody(String body, MdcLoggingProperties loggingProperties, String path, String prefix) {
        if (log.isTraceEnabled()) {
            var responseBody = ObjectUtils.isLogBody(loggingProperties, path) ? body : "skip log body";
            log.trace(MaskingMarker.MASKING_JSON_ALL_MARKER, "\n{} Raw Body: {}", prefix, responseBody);
        }
    }

    private Map<String, List<String>> headers(HttpHeaders headers) {
        Map<String, List<String>> lowerCaseHeaders = new LinkedHashMap<>(headers.size());
        headers.forEach((key, val) -> lowerCaseHeaders.put(key.toLowerCase(), val));
        return lowerCaseHeaders;
    }
}
