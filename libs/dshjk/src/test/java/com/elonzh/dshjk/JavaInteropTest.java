package com.elonzh.dshjk;

import com.elonzh.dshjk.client.DeepSeekHarness;
import com.elonzh.dshjk.configuration.HarnessConfig;
import com.elonzh.dshjk.configuration.RuntimeCommand;
import com.elonzh.dshjk.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
public class JavaInteropTest {
    @TempDir Path root;
    @Test void blockingCallsAndJdkPublisherUseTheSameClient() throws Exception {
        var config = HarnessConfig.builder(root.resolve("home")).cwd(root)
            .runtime(RuntimeCommand.node(Path.of(getClass().getResource("/runtime.mjs").toURI())))
            .approvalHandler((request, cancellation) -> new ApprovalResult(ApprovalOutcome.REJECTED))
            .userQuestionHandler((request, cancellation) -> new UserQuestionResult.Answer(
                List.of(new AnswerItem("q", List.of("yes")))))
            .build();
        try (var harness = new DeepSeekHarness(config)) {
            harness.start();
            var received = new CountDownLatch(1);
            try (var subscription = harness.getClient().subscribe("java")) {
                subscription.events().subscribe(new Flow.Subscriber<HarnessNotification>() {
                    Flow.Subscription upstream;
                    public void onSubscribe(Flow.Subscription s) { upstream = s; s.request(1); }
                    public void onNext(HarnessNotification n) { received.countDown(); upstream.cancel(); }
                    public void onError(Throwable error) { throw new AssertionError(error); }
                    public void onComplete() { }
                });
                assertEquals("java text", harness.run(List.of(new ContentBlock.Text("java text")), "java").getFinalResponse());
                assertTrue(received.await(2, TimeUnit.SECONDS));
            }
            assertEquals("rejected", harness.run("approval").getFinalResponse());
            assertEquals("yes", harness.run("question").getFinalResponse());
            assertEquals("low-level", harness.getClient().prompt("raw", HarnessProtocol.text("prompt")).getMessageId());
        }
    }
}
