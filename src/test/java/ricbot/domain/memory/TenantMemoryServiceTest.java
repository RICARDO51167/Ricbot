package ricbot.domain.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ricbot.infra.persistence.FileSharedStateStore;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TenantMemoryServiceTest {
    @Test
    void isolatesTenantsAndPromotesMemoryLayers(@TempDir Path workspace) {
        TenantMemoryService service = new TenantMemoryService(new FileSharedStateStore(workspace));
        MemoryEntry working = new MemoryEntry()
                .setSummary("Ricbot checkpoint architecture")
                .setDetails("durable event replay and recovery")
                .setMemoryType(MemoryType.WORKING)
                .setImportance(0.8)
                .setConfidence(0.9);
        service.upsert("tenant-a", working);
        service.upsert("tenant-b", new MemoryEntry().setSummary("unrelated tenant preference")
                .setMemoryType(MemoryType.SEMANTIC).setScope(MemoryEntry.SCOPE_LONG_TERM));

        assertEquals(1, service.entries("tenant-a").size());
        assertEquals(1, service.entries("tenant-b").size());
        assertEquals("Ricbot checkpoint architecture",
                service.recall("tenant-a", "checkpoint recovery", "", 1).get(0).entry().getSummary());

        TenantMemoryService.PromotionResult promotion = service.promoteEligible("tenant-a");
        assertEquals(1, promotion.promotedToEpisodic());
        assertEquals(MemoryType.EPISODIC, service.entries("tenant-a").get(0).getMemoryType());
        assertEquals(1, promotion.snapshot().total());
    }
}
