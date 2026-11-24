package cn.sky.ncm.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.sky.ncm.ai.MusicAiService.SegmentationInput;
import cn.sky.ncm.ai.MusicAiService.SegmentationTruncatedException;
import cn.sky.ncm.ai.MusicAiService.ThemeInput;
import cn.sky.ncm.ai.MusicAiService.ThemeWordColor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.pf4j.DefaultVersionManager;
import org.pf4j.PluginDependency;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.aifoundation.AiModelService;
import run.halo.aifoundation.chat.FinishReason;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.aifoundation.chat.GenerateTextResult;
import run.halo.aifoundation.chat.LanguageModel;
import run.halo.aifoundation.chat.ReasoningOptions;
import run.halo.aifoundation.exception.StructuredOutputTerminationException;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

class MusicAiServiceTest {
    private final MusicAiSettings settings = mock(MusicAiSettings.class);
    private final PluginManager manager = mock(PluginManager.class);
    private final PluginWrapper consumer = mock(PluginWrapper.class);
    private final PluginWrapper foundation = mock(PluginWrapper.class);
    private final PluginDescriptor foundationDescriptor = mock(PluginDescriptor.class);
    private final ExtensionGetter extensions = mock(ExtensionGetter.class);
    private final AiModelService models = mock(AiModelService.class);
    private final LanguageModel model = mock(LanguageModel.class);
    private MusicAiService service;

    @BeforeEach
    void modelAvailable() {
        when(settings.access(any())).thenReturn(Mono.just(MusicAiSettings.Access.ALLOWED));
        var descriptor = mock(PluginDescriptor.class);
        when(descriptor.getDependencies()).thenReturn(List.of(new PluginDependency("ai-foundation?@>=1.4.0 & <2.0.0")));
        when(consumer.getDescriptor()).thenReturn(descriptor);
        when(consumer.getPluginManager()).thenReturn(manager);
        when(manager.getPlugin("ai-foundation")).thenReturn(foundation);
        when(manager.getVersionManager()).thenReturn(new DefaultVersionManager());
        when(foundation.getPluginState()).thenReturn(PluginState.STARTED);
        when(foundation.getDescriptor()).thenReturn(foundationDescriptor);
        when(foundationDescriptor.getVersion()).thenReturn("1.4.0");
        when(foundation.getPluginClassLoader()).thenReturn(AiModelService.class.getClassLoader());
        when(extensions.getEnabledExtension(AiModelService.class)).thenReturn(Mono.just(models));
        when(models.languageModel()).thenReturn(Mono.just(model));
        service = new MusicAiService(settings, consumer, extensions, new MusicAiBudget());
    }

    @Test
    void offNeitherResolvesNorCallsModel() {
        when(settings.access(any())).thenReturn(Mono.just(MusicAiSettings.Access.DISABLED));
        StepVerifier.create(service.available(viewer("user"))).expectNext(false).verifyComplete();
        StepVerifier.create(service.theme(viewer("user"), new ThemeInput("歌词", "测试", false)))
            .expectErrorMatches(error -> status(error, HttpStatus.SERVICE_UNAVAILABLE))
            .verify();
        verifyNoInteractions(manager, extensions, model);
    }

    @Test
    void aViewerOutsideTheSelectedRolesCannotSeeOrUseEitherAiFeature() {
        var viewer = viewer("denied");
        when(settings.access(viewer)).thenReturn(Mono.just(MusicAiSettings.Access.DENIED));
        var client = WebTestClient.bindToRouterFunction(new MusicAiEndpoint(service).endpoint())
            .webFilter((exchange, chain) -> chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(viewer)))
            .configureClient().baseUrl("http://localhost:8090").build();

        client.get().uri("/capabilities/-").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.available").isEqualTo(false);
        client.post().uri("/themes").header("Origin", "http://localhost:8090")
            .header("X-NCM-Request", "1").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new ThemeInput("听风", "测试", false)).exchange().expectStatus().isForbidden();
        client.post().uri("/segmentations").header("Origin", "http://localhost:8090")
            .header("X-NCM-Request", "1").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new SegmentationInput(List.of("听风"))).exchange().expectStatus().isForbidden();
        verifyNoInteractions(manager, extensions, model);
    }

    @Test
    void hidesAiUntilFoundationIsStartedAndCompatible() {
        when(manager.getPlugin("ai-foundation")).thenReturn(null);
        StepVerifier.create(service.available(viewer("user"))).expectNext(false).verifyComplete();
        when(manager.getPlugin("ai-foundation")).thenReturn(foundation);
        when(foundation.getPluginState()).thenReturn(PluginState.DISABLED);
        StepVerifier.create(service.available(viewer("user"))).expectNext(false).verifyComplete();
        when(foundation.getPluginState()).thenReturn(PluginState.STARTED);
        when(foundationDescriptor.getVersion()).thenReturn("2.0.0");
        StepVerifier.create(service.available(viewer("user"))).expectNext(false).verifyComplete();
        when(foundationDescriptor.getVersion()).thenReturn("1.4.0");
        when(foundation.getPluginClassLoader()).thenReturn(mock(ClassLoader.class));
        StepVerifier.create(service.available(viewer("user"))).expectNext(false).verifyComplete();
        verifyNoInteractions(extensions, model);
    }

    @Test
    void unavailableModelHidesFeatureWithoutCallingProvider() {
        when(models.languageModel()).thenReturn(Mono.error(new IllegalStateException("missing model")));
        StepVerifier.create(service.available(viewer("user"))).expectNext(false).verifyComplete();
        StepVerifier.create(service.segment(viewer("user"), new SegmentationInput(List.of("歌词"))))
            .expectErrorMatches(error -> status(error, HttpStatus.SERVICE_UNAVAILABLE))
            .verify();
        verifyNoInteractions(model);
    }

    @Test
    void availabilityDoesNotGenerateAnything() {
        StepVerifier.create(service.available(viewer("user"))).expectNext(true).verifyComplete();
        verifyNoInteractions(model);
    }

    @Test
    void providesBothPalettesAsStructuredOutput() {
        Map<String, String> palette = Map.of("name", "海边", "backgroundColor", "#123456",
            "primaryColor", "#ffffff", "accentColor", "#ffcc00", "secondaryColor", "#baccdd",
            "description", "潮水退后海风把心事留在了安静的沙滩上");
        answer(Map.of("light", palette, "dark", palette,
            "wordColors", List.of(
                Map.of("word", "海边", "color", "#123456"),
                Map.of("word", "海边", "color", "#111111"),
                Map.of("word", "月亮", "color", "#234567"),
                Map.of("word", "沙滩", "color", "#abc")),
            "lyricsIcons", List.of("Waves", "Waves", "<svg/>", "Wind")));
        StepVerifier.create(service.theme(viewer("user"), new ThemeInput("沙滩上的附加歌词", "海边", true)))
            .assertNext(result -> {
                assertThat(result.light().name()).isEqualTo("海边");
                assertThat(result.light().description()).contains("潮水");
                assertThat(result.dark().accentColor()).isEqualTo("#ffcc00");
                assertThat(result.dark().provider()).isEqualTo("AI");
                assertThat(result.light().wordColors()).containsExactly(new ThemeWordColor("海边", "#123456"));
                assertThat(result.light().lyricsIcons()).containsExactly("Waves", "Wind");
                assertThat(result.dark().wordColors()).isEqualTo(result.light().wordColors());
                assertThat(result.dark().lyricsIcons()).isEqualTo(result.light().lyricsIcons());
            })
            .verifyComplete();
        var captured = ArgumentCaptor.forClass(GenerateTextRequest.class);
        verify(model).generateText(captured.capture());
        assertThat(captured.getValue().getOutput().getSchema()).containsKeys("properties", "required");
        assertThat(captured.getValue().getMaxRetries()).isZero();
        assertThat(captured.getValue().getReasoning()).isEqualTo(ReasoningOptions.disabled());
        assertThat(captured.getValue().getPrompt()).contains("海边").doesNotContain("沙滩上的附加歌词");
    }

    @Test
    void acceptsBasicPalettesAndLimitsLyricsSentToTheThemeModel() {
        var palette = Map.of("name", "海边", "backgroundColor", "#123456", "primaryColor", "#ffffff",
            "accentColor", "#ffcc00", "secondaryColor", "#baccdd");
        answer(Map.of("light", palette, "dark", palette));
        String snippet = "词".repeat(2000);
        StepVerifier.create(service.theme(viewer("user"), new ThemeInput(snippet + "超出片段", "海边", false)))
            .assertNext(result -> {
                assertThat(result.light().wordColors()).isEmpty();
                assertThat(result.dark().lyricsIcons()).isEmpty();
            })
            .verifyComplete();
        var captured = ArgumentCaptor.forClass(GenerateTextRequest.class);
        verify(model).generateText(captured.capture());
        assertThat(captured.getValue().getPrompt()).endsWith(snippet).doesNotContain("超出片段");
    }

    @Test
    void keepsExactTextIncludingSpacesAndDropsRewrittenRows() {
        answer(Map.of("lines", List.of(List.of("听", " 风"), List.of("改错了"))));
        StepVerifier.create(service.segment(viewer("user"), new SegmentationInput(List.of("听 风", "雨"))))
            .assertNext(result -> {
                assertThat(result.lines().getFirst()).containsExactly("听", " 风");
                assertThat(result.lines().get(1)).isNull();
            })
            .verifyComplete();
        var captured = ArgumentCaptor.forClass(GenerateTextRequest.class);
        verify(model).generateText(captured.capture());
        assertThat(captured.getValue().getReasoning()).isEqualTo(ReasoningOptions.disabled());
    }

    @Test
    void marksTruncationInTheSegmentationResponse() {
        var auth = UsernamePasswordAuthenticationToken.authenticated("user", "unused", List.of());
        var client = WebTestClient.bindToRouterFunction(new MusicAiEndpoint(service).endpoint())
            .webFilter((exchange, chain) -> chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
            .configureClient().baseUrl("http://localhost:8090").build();
        var termination = mock(StructuredOutputTerminationException.class);
        when(termination.getFinishReason()).thenReturn(FinishReason.LENGTH);
        when(model.generateText(any(GenerateTextRequest.class))).thenReturn(Mono.error(termination));

        client.post().uri("/segmentations").header("Origin", "http://localhost:8090")
            .header("X-NCM-Request", "1").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new SegmentationInput(List.of("听风")))
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody().jsonPath("$.code").isEqualTo("SEGMENTATION_TRUNCATED");
    }

    @Test
    void marksAnEmptyLengthResultAndKeepsOtherFailuresGeneric() {
        var input = new SegmentationInput(List.of("听风"));
        when(model.generateText(any(GenerateTextRequest.class))).thenReturn(Mono.just(
            GenerateTextResult.builder().finishReason(FinishReason.LENGTH).build()));
        StepVerifier.create(service.segment(viewer("user"), input))
            .expectError(SegmentationTruncatedException.class).verify();

        when(model.generateText(any(GenerateTextRequest.class))).thenReturn(Mono.error(new IllegalStateException()));
        StepVerifier.create(service.segment(viewer("user"), input))
            .expectErrorMatches(error -> status(error, HttpStatus.BAD_GATEWAY)).verify();
    }

    @Test
    void rejectsMissingRowsAndUnsafeColors() {
        answer(Map.of("lines", List.of(List.of("听风"))));
        StepVerifier.create(service.segment(viewer("user"), new SegmentationInput(List.of("听风", "雨"))))
            .expectErrorMatches(error -> status(error, HttpStatus.BAD_GATEWAY)).verify();
        var invalid = Map.of("name", "海边", "backgroundColor", "url(https://example.invalid/)");
        answer(Map.of("light", invalid, "dark", invalid));
        StepVerifier.create(service.theme(viewer("user"), new ThemeInput("听风", null, false)))
            .expectErrorMatches(error -> status(error, HttpStatus.BAD_GATEWAY)).verify();
    }

    @Test
    void rejectsOversizeInputBeforeResolvingModel() {
        StepVerifier.create(service.theme(viewer("user"), new ThemeInput("词".repeat(12001), null, false)))
            .expectErrorMatches(error -> status(error, HttpStatus.BAD_REQUEST)).verify();
        verifyNoInteractions(extensions);
    }

    @Test
    void rateLimitsRepeatedGeneration() {
        answer(Map.of("lines", List.of(List.of("听", "风"))));
        var input = new SegmentationInput(List.of("听风"));
        for (int i = 0; i < 8; i++) {
            StepVerifier.create(service.segment(viewer("user"), input)).expectNextCount(1).verifyComplete();
        }
        StepVerifier.create(service.segment(viewer("user"), input))
            .expectErrorMatches(error -> status(error, HttpStatus.TOO_MANY_REQUESTS)).verify();
        StepVerifier.create(service.segment(viewer("another-user"), input)).expectNextCount(1).verifyComplete();
    }

    private void answer(Object output) {
        when(model.generateText(any(GenerateTextRequest.class))).thenReturn(Mono.just(
            GenerateTextResult.builder().output(output).build()));
    }

    private static UsernamePasswordAuthenticationToken viewer(String username) {
        return UsernamePasswordAuthenticationToken.authenticated(username, "unused", List.of());
    }

    private static boolean status(Throwable error, HttpStatus expected) {
        return error instanceof ResponseStatusException status && status.getStatusCode() == expected;
    }
}
