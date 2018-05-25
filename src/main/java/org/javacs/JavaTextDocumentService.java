package org.javacs;

import com.google.common.base.Joiner;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;

class JavaTextDocumentService implements TextDocumentService {
    private final JavaLanguageServer server;
    private final Map<URI, VersionedContent> activeDocuments = new HashMap<>();

    JavaTextDocumentService(JavaLanguageServer server) {
        this.server = server;
    }

    void doLint(Collection<URI> paths) {
        LOG.info("Lint " + Joiner.on(", ").join(paths));

        // TODO
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
            TextDocumentPositionParams position) {
        URI uri = URI.create(position.getTextDocument().getUri());
        String content = contents(uri).content;
        int line = position.getPosition().getLine() + 1;
        int column = position.getPosition().getCharacter() + 1;
        List<CompletionItem> result = new ArrayList<>();
        for (Completion c : server.compiler.completions(uri, content, line, column)) {
            CompletionItem i = new CompletionItem();
            if (c.element != null) {
                i.setLabel(c.element.getSimpleName().toString());
                // TODO details
            } else if (c.packagePart != null) {
                i.setLabel(c.packagePart.name);
                // TODO details
            }
            result.add(i);
        }
        return CompletableFuture.completedFuture(Either.forRight(new CompletionList(false, result)));
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
        return CompletableFuture.completedFuture(unresolved); // TODO
    }

    @Override
    public CompletableFuture<Hover> hover(TextDocumentPositionParams position) {
        URI uri = URI.create(position.getTextDocument().getUri());
        String content = contents(uri).content;
        int line = position.getPosition().getLine() + 1;
        int column = position.getPosition().getCharacter() + 1;
        Element e = server.compiler.element(uri, content, line, column);
        if (e != null && e.asType() != null) {
            MarkedString hover = new MarkedString("java", e.asType().toString());
            Hover result = new Hover(Collections.singletonList(Either.forRight(hover)));
            return CompletableFuture.completedFuture(result);
        } else return CompletableFuture.completedFuture(new Hover(Collections.emptyList()));
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(TextDocumentPositionParams position) {
        URI uri = URI.create(position.getTextDocument().getUri());
        String content = contents(uri).content;
        int line = position.getPosition().getLine() + 1;
        int column = position.getPosition().getCharacter() + 1;
        List<SignatureInformation> result = new ArrayList<>();
        for (ExecutableElement e : server.compiler.overloads(uri, content, line, column)) {
            SignatureInformation i = new SignatureInformation();
            i.setLabel(e.getSimpleName().toString());
            List<ParameterInformation> ps = new ArrayList<>();
            for (VariableElement v : e.getParameters()) {
                ParameterInformation p = new ParameterInformation();
                p.setLabel(v.getSimpleName().toString());
                // TODO use type when name is not available
            }
            i.setParameters(ps);
            result.add(i);
        }
        int activeSig = 0; // TODO
        int activeParam = 0; // TODO
        return CompletableFuture.completedFuture(new SignatureHelp(result, activeSig, activeParam));
    }

    private Location location(TreePath p) {
        Trees trees = server.compiler.trees();
        SourcePositions pos = trees.getSourcePositions();
        CompilationUnitTree cu = p.getCompilationUnit();
        LineMap lines = cu.getLineMap();
        long start = pos.getStartPosition(cu, p.getLeaf()), end = pos.getEndPosition(cu, p.getLeaf());
        int startLine = (int) lines.getLineNumber(start) - 1, startCol = (int) lines.getColumnNumber(start) - 1;
        int endLine = (int) lines.getLineNumber(end) - 1, endCol = (int) lines.getColumnNumber(end) - 1;
        URI dUri = cu.getSourceFile().toUri();
        return new Location(
                dUri.toString(), new Range(new Position(startLine, startCol), new Position(endLine, endCol)));
    }

    @Override
    public CompletableFuture<List<? extends Location>> definition(TextDocumentPositionParams position) {
        URI uri = URI.create(position.getTextDocument().getUri());
        String content = contents(uri).content;
        int line = position.getPosition().getLine() + 1;
        int column = position.getPosition().getCharacter() + 1;
        List<Location> result = new ArrayList<>();
        server.compiler
                .definition(uri, content, line, column)
                .ifPresent(
                        d -> {
                            result.add(location(d));
                        });
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams position) {
        URI uri = URI.create(position.getTextDocument().getUri());
        String content = contents(uri).content;
        int line = position.getPosition().getLine() + 1;
        int column = position.getPosition().getCharacter() + 1;
        List<Location> result = new ArrayList<>();
        for (TreePath r : server.compiler.references(uri, content, line, column)) {
            result.add(location(r));
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(TextDocumentPositionParams position) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends SymbolInformation>> documentSymbol(DocumentSymbolParams params) {
        return null; // TODO
    }

    @Override
    public CompletableFuture<List<? extends Command>> codeAction(CodeActionParams params) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        return null;
    }

    @Override
    public CompletableFuture<CodeLens> resolveCodeLens(CodeLens unresolved) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams params) {
        return null;
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        return null;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        TextDocumentItem document = params.getTextDocument();
        URI uri = URI.create(document.getUri());

        activeDocuments.put(uri, new VersionedContent(document.getText(), document.getVersion()));

        doLint(Collections.singleton(uri));
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        VersionedTextDocumentIdentifier document = params.getTextDocument();
        URI uri = URI.create(document.getUri());
        VersionedContent existing = activeDocuments.get(uri);
        String newText = existing.content;

        if (document.getVersion() > existing.version) {
            for (TextDocumentContentChangeEvent change : params.getContentChanges()) {
                if (change.getRange() == null)
                    activeDocuments.put(uri, new VersionedContent(change.getText(), document.getVersion()));
                else newText = patch(newText, change);
            }

            activeDocuments.put(uri, new VersionedContent(newText, document.getVersion()));
        } else LOG.warning("Ignored change with version " + document.getVersion() + " <= " + existing.version);
    }

    private String patch(String sourceText, TextDocumentContentChangeEvent change) {
        try {
            Range range = change.getRange();
            BufferedReader reader = new BufferedReader(new StringReader(sourceText));
            StringWriter writer = new StringWriter();

            // Skip unchanged lines
            int line = 0;

            while (line < range.getStart().getLine()) {
                writer.write(reader.readLine() + '\n');
                line++;
            }

            // Skip unchanged chars
            for (int character = 0; character < range.getStart().getCharacter(); character++)
                writer.write(reader.read());

            // Write replacement text
            writer.write(change.getText());

            // Skip replaced text
            reader.skip(change.getRangeLength());

            // Write remaining text
            while (true) {
                int next = reader.read();

                if (next == -1) return writer.toString();
                else writer.write(next);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        TextDocumentIdentifier document = params.getTextDocument();
        URI uri = URI.create(document.getUri());

        // Remove from source cache
        activeDocuments.remove(uri);

        // Clear diagnostics
        server.publishDiagnostics(uri, Collections.emptyList());
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // Re-lint all active documents
        doLint(activeDocuments.keySet());
    }

    VersionedContent contents(URI openFile) {
        if (activeDocuments.containsKey(openFile)) {
            return activeDocuments.get(openFile);
        } else
            try {
                String content = Files.readAllLines(Paths.get(openFile)).stream().collect(Collectors.joining("\n"));
                return new VersionedContent(content, -1);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
