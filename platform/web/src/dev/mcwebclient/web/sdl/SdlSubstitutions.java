package dev.mcwebclient.web.sdl;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_LogOutputFunctionI;
import org.lwjgl.sdl.SDL_MessageBoxData;
import org.lwjgl.sdl.SDL_PixelFormatDetails;
import org.lwjgl.sdl.SDL_Rect;
import org.lwjgl.sdl.SDL_Surface;
import org.lwjgl.system.MemoryUtil;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import dev.mcwebclient.web.mem.NativeHeap;
import dev.mcwebclient.web.mem.Nmem;

/**
 * The LWJGL SDL3 binding methods Minecraft 26.3 calls (77 of them, minus the GL/Vulkan
 * ones that become unreachable), implemented on {@link WebSdl}. Substituting at the
 * binding level keeps Minecraft's Window/SDLEventHandler code, and any mixins on it,
 * unchanged. Strings in/out are Java strings; SDL-owned return memory lives in nmem.
 */
final class SdlSubstitutions {
    private static final long START = System.nanoTime();
    private static long displayMode;
    private static long pixelFormat;

    private SdlSubstitutions() {
    }

    static long ticksNs() {
        return System.nanoTime() - START;
    }

    /** One display: the browser viewport at its current size. */
    static SDL_DisplayMode currentMode() {
        if (displayMode == 0) {
            displayMode = NativeHeap.calloc(1, SDL_DisplayMode.SIZEOF);
        }
        Nmem.putInt(displayMode + SDL_DisplayMode.DISPLAYID, WebSdl.DISPLAY_ID);
        Nmem.putInt(displayMode + SDL_DisplayMode.FORMAT, 0x16762004); // SDL_PIXELFORMAT_XRGB8888
        Nmem.putInt(displayMode + SDL_DisplayMode.W, WebSdl.cssWidth());
        Nmem.putInt(displayMode + SDL_DisplayMode.H, WebSdl.cssHeight());
        Nmem.putFloat(displayMode + SDL_DisplayMode.PIXEL_DENSITY, WebSdl.pixelDensity());
        Nmem.putFloat(displayMode + SDL_DisplayMode.REFRESH_RATE, 60f);
        Nmem.putInt(displayMode + SDL_DisplayMode.REFRESH_RATE_NUMERATOR, 60);
        Nmem.putInt(displayMode + SDL_DisplayMode.REFRESH_RATE_DENOMINATOR, 1);
        return SDL_DisplayMode.create(displayMode);
    }

    /** RGBA8888-ish details; only used for icon/cursor surfaces, which the browser ignores. */
    static SDL_PixelFormatDetails formatDetails(int format) {
        if (pixelFormat == 0) {
            pixelFormat = NativeHeap.calloc(1, SDL_PixelFormatDetails.SIZEOF);
        }
        Nmem.putInt(pixelFormat + SDL_PixelFormatDetails.FORMAT, format);
        Nmem.putByte(pixelFormat + SDL_PixelFormatDetails.BITS_PER_PIXEL, (byte) 32);
        Nmem.putByte(pixelFormat + SDL_PixelFormatDetails.BYTES_PER_PIXEL, (byte) 4);
        Nmem.putInt(pixelFormat + SDL_PixelFormatDetails.RMASK, 0x000000ff);
        Nmem.putInt(pixelFormat + SDL_PixelFormatDetails.GMASK, 0x0000ff00);
        Nmem.putInt(pixelFormat + SDL_PixelFormatDetails.BMASK, 0x00ff0000);
        Nmem.putInt(pixelFormat + SDL_PixelFormatDetails.AMASK, 0xff000000);
        for (int off : new int[] {SDL_PixelFormatDetails.RBITS, SDL_PixelFormatDetails.GBITS,
                SDL_PixelFormatDetails.BBITS, SDL_PixelFormatDetails.ABITS}) {
            Nmem.putByte(pixelFormat + off, (byte) 8);
        }
        Nmem.putByte(pixelFormat + SDL_PixelFormatDetails.RSHIFT, (byte) 0);
        Nmem.putByte(pixelFormat + SDL_PixelFormatDetails.GSHIFT, (byte) 8);
        Nmem.putByte(pixelFormat + SDL_PixelFormatDetails.BSHIFT, (byte) 16);
        Nmem.putByte(pixelFormat + SDL_PixelFormatDetails.ASHIFT, (byte) 24);
        return SDL_PixelFormatDetails.create(pixelFormat);
    }

    static String str(ByteBuffer b) {
        return b == null ? null : MemoryUtil.memUTF8(b);
    }
}

@TargetClass(org.lwjgl.sdl.SDLInit.class)
final class Target_SDLInit {
    @Substitute
    public static boolean SDL_Init(int flags) {
        WebSdl.init();
        return true;
    }

    @Substitute
    public static void SDL_Quit() {
    }

    @Substitute
    public static boolean SDL_SetAppMetadataProperty(ByteBuffer name, ByteBuffer value) {
        return true;
    }

    @Substitute
    public static boolean SDL_SetAppMetadataProperty(CharSequence name, CharSequence value) {
        return true;
    }
}

@TargetClass(org.lwjgl.sdl.SDLHints.class)
final class Target_SDLHints {
    @Substitute
    public static boolean SDL_SetHint(ByteBuffer name, ByteBuffer value) {
        return true;
    }

    @Substitute
    public static boolean SDL_SetHint(CharSequence name, CharSequence value) {
        return true;
    }
}

@TargetClass(org.lwjgl.sdl.SDLError.class)
final class Target_SDLError {
    @Substitute
    public static String SDL_GetError() {
        return "";
    }
}

@TargetClass(org.lwjgl.sdl.SDLTimer.class)
final class Target_SDLTimer {
    @Substitute
    public static long SDL_GetTicksNS() {
        return SdlSubstitutions.ticksNs();
    }
}

@TargetClass(org.lwjgl.sdl.SDLLog.class)
final class Target_SDLLog {
    @Substitute
    public static void SDL_SetLogOutputFunction(SDL_LogOutputFunctionI callback, long userdata) {
    }

    @Substitute
    public static void SDL_SetLogPriorities(int priority) {
    }
}

@TargetClass(org.lwjgl.sdl.SDLPlatform.class)
final class Target_SDLPlatform {
    @Substitute
    public static String SDL_GetPlatform() {
        return "Linux";
    }
}

@TargetClass(org.lwjgl.sdl.SDLStdinc.class)
final class Target_SDLStdinc {
    @Substitute
    public static void SDL_free(ByteBuffer mem) {
        if (mem != null) {
            NativeHeap.free(MemoryUtil.memAddress0(mem));
        }
    }

    @Substitute
    public static void SDL_free(IntBuffer mem) {
        if (mem != null) {
            NativeHeap.free(MemoryUtil.memAddress0(mem));
        }
    }

    @Substitute
    public static void SDL_free(LongBuffer mem) {
        if (mem != null) {
            NativeHeap.free(MemoryUtil.memAddress0(mem));
        }
    }

    @Substitute
    public static void SDL_free(PointerBuffer mem) {
        if (mem != null) {
            NativeHeap.free(mem.address0());
        }
    }
}

@TargetClass(org.lwjgl.sdl.SDLEvents.class)
final class Target_SDLEvents {
    @Substitute
    public static void SDL_PumpEvents() {
    }

    @Substitute
    public static boolean SDL_PollEvent(SDL_Event event) {
        return WebSdl.pollEvent(event.address());
    }

    @Substitute
    public static void SDL_FlushEvents(int minType, int maxType) {
        WebSdl.flushEvents(minType, maxType);
    }

    @Substitute
    public static long SDL_GetWindowFromEvent(SDL_Event event) {
        return WebSdl.WINDOW;
    }
}

@TargetClass(org.lwjgl.sdl.SDLKeyboard.class)
final class Target_SDLKeyboard {
    @Substitute
    public static short SDL_GetModState() {
        return WebSdl.modState();
    }

    @Substitute
    public static ByteBuffer SDL_GetKeyboardState() {
        return MemoryUtil.memByteBuffer(WebSdl.keyboardState(), 512);
    }

    @Substitute
    public static int SDL_GetKeyFromScancode(int scancode, short modstate, boolean keyEvent) {
        return Keys.keycodeFor(scancode, (modstate & 0x0003) != 0 && !keyEvent);
    }

    @Substitute
    public static String SDL_GetKeyName(int key) {
        return Keys.nameFor(key);
    }

    @Substitute
    public static boolean SDL_StartTextInput(long window) {
        WebSdl.textInput(true);
        return true;
    }

    @Substitute
    public static boolean SDL_StopTextInput(long window) {
        WebSdl.textInput(false);
        return true;
    }

    @Substitute
    public static boolean SDL_SetTextInputArea(long window, SDL_Rect.Buffer rect, int cursor) {
        return true;
    }

    @Substitute
    public static boolean SDL_ClearComposition(long window) {
        return true;
    }
}

@TargetClass(org.lwjgl.sdl.SDLMouse.class)
final class Target_SDLMouse {
    @Substitute
    public static int SDL_GetMouseState(FloatBuffer x, FloatBuffer y) {
        if (x != null) {
            x.put(x.position(), WebSdl.mouseX());
        }
        if (y != null) {
            y.put(y.position(), WebSdl.mouseY());
        }
        return WebSdl.mouseButtons();
    }

    @Substitute
    public static int SDL_GetGlobalMouseState(FloatBuffer x, FloatBuffer y) {
        return SDL_GetMouseState(x, y);
    }

    @Substitute
    public static boolean SDL_SetWindowRelativeMouseMode(long window, boolean enabled) {
        WebSdl.setRelativeMouseMode(enabled);
        return true;
    }

    @Substitute
    public static void SDL_WarpMouseInWindow(long window, float x, float y) {
    }

    @Substitute
    public static long SDL_CreateSystemCursor(int id) {
        return 0x1000L + id;
    }

    @Substitute
    public static long SDL_GetDefaultCursor() {
        return 0x1000L;
    }

    @Substitute
    public static boolean SDL_SetCursor(long cursor) {
        WebSdl.setCursor(cursor >= 0x1000L ? (int) (cursor - 0x1000L) : 0);
        return true;
    }
}

@TargetClass(org.lwjgl.sdl.SDLClipboard.class)
final class Target_SDLClipboard {
    @Substitute
    public static String SDL_GetClipboardText() {
        return WebSdl.clipboard();
    }

    @Substitute
    public static boolean SDL_SetClipboardText(ByteBuffer text) {
        WebSdl.setClipboard(SdlSubstitutions.str(text));
        return true;
    }

    @Substitute
    public static boolean SDL_SetClipboardText(CharSequence text) {
        WebSdl.setClipboard(text.toString());
        return true;
    }
}

@TargetClass(org.lwjgl.sdl.SDLMisc.class)
final class Target_SDLMisc {
    @Substitute
    public static boolean SDL_OpenURL(ByteBuffer url) {
        WebSdl.openUrl(SdlSubstitutions.str(url));
        return true;
    }

    @Substitute
    public static boolean SDL_OpenURL(CharSequence url) {
        WebSdl.openUrl(url.toString());
        return true;
    }
}

@TargetClass(org.lwjgl.sdl.SDLMessageBox.class)
final class Target_SDLMessageBox {
    @Substitute
    public static boolean SDL_ShowSimpleMessageBox(int flags, ByteBuffer title, ByteBuffer message, long window) {
        WebSdl.messageBox(SdlSubstitutions.str(title), SdlSubstitutions.str(message));
        return true;
    }

    @Substitute
    public static boolean SDL_ShowSimpleMessageBox(int flags, CharSequence title, CharSequence message, long window) {
        WebSdl.messageBox(String.valueOf(title), String.valueOf(message));
        return true;
    }

    @Substitute
    public static boolean SDL_ShowMessageBox(SDL_MessageBoxData data, IntBuffer buttonId) {
        WebSdl.messageBox(data.titleString(), data.messageString());
        if (buttonId != null) {
            buttonId.put(buttonId.position(), 0);
        }
        return true;
    }
}

@TargetClass(org.lwjgl.sdl.SDLPixels.class)
final class Target_SDLPixels {
    @Substitute
    public static SDL_PixelFormatDetails SDL_GetPixelFormatDetails(int format) {
        return SdlSubstitutions.formatDetails(format);
    }
}

@TargetClass(org.lwjgl.sdl.SDLSurface.class)
final class Target_SDLSurface {
    /** Icon/cursor images: kept as a plain struct; the browser doesn't use them. */
    @Substitute
    public static SDL_Surface SDL_CreateSurfaceFrom(int width, int height, int format, ByteBuffer pixels, int pitch) {
        long s = NativeHeap.calloc(1, SDL_Surface.SIZEOF);
        Nmem.putInt(s + SDL_Surface.FORMAT, format);
        Nmem.putInt(s + SDL_Surface.W, width);
        Nmem.putInt(s + SDL_Surface.H, height);
        Nmem.putInt(s + SDL_Surface.PITCH, pitch);
        Nmem.putLong(s + SDL_Surface.PIXELS, pixels == null ? 0 : MemoryUtil.memAddress(pixels));
        Nmem.putInt(s + SDL_Surface.REFCOUNT, 1);
        return SDL_Surface.create(s);
    }

    @Substitute
    public static boolean SDL_AddSurfaceAlternateImage(SDL_Surface surface, SDL_Surface image) {
        return true;
    }

    @Substitute
    public static void SDL_DestroySurface(SDL_Surface surface) {
        if (surface != null) {
            NativeHeap.free(surface.address());
        }
    }
}

@TargetClass(org.lwjgl.sdl.SDLVideo.class)
final class Target_SDLVideo {
    @Substitute
    public static long SDL_CreateWindow(ByteBuffer title, int w, int h, long flags) {
        WebSdl.setTitle(SdlSubstitutions.str(title));
        return WebSdl.WINDOW;
    }

    @Substitute
    public static long SDL_CreateWindow(CharSequence title, int w, int h, long flags) {
        WebSdl.setTitle(String.valueOf(title));
        return WebSdl.WINDOW;
    }

    @Substitute
    public static void SDL_DestroyWindow(long window) {
    }

    @Substitute
    public static String SDL_GetCurrentVideoDriver() {
        return "browser";
    }

    @Substitute
    public static IntBuffer SDL_GetDisplays() {
        long mem = NativeHeap.calloc(2, 4);
        Nmem.putInt(mem, WebSdl.DISPLAY_ID);
        return MemoryUtil.memIntBuffer(mem, 1);
    }

    @Substitute
    public static int SDL_GetPrimaryDisplay() {
        return WebSdl.DISPLAY_ID;
    }

    @Substitute
    public static String SDL_GetDisplayName(int display) {
        return "Browser";
    }

    @Substitute
    public static boolean SDL_GetDisplayBounds(int display, SDL_Rect rect) {
        long r = rect.address();
        Nmem.putInt(r + SDL_Rect.X, 0);
        Nmem.putInt(r + SDL_Rect.Y, 0);
        Nmem.putInt(r + SDL_Rect.W, WebSdl.cssWidth());
        Nmem.putInt(r + SDL_Rect.H, WebSdl.cssHeight());
        return true;
    }

    @Substitute
    public static int SDL_GetDisplayForWindow(long window) {
        return WebSdl.DISPLAY_ID;
    }

    @Substitute
    public static SDL_DisplayMode SDL_GetCurrentDisplayMode(int display) {
        return SdlSubstitutions.currentMode();
    }

    @Substitute
    public static SDL_DisplayMode SDL_GetDesktopDisplayMode(int display) {
        return SdlSubstitutions.currentMode();
    }

    @Substitute
    public static PointerBuffer SDL_GetFullscreenDisplayModes(int display) {
        long arr = NativeHeap.calloc(2, 8);
        Nmem.putLong(arr, SdlSubstitutions.currentMode().address());
        return MemoryUtil.memPointerBuffer(arr, 1);
    }

    @Substitute
    public static boolean SDL_GetClosestFullscreenDisplayMode(int display, int w, int h, float refreshRate,
                                                              boolean includeHighDensityModes, SDL_DisplayMode closest) {
        Nmem.copy(SdlSubstitutions.currentMode().address(), closest.address(), SDL_DisplayMode.SIZEOF);
        return true;
    }

    @Substitute
    public static SDL_DisplayMode SDL_GetWindowFullscreenMode(long window) {
        return null;
    }

    @Substitute
    public static boolean SDL_SetWindowFullscreenMode(long window, SDL_DisplayMode mode) {
        return true;
    }

    @Substitute
    public static boolean SDL_SetWindowFullscreen(long window, boolean fullscreen) {
        return true;
    }

    @Substitute
    public static long SDL_GetWindowFlags(long window) {
        long flags = 0x20L; // SDL_WINDOW_RESIZABLE (shown, not hidden)
        if (WebSdl.focused()) {
            flags |= 0x200L; // SDL_WINDOW_INPUT_FOCUS
            flags |= 0x400L; // SDL_WINDOW_MOUSE_FOCUS
        }
        if (WebSdl.relativeMouseMode()) {
            flags |= 0x8000L; // SDL_WINDOW_MOUSE_RELATIVE_MODE
        }
        return flags;
    }

    @Substitute
    public static float SDL_GetWindowPixelDensity(long window) {
        return WebSdl.pixelDensity();
    }

    @Substitute
    public static boolean SDL_GetWindowPosition(long window, IntBuffer x, IntBuffer y) {
        if (x != null) {
            x.put(x.position(), 0);
        }
        if (y != null) {
            y.put(y.position(), 0);
        }
        return true;
    }

    @Substitute
    public static boolean SDL_GetWindowSizeInPixels(long window, IntBuffer w, IntBuffer h) {
        if (w != null) {
            w.put(w.position(), WebSdl.pixelWidth());
        }
        if (h != null) {
            h.put(h.position(), WebSdl.pixelHeight());
        }
        return true;
    }

    @Substitute
    public static boolean SDL_RestoreWindow(long window) {
        return true;
    }

    @Substitute
    public static boolean SDL_SetWindowBordered(long window, boolean bordered) {
        return true;
    }

    @Substitute
    public static boolean SDL_SetWindowIcon(long window, SDL_Surface icon) {
        return true;
    }

    @Substitute
    public static boolean SDL_SetWindowMaximumSize(long window, int w, int h) {
        return true;
    }

    @Substitute
    public static boolean SDL_SetWindowMinimumSize(long window, int w, int h) {
        return true;
    }

    @Substitute
    public static boolean SDL_SetWindowMouseGrab(long window, boolean grabbed) {
        return true;
    }

    @Substitute
    public static boolean SDL_SetWindowPosition(long window, int x, int y) {
        return true;
    }

    @Substitute
    public static boolean SDL_SetWindowSize(long window, int w, int h) {
        return true;
    }

    @Substitute
    public static boolean SDL_SetWindowTitle(long window, ByteBuffer title) {
        WebSdl.setTitle(SdlSubstitutions.str(title));
        return true;
    }

    @Substitute
    public static boolean SDL_SetWindowTitle(long window, CharSequence title) {
        WebSdl.setTitle(String.valueOf(title));
        return true;
    }

    @Substitute
    public static boolean SDL_SyncWindow(long window) {
        return true;
    }
}
