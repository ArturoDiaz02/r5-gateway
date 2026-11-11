package com.soat.gateway.application.port;

public interface ProviderEventRepository {

    boolean registerIfNew(String eventId, String reference);
}
