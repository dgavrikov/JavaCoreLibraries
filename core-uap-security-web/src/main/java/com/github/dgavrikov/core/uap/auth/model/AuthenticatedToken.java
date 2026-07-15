package com.github.dgavrikov.core.uap.auth.model;

import java.util.Objects;

@SuppressWarnings("unchecked")
public class AuthenticatedToken {
    private String subject;
    private String principal;
    private Object details;
    private String channel;

    public AuthenticatedToken(String subject, String principal) {
        this.subject = subject;
        this.principal = principal;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getPrincipal() {
        return principal;
    }

    public void setPrincipal(String principal) {
        this.principal = principal;
    }

    public <T> T getDetails() {
        return (T) details;
    }

    public void setDetails(Object details) {
        this.details = details;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;

        var that = (AuthenticatedToken) obj;
        return Objects.equals(subject, that.subject)
                && Objects.equals(principal, that.principal)
                && Objects.equals(details, that.details)
                && Objects.equals(channel, that.channel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subject, principal, details, channel);
    }
}
