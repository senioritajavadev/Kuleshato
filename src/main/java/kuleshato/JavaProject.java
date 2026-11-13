package kuleshato;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class JavaProject {
    private static final Set<String> IGNORED = Set.of(
        ".git", ".idea", ".vscode", "node_modules", "target", "build", "out", "dist",
        ".gradle", "vendor"
    );
    private static final Set<String> BUILD_FILES = Set.of(
        "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
        "settings.gradle.kts", "gradlew", "gradlew.bat"
    );

    private JavaProject() {}

    static Path requireWorkspace(Path requested) throws IOException {
        Path root = requested.toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Рабочий путь должен указывать на каталог.");
        if (!containsJavaProject(root)) {
            throw new IOException(
                "Kuleshato работает только с Java-проектами. В каталоге не обнаружены Java-исходники "
                    + "или конфигурация Maven/Gradle с Java-кодом: " + root
            );
        }
        return root;
    }

    static boolean containsJavaProject(Path root) throws IOException {
        List<Path> javaFiles = new ArrayList<>();
        List<Path> buildFiles = new ArrayList<>();
        int[] inspected = {0};
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (!directory.equals(root)
                    && (Files.isSymbolicLink(directory) || IGNORED.contains(directory.getFileName().toString()))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (++inspected[0] > 100_000) return FileVisitResult.TERMINATE;
                if (attrs.isRegularFile()) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".java")) javaFiles.add(root.relativize(file));
                    if (BUILD_FILES.contains(name)) buildFiles.add(root.relativize(file));
                }
                return inspected[0] > 100_000 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        for (Path javaFile : javaFiles) {
            if (javaFile.getNameCount() == 1 || isConventionalSource(javaFile)) return true;
            for (Path buildFile : buildFiles) {
                Path buildDirectory = buildFile.getParent();
                if (buildDirectory == null || javaFile.startsWith(buildDirectory)) return true;
            }
        }
        return false;
    }

    private static boolean isConventionalSource(Path javaFile) {
        int count = javaFile.getNameCount();
        if (count == 2 && javaFile.getName(0).toString().equals("src")) return true;
        for (int index = 0; index + 2 < count; index++) {
            if (!javaFile.getName(index).toString().equals("src")) continue;
            String sourceSet = javaFile.getName(index + 1).toString();
            if ((sourceSet.equals("main") || sourceSet.equals("test")
                || sourceSet.equals("integrationTest") || sourceSet.equals("testFixtures"))
                && javaFile.getName(index + 2).toString().equals("java")) {
                return true;
            }
        }
        return false;
    }

}
