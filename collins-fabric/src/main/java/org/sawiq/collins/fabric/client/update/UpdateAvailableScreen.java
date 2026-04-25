package org.sawiq.collins.fabric.client.update;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/**
 * Title-screen overlay that informs the player a newer release of
 * {@code collins-fabric} is available on Modrinth and lets them open the
 * project page in their default browser.
 */
public final class UpdateAvailableScreen extends Screen {
    private final Screen parent;
    private final String newVersion;
    private final String currentVersion;
    private final String url;

    public UpdateAvailableScreen(Screen parent, String newVersion, String url) {
        super(Text.translatable("text.collins.update.title"));
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

        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("text.collins.update.open_page"),
                        button -> Util.getOperatingSystem().open(this.url))
                .dimensions(centerX - 100, centerY + 24, 200, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("text.collins.update.dismiss"),
                        button -> close())
                .dimensions(centerX - 100, centerY + 50, 200, 20)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int baseY = this.height / 2 - 40;

        drawCenteredText(context, this.title, centerX, baseY, 0xFFFFFFFF);
        drawCenteredText(context, Text.translatable("text.collins.update.subtitle", this.newVersion),
                centerX, baseY + 18, 0xFF55FF55);
        drawCenteredText(context, Text.translatable("text.collins.update.current", this.currentVersion),
                centerX, baseY + 32, 0xFFAAAAAA);
    }

    @Override
    public void close() {
        if (this.client == null) {
            return;
        }
        if (this.parent != null) {
            this.client.setScreen(this.parent);
        } else {
            this.client.setScreen(null);
        }
    }

    private void drawCenteredText(DrawContext context, Text text, int centerX, int y, int color) {
        int width = this.textRenderer.getWidth(text);
        context.drawTextWithShadow(this.textRenderer, text, centerX - width / 2, y, color);
    }
}
