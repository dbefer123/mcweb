package dev.mcwebclient.web.sdl;

import java.nio.charset.StandardCharsets;

import org.graalvm.webimage.api.JS;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_KeyboardEvent;
import org.lwjgl.sdl.SDL_MouseButtonEvent;
import org.lwjgl.sdl.SDL_MouseMotionEvent;
import org.lwjgl.sdl.SDL_MouseWheelEvent;
import org.lwjgl.sdl.SDL_TextInputEvent;
import org.lwjgl.sdl.SDL_WindowEvent;

import dev.mcwebclient.web.WebJs;
import dev.mcwebclient.web.mem.NativeHeap;
import dev.mcwebclient.web.mem.Nmem;

/**
 * The SDL3 state machine behind the substituted LWJGL SDL bindings. The page's host
 * script owns the canvas and DOM events; it shares one block of nmem with us:
 *
 * <pre>
 * +0  magic 'SDLW'      +4  write index (JS)   +8  read index (Java)  +12 slot count
 * +16 css width         +20 css height         +24 pixel width        +28 pixel height
 * +32 dpr * 1000        +36 focused            +40 mouse x (f32 css)  +44 mouse y (f32)
 * +48 button mask       +52 pointer locked     +56 caps lock          +60 reserved
 * +64 event slots, 32 bytes each: i32 type, i32 a, i32 b, i32 c, f32 x, f32 y, f32 dx, f32 dy
 * </pre>
 *
 * Key events carry a = scancode, b = keycode, c = flags (bit 0 repeat). Text input
 * carries a = byte length and the UTF-8 bytes in the following slots.
 */
public final class WebSdl {
    public static final long WINDOW = 1L;
    public static final int WINDOW_ID = 1;
    public static final int DISPLAY_ID = 1;

    private static final int MAGIC = 0x57_4C_44_53; // "SDLW"
    private static final int SLOTS = 1024;
    private static final int SLOT = 32;

    private static long block;
    private static long keyState;     // 512-byte SDL keyboard state array
    private static long pendingText;  // text of the last delivered text-input event
    private static boolean relativeMouse;
    private static String title = "Minecraft";
    private static String clipboard = "";

    private WebSdl() {
    }

    public static synchronized void init() {
        if (block != 0) {
            return;
        }
        block = NativeHeap.calloc(1, 64 + (long) SLOTS * SLOT);
        keyState = NativeHeap.calloc(1, 512);
        Nmem.putInt(block, MAGIC);
        Nmem.putInt(block + 12, SLOTS);
        attachHost((int) block, SLOTS, (int) keyState);
    }

    /** Hands the shared block to the page; it starts writing events and sizes. */
    @JS.Coerce
    @JS(value = "globalThis.mcweb?.attachInput?.(getExport('nmem'), block, slots, keyState);",
            args = {"block", "slots", "keyState"})
    private static native void attachHost(int block, int slots, int keyState);

    // ---- window state ----

    public static int cssWidth() {
        return Math.max(1, Nmem.getInt(block + 16));
    }

    public static int cssHeight() {
        return Math.max(1, Nmem.getInt(block + 20));
    }

    public static int pixelWidth() {
        return Math.max(1, Nmem.getInt(block + 24));
    }

    public static int pixelHeight() {
        return Math.max(1, Nmem.getInt(block + 28));
    }

    public static float pixelDensity() {
        int v = Nmem.getInt(block + 32);
        return v <= 0 ? 1f : v / 1000f;
    }

    public static boolean focused() {
        return Nmem.getInt(block + 36) != 0;
    }

    public static float mouseX() {
        return Nmem.getFloat(block + 40);
    }

    public static float mouseY() {
        return Nmem.getFloat(block + 44);
    }

    public static int mouseButtons() {
        return Nmem.getInt(block + 48);
    }

    public static long keyboardState() {
        return keyState;
    }

    /** SDL_Keymod bits derived from the key state array and caps lock. */
    public static short modState() {
        int m = 0;
        if (Nmem.getByte(keyState + 225) != 0) m |= 0x0001; // LSHIFT
        if (Nmem.getByte(keyState + 229) != 0) m |= 0x0002; // RSHIFT
        if (Nmem.getByte(keyState + 224) != 0) m |= 0x0040; // LCTRL
        if (Nmem.getByte(keyState + 228) != 0) m |= 0x0080; // RCTRL
        if (Nmem.getByte(keyState + 226) != 0) m |= 0x0100; // LALT
        if (Nmem.getByte(keyState + 230) != 0) m |= 0x0200; // RALT
        if (Nmem.getByte(keyState + 227) != 0) m |= 0x0400; // LGUI
        if (Nmem.getByte(keyState + 231) != 0) m |= 0x0800; // RGUI
        if (block != 0 && Nmem.getInt(block + 56) != 0) m |= 0x2000; // CAPS
        return (short) m;
    }

    // ---- events ----

    public static boolean pollEvent(long event) {
        if (pendingText != 0) {
            NativeHeap.free(pendingText);
            pendingText = 0;
        }
        if (block == 0) {
            return false;
        }
        int write = Nmem.getInt(block + 4);
        int read = Nmem.getInt(block + 8);
        if (read == write) {
            return false;
        }
        long slot = block + 64 + (long) (read % SLOTS) * SLOT;
        int type = Nmem.getInt(slot);
        int a = Nmem.getInt(slot + 4);
        int b = Nmem.getInt(slot + 8);
        int c = Nmem.getInt(slot + 12);
        float x = Nmem.getFloat(slot + 16);
        float y = Nmem.getFloat(slot + 20);
        float dx = Nmem.getFloat(slot + 24);
        float dy = Nmem.getFloat(slot + 28);
        int consumed = 1;

        Nmem.fill(event, 0, SDL_Event.SIZEOF);
        Nmem.putInt(event + SDL_Event.TYPE, type);
        long ts = System.nanoTime();
        switch (type) {
            case 768, 769 -> { // KEY_DOWN / KEY_UP
                Nmem.putLong(event + SDL_KeyboardEvent.TIMESTAMP, ts);
                Nmem.putInt(event + SDL_KeyboardEvent.WINDOWID, WINDOW_ID);
                Nmem.putInt(event + SDL_KeyboardEvent.SCANCODE, a);
                Nmem.putInt(event + SDL_KeyboardEvent.KEY, b);
                Nmem.putShort(event + SDL_KeyboardEvent.MOD, modState());
                Nmem.putByte(event + SDL_KeyboardEvent.DOWN, (byte) (type == 768 ? 1 : 0));
                Nmem.putByte(event + SDL_KeyboardEvent.REPEAT, (byte) (c & 1));
            }
            case 771 -> { // TEXT_INPUT: UTF-8 bytes follow in the next slots
                int len = a;
                int extraSlots = (len + SLOT - 1) / SLOT;
                byte[] bytes = new byte[len];
                for (int i = 0; i < len; i++) {
                    int s = (read + 1 + i / SLOT) % SLOTS;
                    bytes[i] = Nmem.getByte(block + 64 + (long) s * SLOT + (i % SLOT));
                }
                consumed += extraSlots;
                pendingText = NativeHeap.malloc(len + 1L);
                Nmem.copyFromBytes(bytes, 0, pendingText, len);
                Nmem.putByte(pendingText + len, (byte) 0);
                Nmem.putLong(event + SDL_TextInputEvent.TIMESTAMP, ts);
                Nmem.putInt(event + SDL_TextInputEvent.WINDOWID, WINDOW_ID);
                Nmem.putLong(event + SDL_TextInputEvent.TEXT, pendingText);
            }
            case 1024 -> { // MOUSE_MOTION
                Nmem.putLong(event + SDL_MouseMotionEvent.TIMESTAMP, ts);
                Nmem.putInt(event + SDL_MouseMotionEvent.WINDOWID, WINDOW_ID);
                Nmem.putInt(event + SDL_MouseMotionEvent.STATE, a);
                Nmem.putFloat(event + SDL_MouseMotionEvent.X, x);
                Nmem.putFloat(event + SDL_MouseMotionEvent.Y, y);
                Nmem.putFloat(event + SDL_MouseMotionEvent.XREL, dx);
                Nmem.putFloat(event + SDL_MouseMotionEvent.YREL, dy);
            }
            case 1025, 1026 -> { // MOUSE_BUTTON_DOWN / UP
                Nmem.putLong(event + SDL_MouseButtonEvent.TIMESTAMP, ts);
                Nmem.putInt(event + SDL_MouseButtonEvent.WINDOWID, WINDOW_ID);
                Nmem.putByte(event + SDL_MouseButtonEvent.BUTTON, (byte) a);
                Nmem.putByte(event + SDL_MouseButtonEvent.DOWN, (byte) (type == 1025 ? 1 : 0));
                Nmem.putByte(event + SDL_MouseButtonEvent.CLICKS, (byte) Math.max(1, b));
                Nmem.putFloat(event + SDL_MouseButtonEvent.X, x);
                Nmem.putFloat(event + SDL_MouseButtonEvent.Y, y);
            }
            case 1027 -> { // MOUSE_WHEEL
                Nmem.putLong(event + SDL_MouseWheelEvent.TIMESTAMP, ts);
                Nmem.putInt(event + SDL_MouseWheelEvent.WINDOWID, WINDOW_ID);
                Nmem.putFloat(event + SDL_MouseWheelEvent.X, dx);
                Nmem.putFloat(event + SDL_MouseWheelEvent.Y, dy);
                Nmem.putFloat(event + SDL_MouseWheelEvent.MOUSE_X, x);
                Nmem.putFloat(event + SDL_MouseWheelEvent.MOUSE_Y, y);
            }
            default -> { // window events (0x200 range) and anything else carry data1/data2
                Nmem.putLong(event + SDL_WindowEvent.TIMESTAMP, ts);
                Nmem.putInt(event + SDL_WindowEvent.WINDOWID, WINDOW_ID);
                Nmem.putInt(event + SDL_WindowEvent.DATA1, a);
                Nmem.putInt(event + SDL_WindowEvent.DATA2, b);
            }
        }
        Nmem.putInt(block + 8, read + consumed);
        return true;
    }

    /** Drops queued events in [min, max] (SDL_FlushEvents). */
    public static void flushEvents(int min, int max) {
        if (block == 0) {
            return;
        }
        int write = Nmem.getInt(block + 4);
        int read = Nmem.getInt(block + 8);
        while (read != write) {
            long slot = block + 64 + (long) (read % SLOTS) * SLOT;
            int type = Nmem.getInt(slot);
            if (type < min || type > max) {
                break;
            }
            read += type == 771 ? 1 + (Nmem.getInt(slot + 4) + SLOT - 1) / SLOT : 1;
        }
        Nmem.putInt(block + 8, read);
    }

    // ---- host actions ----

    public static void setRelativeMouseMode(boolean on) {
        relativeMouse = on;
        hostPointerLock(on);
    }

    public static boolean relativeMouseMode() {
        return relativeMouse;
    }

    @JS.Coerce
    @JS(value = "globalThis.mcweb?.setPointerLock?.(on);", args = {"on"})
    private static native void hostPointerLock(boolean on);

    public static void setTitle(String t) {
        title = t;
        hostTitle(t);
    }

    public static String title() {
        return title;
    }

    @JS.Coerce
    @JS(value = "if (typeof document !== 'undefined') document.title = t;", args = {"t"})
    private static native void hostTitle(String t);

    @JS.Coerce
    @JS(value = "globalThis.mcweb?.setCursor?.(id);", args = {"id"})
    public static native void setCursor(int id);

    @JS.Coerce
    @JS(value = "globalThis.mcweb?.openUrl?.(url);", args = {"url"})
    public static native void openUrl(String url);

    @JS.Coerce
    @JS(value = "globalThis.mcweb?.setTextInput?.(on);", args = {"on"})
    public static native void textInput(boolean on);

    public static void setClipboard(String text) {
        clipboard = text;
        hostSetClipboard(text);
    }

    public static String clipboard() {
        String fromHost = hostGetClipboard();
        return fromHost != null ? fromHost : clipboard;
    }

    @JS.Coerce
    @JS(value = "globalThis.mcweb?.setClipboard?.(t);", args = {"t"})
    private static native void hostSetClipboard(String t);

    @JS.Coerce
    @JS(value = "return globalThis.mcweb?.getClipboard?.() ?? null;", args = {})
    private static native String hostGetClipboard();

    public static void messageBox(String title, String message) {
        WebJs.log("[sdl] message box: " + title + ": " + message);
        hostMessage(title, message);
    }

    @JS.Coerce
    @JS(value = "globalThis.mcweb?.showMessage?.(t, m);", args = {"t", "m"})
    private static native void hostMessage(String t, String m);

    static byte[] utf8(CharSequence s) {
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }
}
