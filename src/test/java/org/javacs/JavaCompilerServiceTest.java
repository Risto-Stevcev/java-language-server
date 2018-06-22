package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.*;
import java.util.stream.Collectors;
import javax.lang.model.element.*;
import org.junit.Test;

public class JavaCompilerServiceTest {
    private static final Logger LOG = Logger.getLogger("main");

    private JavaCompilerService compiler =
            new JavaCompilerService(Collections.singleton(resourcesDir()), Collections.emptySet());

    private static Path resourcesDir() {
        try {
            return Paths.get(JavaCompilerServiceTest.class.getResource("/HelloWorld.java").toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private String contents(String resourceFile) {
        try (InputStream in = JavaCompilerServiceTest.class.getResourceAsStream(resourceFile)) {
            return new BufferedReader(new InputStreamReader(in)).lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void element() {
        Element found = compiler.element(URI.create("/HelloWorld.java"), contents("/HelloWorld.java"), 3, 24);

        assertThat(found.getSimpleName(), hasToString(containsString("println")));
    }

    @Test
    public void pruneMethods() {
        Pruner pruner = new Pruner(URI.create("/PruneMethods.java"), contents("/PruneMethods.java"));
        pruner.prune(6, 19);
        String expected = contents("/PruneMethods_erased.java");
        assertThat(pruner.contents(), equalToIgnoringWhiteSpace(expected));
    }

    @Test
    public void pruneToEndOfBlock() {
        Pruner pruner = new Pruner(URI.create("/PruneToEndOfBlock.java"), contents("/PruneToEndOfBlock.java"));
        pruner.prune(4, 18);
        String expected = contents("/PruneToEndOfBlock_erased.java");
        assertThat(pruner.contents(), equalToIgnoringWhiteSpace(expected));
    }

    private List<String> completionNames(List<Completion> found) {
        List<String> result = new ArrayList<>();
        for (Completion c : found) {
            if (c.element != null) result.add(c.element.getSimpleName().toString());
            else if (c.packagePart != null) result.add(c.packagePart.name);
            else if (c.classSymbol != null) result.add("class");
        }
        return result;
    }

    private List<String> elementNames(List<Element> found) {
        return found.stream().map(e -> e.getSimpleName().toString()).collect(Collectors.toList());
    }

    @Test
    public void identifiers() {
        List<Element> found =
                compiler.scopeMembers(
                        URI.create("/CompleteIdentifiers.java"), contents("/CompleteIdentifiers.java"), 13, 21);
        List<String> names = elementNames(found);
        assertThat(names, hasItem("completeLocal"));
        assertThat(names, hasItem("completeParam"));
        assertThat(names, hasItem("super"));
        assertThat(names, hasItem("this"));
        assertThat(names, hasItem("completeOtherMethod"));
        assertThat(names, hasItem("completeInnerField"));
        assertThat(names, hasItem("completeOuterField"));
        assertThat(names, hasItem("completeOuterStatic"));
        assertThat(names, hasItem("CompleteIdentifiers"));
    }

    @Test
    public void completeIdentifiers() {
        List<Completion> found =
                compiler.completions(
                        URI.create("/CompleteIdentifiers.java"), contents("/CompleteIdentifiers.java"), 13, 21);
        List<String> names = completionNames(found);
        assertThat(names, hasItem("completeLocal"));
        assertThat(names, hasItem("completeParam"));
        assertThat(names, hasItem("super"));
        assertThat(names, hasItem("this"));
        assertThat(names, hasItem("completeOtherMethod"));
        assertThat(names, hasItem("completeInnerField"));
        assertThat(names, hasItem("completeOuterField"));
        assertThat(names, hasItem("completeOuterStatic"));
        assertThat(names, hasItem("CompleteIdentifiers"));
    }

    @Test
    public void members() {
        List<Completion> found =
                compiler.members(URI.create("/CompleteMembers.java"), contents("/CompleteMembers.java"), 3, 14);
        List<String> names = completionNames(found);
        assertThat(names, hasItem("subMethod"));
        assertThat(names, hasItem("superMethod"));
        assertThat(names, hasItem("equals"));
    }

    @Test
    public void completeMembers() {
        List<Completion> found =
                compiler.completions(URI.create("/CompleteMembers.java"), contents("/CompleteMembers.java"), 3, 15);
        List<String> names = completionNames(found);
        assertThat(names, hasItem("subMethod"));
        assertThat(names, hasItem("superMethod"));
        assertThat(names, hasItem("equals"));
    }

    @Test
    public void completeClass() {
        List<Completion> found =
                compiler.completions(URI.create("/CompleteClass.java"), contents("/CompleteClass.java"), 3, 23);
        List<String> names = completionNames(found);
        assertThat(names, hasItems("staticMethod", "staticField"));
        assertThat(names, hasItems("class"));
        assertThat(names, not(hasItem("instanceMethod")));
        assertThat(names, not(hasItem("instanceField")));
    }

    @Test
    public void completeImports() {
        List<Completion> found =
                compiler.completions(URI.create("/CompleteImports.java"), contents("/CompleteImports.java"), 1, 18);
        List<String> names = completionNames(found);
        assertThat(names, hasItem("List"));
        assertThat(names, hasItem("concurrent"));
    }

    @Test
    public void gotoDefinition() {
        Optional<TreePath> def =
                compiler.definition(URI.create("/GotoDefinition.java"), contents("/GotoDefinition.java"), 3, 12);
        assertTrue(def.isPresent());

        TreePath t = def.get();
        CompilationUnitTree unit = t.getCompilationUnit();
        assertThat(unit.getSourceFile().getName(), endsWith("GotoDefinition.java"));

        Trees trees = compiler.trees();
        SourcePositions pos = trees.getSourcePositions();
        LineMap lines = unit.getLineMap();
        long start = pos.getStartPosition(unit, t.getLeaf());
        long line = lines.getLineNumber(start);
        assertThat(line, equalTo(6L));
    }

    @Test
    public void references() {
        List<TreePath> refs =
                compiler.references(URI.create("/GotoDefinition.java"), contents("/GotoDefinition.java"), 6, 13);
        boolean found = false;
        for (TreePath t : refs) {
            CompilationUnitTree unit = t.getCompilationUnit();
            String name = unit.getSourceFile().getName();
            Trees trees = compiler.trees();
            SourcePositions pos = trees.getSourcePositions();
            LineMap lines = unit.getLineMap();
            long start = pos.getStartPosition(unit, t.getLeaf());
            long line = lines.getLineNumber(start);
            if (name.endsWith("GotoDefinition.java") && line == 3) found = true;
        }

        if (!found) fail(String.format("No GotoDefinition.java line 3 in %s", refs));
    }

    @Test
    public void overloads() {
        MethodInvocation found =
                compiler.methodInvocation(URI.create("/Overloads.java"), contents("/Overloads.java"), 3, 15).get();

        assertThat(
                found.overloads, containsInAnyOrder(hasToString("print(int)"), hasToString("print(java.lang.String)")));
    }
}
