package net.vulkanmod.mixin.network;

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.epoll.Epoll;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes "Unable to access address of buffer" crash when connecting to servers on Java 17+.
 *
 * Root cause: Netty 4.0.23's native epoll transport uses JNI GetDirectBufferAddress,
 * which fails when PlatformDependent0's Unsafe is broken by module encapsulation.
 * Disabling epoll forces the NIO transport which works with heap buffers.
 */
@Mixin(ClientConnection.class)
public class ClientConnectionMixin {

    @Redirect(method = "connect", at = @At(value = "INVOKE", target = "Lio/netty/channel/epoll/Epoll;isAvailable()Z"))
    private static boolean disableEpoll() {
        // Force NIO transport instead of epoll. Epoll's native code requires
        // direct buffer addresses via JNI which breaks on Java 17+ with patched Unsafe.
        return false;
    }

    @Inject(method = "channelActive", at = @At("HEAD"))
    private void forceHeapAllocator(ChannelHandlerContext ctx, CallbackInfo ci) throws Exception {
        // Force heap buffers to avoid PlatformDependent.directBufferAddress() calls
        ctx.channel().config().setAllocator(new UnpooledByteBufAllocator(false));
    }
}
