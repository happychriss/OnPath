package org.teavm.classlib.java.lang;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.HashMap;
import java.util.Map;

/**
 * Replaces TeaVM's java.lang.Package (same members) and adds the manifest getters, which TeaVM lacks:
 * BRouter's OsmTrack reads getImplementationVersion() once for its GPX header. No manifest in the browser → null,
 * exactly what the JVM returns for classes without a manifest version.
 */
public class TPackage implements AnnotatedElement {
    private static final Map<String, TPackage> PACKAGES = new HashMap<>();
    private final String name;

    TPackage(String name) { this.name = name; }

    public String getName() { return name; }

    public static TPackage getPackage(String name) {
        TPackage p = PACKAGES.get(name);
        if (p == null) { p = new TPackage(name); PACKAGES.put(name, p); }
        return p;
    }

    public String getImplementationVersion() { return null; }
    public String getImplementationTitle() { return null; }
    public String getImplementationVendor() { return null; }
    public String getSpecificationVersion() { return null; }

    @Override public <T extends Annotation> T getAnnotation(Class<T> c) { return null; }
    @Override public Annotation[] getAnnotations() { return new Annotation[0]; }
    @Override public Annotation[] getDeclaredAnnotations() { return new Annotation[0]; }
}
