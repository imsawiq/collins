package org.sawiq.collins.fabric.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes NativeImage's native pointer so we can blit decoded video frames
 * directly into its memory via LWJGL MemoryUtil.
 *
 * <p>26.1 renamed the underlying field from {@code pointer} to {@code pixels}.</p>
 */
@Mixin(NativeImage.class)
public interface NativeImageAccessor {
    @Accessor("pixels")
    long collins$getPointer();
}
