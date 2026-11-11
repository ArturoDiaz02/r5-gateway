package com.soat.gateway.domain.emission;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.soat.gateway.domain.emission.EmissionStatus.EXPIRED;
import static com.soat.gateway.domain.emission.EmissionStatus.ISSUED;
import static com.soat.gateway.domain.emission.EmissionStatus.REJECTED;
import static com.soat.gateway.domain.emission.EmissionStatus.REQUESTED;
import static com.soat.gateway.domain.emission.EmissionStatus.REVIEW_REQUIRED;
import static com.soat.gateway.domain.emission.EmissionStatus.SEND_FAILED;
import static com.soat.gateway.domain.emission.EmissionStatus.SENT;

public final class TransitionMatrix {

    private static final Map<EmissionStatus, Set<EmissionStatus>> TARGETS = new EnumMap<>(Map.of(
            REQUESTED, EnumSet.of(SENT, SEND_FAILED, EXPIRED, ISSUED, REJECTED),
            SENT, EnumSet.of(REVIEW_REQUIRED, EXPIRED, ISSUED, REJECTED),
            REVIEW_REQUIRED, EnumSet.of(EXPIRED, ISSUED, REJECTED),
            SEND_FAILED, EnumSet.of(SENT, EXPIRED, ISSUED, REJECTED),
            EXPIRED, EnumSet.of(ISSUED, REJECTED),
            ISSUED, EnumSet.noneOf(EmissionStatus.class),
            REJECTED, EnumSet.noneOf(EmissionStatus.class)));

    private static final Map<EmissionStatus, Set<EmissionStatus>> ORIGINS = Arrays.stream(EmissionStatus.values())
            .collect(Collectors.toMap(
                    target -> target,
                    target -> Set.copyOf(Arrays.stream(EmissionStatus.values())
                            .filter(origin -> TARGETS.get(origin).contains(target))
                            .collect(Collectors.toSet()))));

    private TransitionMatrix() {
    }

    public static boolean allows(EmissionStatus from, EmissionStatus to) {
        return TARGETS.get(from).contains(to);
    }

    public static Set<EmissionStatus> allowedOrigins(EmissionStatus target) {
        return ORIGINS.get(target);
    }
}
