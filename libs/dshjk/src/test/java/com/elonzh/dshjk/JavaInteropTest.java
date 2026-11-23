package com.elonzh.dshjk;

import com.elonzh.dshjk.client.DeepSeekHarness;
import com.elonzh.dshjk.configuration.HarnessConfig;
import com.elonzh.dshjk.configuration.RuntimeCommand;
import com.elonzh.dshjk.error.RequestTimeoutException;
import com.elonzh.dshjk.office.HarnessOffice;
import com.elonzh.dshjk.office.OfficeConfig;
import com.elonzh.dshjk.office.OfficeException;
import com.elonzh.dshjk.office.OfficeOptions;
import com.elonzh.dshjk.office.OfficeRenderOptions;
import com.elonzh.dshjk.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
            .requestTimeout(Duration.ofSeconds(10))
            .approvalHandler((request, cancellation) -> new ApprovalResult(ApprovalOutcome.REJECTED))
            .userQuestionHandler((request, cancellation) -> new UserQuestionResult.Answer(
                List.of(new AnswerItem("q", List.of("yes")))))
            .build();
        try (var harness = new DeepSeekHarness(config)) {
            harness.start();
            var received = new CountDownLatch(1);
            try (var subscription = harness.getClient().subscribe("java")) {
                assertThrows(RequestTimeoutException.class, () -> subscription.next(Duration.ofMillis(20)));
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
    @Test void defaultConstructorsAndOptionalHomeBuilderWorkWithoutLaunching() {
        assertNull(new HarnessConfig().getDshHome());
        assertNull(HarnessConfig.builder().build().getDshHome());
        assertEquals(root, HarnessConfig.builder().dshHome(root).build().getDshHome());
        try (var harness = new DeepSeekHarness(); var client = new com.elonzh.dshjk.client.HarnessClient()) {
            assertNull(harness.getClient().getPid());
            assertTrue(harness.session().getId().matches("session-[0-9a-f]{32}"));
            assertNull(client.getPid());
        }
    }
    @Test void nativeEventDataAndStatesAreUsableFromJava17() {
        var raw = HarnessProtocol.json.parseToJsonElement(
            "{\"type\":\"turn/end\",\"seq\":1,\"time\":42,\"data\":{\"turn\":1,\"reason\":{\"kind\":\"completed\"}}}");
        var event = HarnessProtocol.event((kotlinx.serialization.json.JsonObject) raw);
        assertEquals(SessionEventType.Builtin.TURN_END, event.getType());
        assertTrue(event.getData() instanceof SessionEventData.TurnEnded);
        var ended = (SessionEventData.TurnEnded) event.getData();
        assertEquals(FinishReason.Builtin.COMPLETED, ended.getReason().getKind());
        assertEquals("completed", ended.getReason().getKind().getWireName());
        assertEquals(AgentStatus.RUNNING, AgentStatus.valueOf("RUNNING"));
        assertEquals("session/query", SdkRequestMethod.SESSION_QUERY.getWireName());
    }
    @Test void standaloneOfficeApiAndBuildersWorkFromJava17() throws Exception {
        var cli = Files.writeString(root.resolve("java-office.mjs"), """
            import { mkdirSync, writeFileSync } from 'node:fs'
            import { join } from 'node:path'
            const cmd = process.argv[2]
            if (cmd === 'capabilities') {
              process.stdout.write(JSON.stringify({
                runtime: { version: '0.2.3', backend: 'wasm', nativePreview: false, cliPath: process.argv[1], nodeApiPath: process.argv[1] },
                conversions: [{ inputs: ['docx'], outputs: ['pdf'] }],
                imageRendering: { inputs: ['docx'], output: 'png', officeBackend: 'libreoffice', pdfBackend: 'pdfium', maxPages: 100, maxPixels: 16777216, maxDimension: 8192 },
                recalculation: { inputs: ['xlsx'], outputs: ['xlsx'], preservesFormulas: true },
              }) + '\\n')
            } else if (cmd === 'convert' || cmd === 'recalculate') {
              const out = process.argv[process.argv.indexOf('--output') + 1]
              writeFileSync(out, 'ok')
              process.stdout.write(JSON.stringify({ backend: 'wasm', outputPath: out, missingFonts: [], pageCount: 2 }) + '\\n')
            } else if (cmd === 'render') {
              const dir = process.argv[process.argv.indexOf('--output-dir') + 1]
              mkdirSync(dir)
              const img = join(dir, 'page-0001.png')
              writeFileSync(img, 'png')
              const manifest = {
                schemaVersion: 1, backend: 'wasm', rasterEngine: 'libreoffice', source: 'saved',
                inputPath: process.argv[process.argv.indexOf('--input') + 1], sourceSha256: 'abcd',
                dpi: 96, pageCount: 1, missingFonts: [],
                images: [{ index: 1, path: img, width: 10, height: 20, rectangle: { x: 0, y: 0, width: 10, height: 20 }, byteLength: 3, sheet: 'Summary', range: 'A1:B2' }],
              }
              writeFileSync(join(dir, 'manifest.json'), JSON.stringify(manifest) + '\\n')
              process.stdout.write(JSON.stringify(manifest) + '\\n')
            }
            """);
        var options = OfficeOptions.builder().timeout(Duration.ofSeconds(10)).maxInputBytes(1024L).build();
        var config = OfficeConfig.builder().cwd(root).command(RuntimeCommand.node(cli)).defaultOptions(options).build();
        try (var office = new HarnessOffice(config)) {
            assertEquals("wasm", office.capabilities().getRuntime().getBackend());
            var converted = office.convert(Path.of("in.docx"), Path.of("out.pdf"), options);
            assertEquals(2, converted.getPageCount());
            assertEquals(root.resolve("out.pdf"), converted.getOutputPath());
            var recalc = office.recalculate(Path.of("in.xlsx"), Path.of("out.xlsx"));
            assertEquals(root.resolve("out.xlsx"), recalc.getOutputPath());
            var renderOpts = OfficeRenderOptions.builder().sheet("Summary").range("A1:B2").dpi(96).build();
            var rendered = office.renderImages(Path.of("in.xlsx"), Path.of("pages"), renderOpts);
            assertEquals("libreoffice", rendered.getRasterEngine());
            assertEquals(3L, rendered.getImages().get(0).getByteLength());
            assertEquals(OfficeRenderOptions.sheet("Summary", "A1:B2"), new OfficeRenderOptions(List.of(), "Summary", "A1:B2"));
            assertEquals(OfficeRenderOptions.pages(1, 3).getPages(), List.of(1, 3));
            var err = assertThrows(OfficeException.class, () -> office.convert(Path.of("in.docx"), Path.of("out.pdf")));
            assertEquals("invalid-output", err.getCode());
            assertNull(err.getExitCode());
        }
    }
}
