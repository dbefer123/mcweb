package dev.mcwebclient.web.sdl;

/**
 * SDL3 scancode → keycode and key names for a US layout. Printable keys map to their
 * character; others to scancode | SDLK_SCANCODE_MASK, as SDL does. (The page also
 * sends layout-aware keycodes with each key event; this table serves
 * SDL_GetKeyFromScancode, used for key-binding labels.)
 */
final class Keys {
    private static final int SCANCODE_MASK = 1 << 30;
    private static final String DIGITS = "1234567890";

    private Keys() {
    }

    static int keycodeFor(int scancode, boolean shifted) {
        if (scancode >= 4 && scancode <= 29) {
            return 'a' + (scancode - 4);
        }
        if (scancode >= 30 && scancode <= 39) {
            return DIGITS.charAt(scancode - 30);
        }
        return switch (scancode) {
            case 40 -> '\r';
            case 41 -> 0x1b;
            case 42 -> '\b';
            case 43 -> '\t';
            case 44 -> ' ';
            case 45 -> '-';
            case 46 -> '=';
            case 47 -> '[';
            case 48 -> ']';
            case 49 -> '\\';
            case 51 -> ';';
            case 52 -> '\'';
            case 53 -> '`';
            case 54 -> ',';
            case 55 -> '.';
            case 56 -> '/';
            case 76 -> 0x7f; // DELETE
            default -> scancode | SCANCODE_MASK;
        };
    }

    static String nameFor(int key) {
        if ((key & SCANCODE_MASK) == 0) {
            return switch (key) {
                case '\r' -> "Return";
                case 0x1b -> "Escape";
                case '\b' -> "Backspace";
                case '\t' -> "Tab";
                case ' ' -> "Space";
                case 0x7f -> "Delete";
                default -> key > 0 && key < 0x110000 ? new String(Character.toChars(key)).toUpperCase() : "";
            };
        }
        int sc = key & ~SCANCODE_MASK;
        if (sc >= 58 && sc <= 69) {
            return "F" + (sc - 57);
        }
        if (sc >= 104 && sc <= 115) {
            return "F" + (sc - 91);
        }
        if (sc >= 89 && sc <= 97) {
            return "Keypad " + (sc - 88);
        }
        return switch (sc) {
            case 57 -> "CapsLock";
            case 70 -> "PrintScreen";
            case 71 -> "ScrollLock";
            case 72 -> "Pause";
            case 73 -> "Insert";
            case 74 -> "Home";
            case 75 -> "PageUp";
            case 77 -> "End";
            case 78 -> "PageDown";
            case 79 -> "Right";
            case 80 -> "Left";
            case 81 -> "Down";
            case 82 -> "Up";
            case 83 -> "Numlock";
            case 84 -> "Keypad /";
            case 85 -> "Keypad *";
            case 86 -> "Keypad -";
            case 87 -> "Keypad +";
            case 88 -> "Keypad Enter";
            case 98 -> "Keypad 0";
            case 99 -> "Keypad .";
            case 101 -> "Application";
            case 224 -> "Left Ctrl";
            case 225 -> "Left Shift";
            case 226 -> "Left Alt";
            case 227 -> "Left GUI";
            case 228 -> "Right Ctrl";
            case 229 -> "Right Shift";
            case 230 -> "Right Alt";
            case 231 -> "Right GUI";
            default -> "";
        };
    }
}
