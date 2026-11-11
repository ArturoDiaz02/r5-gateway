package com.soat.gateway.architecture.fixtures.security;

public class SignatureVerifier {

    public boolean matches(String expected, String received) {
        return expected.equals(received);
    }
}
