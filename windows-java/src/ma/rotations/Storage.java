package ma.rotations;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Enregistrement : même dossier et même format que les versions précédentes (les données sont reprises). */
public final class Storage {
    private Storage() { }

    /** %APPDATA%\ProgrammeRotations sous Windows, ~/.local/share/ProgrammeRotations ailleurs. */
    public static Path dataDir() {
        String appdata = System.getenv("APPDATA");
        Path base = appdata != null && !appdata.isBlank() ? Paths.get(appdata) : Paths.get(System.getProperty("user.home"), ".local", "share");
        Path p = base.resolve("ProgrammeRotations");
        try { Files.createDirectories(p); } catch (IOException ignored) { }
        return p;
    }

    public static String read(Path p) throws IOException { return new String(Files.readAllBytes(p), StandardCharsets.UTF_8); }

    /** Écriture atomique (fichier temporaire puis remplacement) + copie de sécurité horodatée. */
    public static void writeAtomic(Path path, String content, int keepBackups) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path tmp = path.toAbsolutePath().getParent().resolve(".tmp_" + ProcessHandle.current().pid() + "_" + System.nanoTime() + ".json");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ch.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
                ch.force(true);
            }
            if (keepBackups > 0 && Files.exists(path)) {
                Path bdir = path.toAbsolutePath().getParent().resolve("sauvegardes");
                Files.createDirectories(bdir);
                String stem = path.getFileName().toString().replaceFirst("\\.json$", "");
                Files.copy(path, bdir.resolve(stem + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".json"),
                        StandardCopyOption.REPLACE_EXISTING);
                List<Path> olds = backups(bdir, stem);
                for (int i = 0; i < olds.size() - keepBackups; i++) Files.deleteIfExists(olds.get(i));
            }
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Sauvegardes triées de la plus ancienne à la plus récente. */
    public static List<Path> backups(Path bdir, String stem) throws IOException {
        if (!Files.isDirectory(bdir)) return new ArrayList<>();
        try (Stream<Path> s = Files.list(bdir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(stem + "_") && p.toString().endsWith(".json"))
                    .sorted().collect(Collectors.toList());
        }
    }

    public static void saveProgram(Core.Program prog, Path path, int keepBackups) throws IOException {
        writeAtomic(path, Json.pretty(Core.toDict(prog)), keepBackups);
    }

    public static Core.Program loadProgram(Path path) throws IOException {
        return Core.fromDict(Json.parse(read(path)));
    }
}
