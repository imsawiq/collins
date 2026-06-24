package org.sawiq.collins.fabric.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.sawiq.collins.fabric.client.video.HwAccelBackend;

import java.util.ArrayList;
import java.util.List;

public final class CollinsModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return this::buildScreen;
    }

    private Screen buildScreen(Screen parent) {
        CollinsClientConfig cfg = CollinsClientConfig.get();

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.translatable("config.collins.title"));

        builder.setSavingRunnable(CollinsClientConfig::save);

        ConfigCategory general = builder.getOrCreateCategory(Text.translatable("config.collins.category.video"));
        ConfigEntryBuilder eb = builder.entryBuilder();

        general.addEntry(eb.startIntSlider(Text.translatable("config.collins.local_volume"), cfg.localVolumePercent, 0, 100)
            .setDefaultValue(100)
            .setSaveConsumer(v -> cfg.localVolumePercent = v)
            .build());

        general.addEntry(eb.startBooleanToggle(Text.translatable("config.collins.render_video"), cfg.renderVideo)
            .setDefaultValue(true)
            .setSaveConsumer(v -> cfg.renderVideo = v)
            .build());

        general.addEntry(eb.startBooleanToggle(Text.translatable("config.collins.actionbar_timeline"), cfg.actionbarTimeline)
            .setDefaultValue(true)
            .setSaveConsumer(v -> cfg.actionbarTimeline = v)
            .build());

        general.addEntry(eb.startBooleanToggle(Text.translatable("config.collins.hardware_decoding"), cfg.hardwareDecoding)
            .setDefaultValue(true)
            .setTooltip(Text.translatable("config.collins.hardware_decoding.tooltip"))
            .setSaveConsumer(v -> cfg.hardwareDecoding = v)
            .build());

        List<Text> backendNames = new ArrayList<>();
        for (HwAccelBackend b : HwAccelBackend.values()) {
            backendNames.add(Text.literal(b.name()));
        }
        general.addEntry(eb.startSelector(
                Text.translatable("config.collins.hwaccel_backend"),
                backendNames.toArray(new Text[0]),
                Text.literal(cfg.hwAccelBackend))
            .setDefaultValue(Text.literal(HwAccelBackend.detectDefault().name()))
            .setTooltip(Text.translatable("config.collins.hwaccel_backend.tooltip"))
            .setSaveConsumer((Text v) -> cfg.hwAccelBackend = v.getString())
            .build());

        return builder.build();
    }
}
