package exps.cariv.global.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void setAndGetCompanyIdWorksInCurrentThread() {
        TenantContext.setCompanyId(77L);

        assertThat(TenantContext.getCompanyId()).isEqualTo(77L);
    }

    @Test
    void clearRemovesCompanyId() {
        TenantContext.setCompanyId(77L);
        TenantContext.clear();

        assertThat(TenantContext.getCompanyId()).isNull();
    }

    @Test
    void threadLocalDoesNotLeakToAnotherThread() throws Exception {
        TenantContext.setCompanyId(77L);
        AtomicReference<Long> otherThreadValue = new AtomicReference<>(-1L);

        Thread worker = new Thread(() -> otherThreadValue.set(TenantContext.getCompanyId()));
        worker.start();
        worker.join();

        assertThat(otherThreadValue.get()).isNull();
        assertThat(TenantContext.getCompanyId()).isEqualTo(77L);
    }
}
