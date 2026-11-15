package io.github.dgavrikov.core.logging.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpMessage;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.zalando.logbook.HttpRequest;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.zalando.logbook.core.Conditions.exclude;

@Slf4j
public class PathExclusion {
    private static final AntPathMatcher antPathMatcher = new AntPathMatcher();
    private final List<String> excludePaths;

    public PathExclusion() {
        this.excludePaths = Collections.emptyList();
    }

    public PathExclusion(List<String> paths) {
        this.excludePaths = new CopyOnWriteArrayList<>(paths);
    }

    public boolean isExcluded() {
        return isExcluded(excludePaths, null);
    }

    public boolean isExcluded(HttpMessage httpMessage) {
        return isExcluded(excludePaths, httpMessage);
    }

    public boolean isExcludedPatterns(List<String> patterns) {
        return isExcluded(patterns, null);
    }

    public Predicate<HttpRequest> excludeCondition() {
        if (excludePaths == null || excludePaths.isEmpty())
            return httpRequest -> true;

        var antMatcher = new AntPathMatcher();
        return exclude(httpRequest -> {
            var path = httpRequest.getPath();
            return excludePaths.stream()
                    .anyMatch(pattern -> antMatcher.match(pattern, path));
        });
    }

    public static boolean isExcluded(List<String> patterns, HttpMessage httpMessage) {
        try {
            if (CollectionUtils.isEmpty(patterns))
                return false;

            if (httpMessage != null) {
                var path = httpMessage.getHeaders().getFirst("X_Path");
                if (!StringUtils.isBlank(path))
                    return matchUrl(patterns, path);
            }

            var attributes = RequestContextHolder.getRequestAttributes();
            if (attributes == null)
                return false;

            if (attributes instanceof ServletRequestAttributes sra) {
                var request = sra.getRequest();
                return matchUrl(patterns, request.getRequestURI());
            }

            return false;
        } catch (Exception e) {
            log.warn(e.getLocalizedMessage(), e);
            return false;
        }
    }

    public static boolean isLogBody(List<String> paths, String path) {
        return !match(paths, path);
    }

    public static boolean matchUrl(List<String> paths, String path) {
        return match(paths, path);
    }

    public static boolean match(List<String> patterns, String path) {
        if (StringUtils.isBlank(path))
            return false;
        for (var pattern : patterns) {
            if (antPathMatcher.match(pattern, path))
                return true;
        }
        return false;
    }
}
