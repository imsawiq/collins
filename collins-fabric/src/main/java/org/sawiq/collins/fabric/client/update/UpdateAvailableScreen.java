package org.sawiq.collins.fabric.client.update;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Title-screen overlay that informs the player a newer release of
 * {@code collins-fabric} is available on Modrinth and lets them open the
 * project page in their default browser.
 *
 * <p>26.1: the rendering pipeline switched from immediate-mode
 * {@code render(GuiGraphics, ...)} to the deferred extraction API
 * {@code extractRenderState(GuiGraphicsExtractor, ...)}, so all drawing
 * happens via {@link GuiGraphicsExtractor#centeredText} below.</p>
 */
public final class UpdateAvailableScreen extends Screen {
    private final Screen parent;
    private final String newVersion;
    private final String currentVersion;
    private final String url;

    public UpdateAvailableScreen(Screen parent, String newVersion, String url) {
        super(Component.translatable("text.collins.update.title"));
        this.parent = parent;
        this.newVersion = newVersion;
        this.url = url;
        this.currentVersion = FabricLoader.getInstance()
                .getModContainer("collins-fabric")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        addRenderableWidget(Button.builder(
                        Component.translatable("text.collins.update.open_page"),
                        button -> openUrl(this.url))
                .bounds(centerX - 100, centerY + 24, 200, 20)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("text.collins.update.dismiss"),
                        button -> onClose())
                .bounds(centerX - 100, centerY + 50, 200, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float delta) {
        super.extractRenderState(extractor, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int baseY = this.height / 2 - 40;

        extractor.centeredText(this.font, this.title, centerX, baseY, 0xFFFFFFFF);
        extractor.centeredText(this.font,
                Component.translatable("text.collins.update.subtitle", this.newVersion),
                centerX, baseY + 18, 0xFF55FF55);
        extractor.centeredText(this.font,
                Component.translatable("text.collins.update.current", this.currentVersion),
                centerX, baseY + 32, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        if (this.minecraft == null) {
            return;
        }
        this.minecraft.setScreen(this.parent);
    }

    private static void openUrl(String url) {
        try {
            Util.getPlatform().openUri(new URI(url));
        } catch (URISyntaxException ignored) {
            // Modrinth URLs are statically defined; misformed URIs would be a
            // programmer error rather than something the user can recover from.
        }
    }
}
