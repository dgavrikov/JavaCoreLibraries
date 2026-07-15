package com.github.dgavrikov.core.correlation.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.net.InetAddress;

@Slf4j
public class HostInfo {
    private final String localIp;
    private final String localHostname;

    @Value("${spring.application.name:unknown-service}")
    private String serviceName;

    public HostInfo() {
        String ip = "unknown";
        String hostname = "unknown";
        try {
            InetAddress addr = InetAddress.getLocalHost();
            ip = addr.getHostAddress();
            hostname = addr.getHostName();
        } catch (Exception e) {
            log.warn("[correlation] Failed to determine local host: {}", e.getLocalizedMessage());
        }
        this.localIp = ip;
        this.localHostname = hostname;
    }

    public String localIp() {
        return this.localIp;
    }

    public String localHostname() {
        return this.localHostname;
    }

    public String serviceName() {
        return this.serviceName;
    }
}
