package com.vms.workflow.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FinancePageCursorCodecTest {
    private static final String SECRET =
        "test-only-cursor-signing-secret-0123456789abcdef";
    private static final UUID ENGAGEMENT =
        UUID.fromString("00000000-0000-0000-0000-000000000401");

    @Test
    void cursorIsSignedAndBoundToActorResourceAndScope() {
        Instant now = Instant.parse("2026-07-27T12:00:00.123456Z");
        FinancePageCursorCodec codec = new FinancePageCursorCodec(
            SECRET, Duration.ofMinutes(30),
            Clock.fixed(now, ZoneOffset.UTC));
        UUID lastId = UUID.randomUUID();
        String encoded = codec.encode(
            "control-tower", "user-procurement",
            List.of(ENGAGEMENT), now, "2026-06-01", lastId);

        FinancePageCursorCodec.Cursor decoded = codec.decode(
            encoded, "control-tower", "user-procurement",
            List.of(ENGAGEMENT));
        assertEquals(now, decoded.snapshotAt());
        assertEquals("2026-06-01", decoded.lastSortValue());
        assertEquals(lastId, decoded.lastId());

        assertThrows(IllegalArgumentException.class, () -> codec.decode(
            encoded, "report-exports", "user-procurement",
            List.of(ENGAGEMENT)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
            encoded, "control-tower", "another-user",
            List.of(ENGAGEMENT)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
            encoded, "control-tower", "user-procurement",
            List.of(UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
            encoded.substring(0, encoded.length() - 1) + "x",
            "control-tower", "user-procurement", List.of(ENGAGEMENT)));
    }

    @Test
    void expiredSnapshotCursorIsRejectedAsStale() {
        Instant issued = Instant.parse("2026-07-27T12:00:00Z");
        FinancePageCursorCodec issuer = new FinancePageCursorCodec(
            SECRET, Duration.ofMinutes(30),
            Clock.fixed(issued, ZoneOffset.UTC));
        String encoded = issuer.encode(
            "control-tower", "user-procurement",
            List.of(ENGAGEMENT), issued, "2026-06-01",
            UUID.randomUUID());
        FinancePageCursorCodec reader = new FinancePageCursorCodec(
            SECRET, Duration.ofMinutes(30),
            Clock.fixed(issued.plus(Duration.ofMinutes(31)),
                ZoneOffset.UTC));

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> reader.decode(
                encoded, "control-tower", "user-procurement",
                List.of(ENGAGEMENT)));
        assertEquals("The pagination cursor is stale.", error.getMessage());
    }

    @Test
    void everyFinanceListRouteRejectsTamperActorRouteAndScopeReuse() {
        Instant now = Instant.parse("2026-07-27T12:00:00Z");
        FinancePageCursorCodec codec = new FinancePageCursorCodec(
            SECRET, Duration.ofMinutes(30),
            Clock.fixed(now, ZoneOffset.UTC));
        UUID monthId =
            UUID.fromString("00000000-0000-0000-0000-000000000602");
        UUID packageId =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
        List<String> routes = List.of(
            "finance-months",
            "finance-invoices:all",
            "finance-invoices:" + monthId,
            "package-history:" + monthId,
            "package-access-events:" + packageId,
            "package-shares:" + packageId);

        for (int index = 0; index < routes.size(); index++) {
            String route = routes.get(index);
            String encoded = codec.encode(
                route, "user-procurement", List.of(ENGAGEMENT), now,
                "2026-07-27T12:00:00Z", UUID.randomUUID());
            assertEquals(now, codec.decode(
                encoded, route, "user-procurement",
                List.of(ENGAGEMENT)).snapshotAt());
            String anotherRoute = routes.get((index + 1) % routes.size());
            assertThrows(IllegalArgumentException.class, () -> codec.decode(
                encoded, anotherRoute, "user-procurement",
                List.of(ENGAGEMENT)));
            assertThrows(IllegalArgumentException.class, () -> codec.decode(
                encoded, route, "another-user", List.of(ENGAGEMENT)));
            assertThrows(IllegalArgumentException.class, () -> codec.decode(
                encoded, route, "user-procurement",
                List.of(UUID.randomUUID())));
            String tampered = (encoded.charAt(0) == 'A' ? "B" : "A")
                + encoded.substring(1);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(
                tampered, route, "user-procurement", List.of(ENGAGEMENT)));
            FinancePageCursorCodec expiredReader = new FinancePageCursorCodec(
                SECRET, Duration.ofMinutes(30),
                Clock.fixed(now.plus(Duration.ofMinutes(31)),
                    ZoneOffset.UTC));
            assertThrows(IllegalArgumentException.class,
                () -> expiredReader.decode(
                    encoded, route, "user-procurement",
                    List.of(ENGAGEMENT)));
        }
    }
}
