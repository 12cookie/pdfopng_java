package com.example.demo.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the native libraries and lays out N independent "slots" for {@link PdfRenderService}
 * to load, each slot being a directory holding its own copy of {@code libpdf_render} and
 * {@code libpdfium}.
 *
 * <h2>Why copies at all?</h2>
 * <p>
 * pdfium (Google's PDF engine, written in C++) keeps global state and is not thread-safe, so a
 * single loaded copy can only render one page at a time. The operating system's dynamic loader
 * ({@code dlopen}) identifies a library by its <em>file</em>: opening the same path twice returns
 * the same already-loaded image, but opening N distinct files maps N separate images, each with
 * its own private copy of every global variable. N copies therefore behave like N completely
 * independent pdfium installations living in one process, and can run in parallel.
 * <p>
 * Both libraries must be duplicated per slot. {@code libpdf_render} holds one global
 * {@code Pdfium} and itself {@code dlopen}s {@code libpdfium}; if six {@code libpdf_render}
 * copies all pointed at one {@code libpdfium.dylib} file, the loader would share that single
 * pdfium image between them and the concurrency would silently disappear.
 *
 * <h2>Where the files come from</h2>
 * <p>
 * The Maven build (see {@code native/pdf-render/stage-natives.sh} and the
 * {@code pdf.render.slots} property) bundles ready-made slot directories on the classpath as
 * {@code native/<platform>/slot-0 ... slot-N}. At run time, sources are tried in this order:
 * <ol>
 *   <li>explicit paths from {@code pdf.render.library-path} / {@code pdf.render.pdfium-path};</li>
 *   <li>the bundled classpath slots;</li>
 *   <li>the cargo output directory {@code native/pdf-render/target/release} plus the pdfium
 *       download in {@code native/pdf-render/lib/<platform>} - for IDE runs without a Maven build.</li>
 * </ol>
 *
 * <h2>Why extract to a temp directory?</h2>
 * <p>
 * The OS loader needs a real file on disk; it cannot {@code dlopen} an entry inside a jar. So
 * classpath resources are copied out into a fresh temporary directory (one subdirectory per
 * slot), and for the explicit/cargo sources the single file is replicated N times there as
 * well. Either way every slot ends up as a distinct file, which is what makes the trick work.
 * The files are marked {@code deleteOnExit}; libraries in use cannot be deleted earlier.
 *
 * <p>{@link #platform()} and {@link #libraryFileName(String)} mirror the naming used by the build
 * scripts ({@code darwin-aarch64}, {@code libpdf_render.dylib}, ...), so the two sides agree on
 * where things are.
 */
final class NativeLibraryLoader {

    private static final Logger log = LoggerFactory.getLogger(NativeLibraryLoader.class);

    private static final String RENDER_LIB_BASE = "pdf_render";
    private static final String PDFIUM_LIB_BASE = "pdfium";
    private static final Path CARGO_OUTPUT = Path.of("native", "pdf-render", "target", "release");
    private static final Path PDFIUM_DOWNLOAD = Path.of("native", "pdf-render", "lib");

    /**
     * The two files that make up one renderer instance.
     *
     * @param index    slot number, for logging
     * @param renderer path to this slot's private copy of {@code libpdf_render}
     * @param pdfium   path to this slot's private copy of {@code libpdfium}, or {@code null} to let
     *                 the Rust side search the system (only sensible on a machine with a matching
     *                 pdfium installed; all slots would then share it)
     */
    record Slot(int index, Path renderer, Path pdfium) {
    }

    private NativeLibraryLoader() {
    }

    /**
     * Materializes the slots.
     *
     * @param explicitRenderer {@code pdf.render.library-path}, or {@code null}
     * @param explicitPdfium   {@code pdf.render.pdfium-path}, or {@code null}
     * @param requested        number of slots wanted; {@code <= 0} means "as many as the build bundled"
     *                         (or 1 when nothing is bundled). Bundled slots cannot be exceeded because
     *                         only the build knows how many copies exist in the jar.
     * @return one {@link Slot} per renderer, ready to pass to {@link PdfRenderNative}
     * @throws IOException if no renderer library can be found or files cannot be copied
     */
    static List<Slot> load(Path explicitRenderer, Path explicitPdfium, int requested) throws IOException {
        String platform = platform();
        String rendererFile = libraryFileName(RENDER_LIB_BASE);
        String pdfiumFile = libraryFileName(PDFIUM_LIB_BASE);
        Path workDir = Files.createTempDirectory("pdf-render-");
        workDir.toFile().deleteOnExit();

        if (explicitRenderer != null) {
            requireFile(explicitRenderer, "pdf.render.library-path");
            if (explicitPdfium != null) {
                requireFile(explicitPdfium, "pdf.render.pdfium-path");
            }

            int count = Math.max(requested, 1);
            log.info("Staging {} pdf-render slot(s) from explicit paths (renderer={}, pdfium={})",
                    count, explicitRenderer, explicitPdfium == null ? "<system>" : explicitPdfium);
            return replicate(workDir, explicitRenderer, explicitPdfium, count);
        }

        String resourceDir = "native/" + platform + "/";
        int bundled = countBundledSlots(resourceDir, rendererFile);
        if (bundled > 0) {
            int count = requested <= 0 ? bundled : requested;
            if (count > bundled) {
                log.warn("pdf.render.concurrency={} exceeds the {} slot(s) bundled by the build "
                        + "(pom property pdf.render.slots); using {}", requested, bundled, bundled);
                count = bundled;
            }

            List<Slot> slots = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String slotDir = resourceDir + "slot-" + i + "/";
                Path dir = Files.createDirectories(workDir.resolve("slot-" + i));
                Path renderer = extract(slotDir + rendererFile, dir.resolve(rendererFile));
                Path pdfium = explicitPdfium != null
                        ? copy(explicitPdfium, dir.resolve(pdfiumFile))
                        : resourceExists(slotDir + pdfiumFile) ? extract(slotDir + pdfiumFile, dir.resolve(pdfiumFile)) : null;
                slots.add(new Slot(i, renderer, pdfium));
            }

            log.info("Staged {} of {} bundled pdf-render slot(s) from classpath {}", count, bundled, resourceDir);
            return slots;
        }

        Path cargoRenderer = CARGO_OUTPUT.resolve(rendererFile).toAbsolutePath();
        if (Files.isRegularFile(cargoRenderer)) {
            Path pdfium = explicitPdfium;
            if (pdfium == null) {
                Path downloaded = PDFIUM_DOWNLOAD.resolve(platform).resolve(pdfiumFile).toAbsolutePath();
                pdfium = Files.isRegularFile(downloaded) ? downloaded : null;
            }

            int count = Math.max(requested, 1);
            log.info("Staging {} pdf-render slot(s) from cargo output {} (pdfium={})",
                    count, cargoRenderer, pdfium == null ? "<system>" : pdfium);
            return replicate(workDir, cargoRenderer, pdfium, count);
        }

        throw new IOException("Could not find " + rendererFile + " for platform " + platform
                + ". Run `mvn process-resources` (which invokes cargo) or set pdf.render.library-path.");
    }

    /**
     * Copies one renderer (and optionally one pdfium) file {@code count} times into per-slot directories.
     */
    private static List<Slot> replicate(Path workDir, Path renderer, Path pdfium, int count) throws IOException {
        List<Slot> slots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Path dir = Files.createDirectories(workDir.resolve("slot-" + i));
            Path rendererCopy = copy(renderer, dir.resolve(renderer.getFileName()));
            Path pdfiumCopy = pdfium == null ? null : copy(pdfium, dir.resolve(pdfium.getFileName()));
            slots.add(new Slot(i, rendererCopy, pdfiumCopy));
        }

        return slots;
    }

    /**
     * Probes {@code slot-0}, {@code slot-1}, ... on the classpath until one is missing.
     */
    private static int countBundledSlots(String resourceDir, String rendererFile) {
        int n = 0;
        while (resourceExists(resourceDir + "slot-" + n + "/" + rendererFile)) {
            n++;
        }

        return n;
    }

    private static boolean resourceExists(String resource) {
        return NativeLibraryLoader.class.getClassLoader().getResource(resource) != null;
    }

    /**
     * Copies a classpath resource (possibly inside the jar) to a real file the OS loader can open.
     */
    private static Path extract(String resource, Path target) throws IOException {
        try (InputStream in = NativeLibraryLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing classpath resource " + resource);
            }

            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }

        target.toFile().deleteOnExit();
        return target;
    }

    private static Path copy(Path source, Path target) throws IOException {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        target.toFile().deleteOnExit();
        return target;
    }

    private static void requireFile(Path path, String property) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException(property + " does not exist: " + path);
        }
    }

    /**
     * OS + CPU architecture in the form the build uses ({@code darwin-aarch64}, {@code linux-x86_64}, ...).
     * A native library only runs on the platform it was compiled for, so this selects the right
     * bundled directory. Must match the {@code native.platform} profiles in pom.xml.
     */
    static String platform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String osName = os.contains("mac") || os.contains("darwin") ? "darwin"
                : os.contains("win") ? "windows"
                : "linux";

        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String archName = switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };

        return osName + "-" + archName;
    }

    /**
     * Platform-specific shared-library file name: {@code libX.dylib} (macOS), {@code libX.so} (Linux), {@code X.dll} (Windows).
     */
    static String libraryFileName(String base) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "lib" + base + ".dylib";
        }

        if (os.contains("win")) {
            return base + ".dll";
        }

        return "lib" + base + ".so";
    }
}
