package io.github.dgavrikov.core.uap.auth.model;

public class TokenStatus {
    private String jti;
    private Boolean status;

    public String getJti() { return this.jti; }
    public TokenStatus setJti(String jti) {
        this.jti = jti;
        return this;
    }

    public Boolean getStatus() { return this.status; }
    public TokenStatus setStatus(Boolean status) {
        this.status = status;
        return this;
    }
}
