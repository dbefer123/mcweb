// Shared build configuration. Paths can be overridden with environment variables.
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const appData = process.env.APPDATA || join(process.env.USERPROFILE || "", "AppData", "Roaming");

export const PRISM_DIR = process.env.PRISM_DIR || join(appData, "PrismLauncher");
export const INSTANCE = process.env.PRISM_INSTANCE || "MinecraftWebpage";
export const INSTANCE_DIR = join(PRISM_DIR, "instances", INSTANCE);
export const GAME_DIR = join(INSTANCE_DIR, "minecraft");

export const GRAALVM_HOME = process.env.GRAALVM_HOME || "D:\\Claude\\toolchains\\graalvm-25.2.4+7.1";
export const JAVA = join(GRAALVM_HOME, "bin", process.platform === "win32" ? "java.exe" : "java");
export const JAVAC = join(GRAALVM_HOME, "bin", process.platform === "win32" ? "javac.exe" : "javac");
export const JAR = join(GRAALVM_HOME, "bin", process.platform === "win32" ? "jar.exe" : "jar");
export const TMP = process.env.MCWEB_TMP || "D:\\gradle-tmp\\tmp";

export const BUILD = join(ROOT, "build");
export const INPUTS = join(ROOT, "inputs");
