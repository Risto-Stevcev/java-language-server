package org.javacs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import javax.tools.*;

class SourceFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
    public SourceFileManager() {
        super(createDelegateFileManager());
    }

    private static StandardJavaFileManager createDelegateFileManager() {
        var compiler = ServiceLoader.load(JavaCompiler.class).iterator().next();
        return compiler.getStandardFileManager(SourceFileManager::logError, null, Charset.defaultCharset());
    }

    private static void logError(Diagnostic<?> error) {
        LOG.warning(error.getMessage(null));
    }

    // TODO if .class files get moved around, this could become wrong
    // class path includes generated .class files, so this can definitely happen
    private final LruCache<String, Iterable<JavaFileObject>> cacheClassPath = new LruCache<>(1000, this::listClassPath);

    @Override
    public Iterable<JavaFileObject> list(
            Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
        if (location == StandardLocation.SOURCE_PATH) {
            var stream = FileStore.list(packageName).stream().map(this::asJavaFileObject);
            return stream::iterator;
        } else if (location == StandardLocation.CLASS_PATH) {
            // Listing large class paths is expensive
            return cacheClassPath.get(packageName);
        } else {
            return super.list(location, packageName, kinds, recurse);
        }
    }

    private Iterable<JavaFileObject> listClassPath(String packageName) {
        try {
            return super.list(StandardLocation.CLASS_PATH, packageName, Set.of(JavaFileObject.Kind.values()), false);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private JavaFileObject asJavaFileObject(Path file) {
        // TODO erase method bodies of files that are not open
        return new SourceFileObject(file);
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (location == StandardLocation.SOURCE_PATH) {
            var source = (SourceFileObject) file;
            var packageName = FileStore.packageName(source.path);
            var className = removeExtension(source.path.getFileName().toString());
            if (!packageName.isEmpty()) className = packageName + "." + className;
            return className;
        } else {
            return super.inferBinaryName(location, file);
        }
    }

    private String removeExtension(String fileName) {
        var lastDot = fileName.lastIndexOf(".");
        return (lastDot == -1 ? fileName : fileName.substring(0, lastDot));
    }

    @Override
    public boolean hasLocation(Location location) {
        return location == StandardLocation.SOURCE_PATH || super.hasLocation(location);
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind)
            throws IOException {
        if (location == StandardLocation.SOURCE_PATH) {
            var packageName = Parser.mostName(className);
            var simpleClassName = Parser.lastName(className);
            for (var f : FileStore.list(packageName)) {
                if (f.getFileName().toString().equals(simpleClassName + kind.extension)) return new SourceFileObject(f);
            }
            return null;
        }
        return super.getJavaFileForInput(location, className, kind);
    }

    @Override
    public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
        if (location == StandardLocation.SOURCE_PATH) {
            return null;
        }
        return super.getFileForInput(location, packageName, relativeName);
    }

    @Override
    public boolean contains(Location location, FileObject file) throws IOException {
        if (location == StandardLocation.SOURCE_PATH) {
            var source = (SourceFileObject) file;
            return FileStore.contains(source.path);
        } else {
            return super.contains(location, file);
        }
    }

    public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(Iterable<? extends File> files) {
        var result = new ArrayList<JavaFileObject>();
        for (var f : files) {
            result.add(new SourceFileObject(f.toPath()));
        }
        return result;
    }

    public void setLocation(Location location, Iterable<? extends File> files) throws IOException {
        fileManager.setLocation(location, files);
    }

    public void setLocationFromPaths(Location location, Collection<? extends Path> searchpath) throws IOException {
        fileManager.setLocationFromPaths(location, searchpath);
    }

    private static final Logger LOG = Logger.getLogger("main");
}
