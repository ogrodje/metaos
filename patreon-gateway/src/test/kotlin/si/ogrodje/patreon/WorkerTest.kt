package si.ogrodje.patreon

import io.temporal.worker.WorkerFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertNotNull

/** The gateway works on MetaOS's workflows' activities, on task queue patreon, as application.properties says */
@SpringBootTest
@TestPropertySource(properties = ["spring.temporal.test-server.enabled=true", "patreon.state-dir=build/worker-test-state"])
class WorkerTest {
    @Autowired
    lateinit var workers: WorkerFactory

    @Test
    fun `it works on the patreon queue`() {
        assertNotNull(workers.tryGetWorker(PATREON_QUEUE))
    }
}
